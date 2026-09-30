package com.example.agentdemo.rag.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.dto.KbSearchResult;
import com.example.agentdemo.dto.KbStats;
import com.example.agentdemo.dto.KbUploadResult;
import com.example.agentdemo.entity.KnowledgeDocument;
import com.example.agentdemo.mapper.KnowledgeDocumentMapper;
import com.example.agentdemo.rag.DocumentParser;
import com.example.agentdemo.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库服务实现。
 *
 * <p>向量库（Redis Stack）与 MySQL 清单表的分工与一致性策略：
 * <ul>
 *   <li>入库：先写向量、再写清单。分片向量是<b>分批</b>写入的，故有两个失败面：
 *       <b>(a)</b> 中途某批 embedding 失败 → 前几批已入库但清单未写；
 *       <b>(b)</b> 全部向量就绪但清单插入失败。
 *       两者都必须回滚向量，否则残留「检索可召回、清单查不到」的孤儿分片
 *       （俗称幽灵分片）。统一由 {@code ingest} 的 {@code catch} 调
 *       {@link #deleteVectorsByDocId(String)} 处理，其「SCAN 前缀兜底」分支
 *       正好覆盖「清单尚未写入」的情形。</li>
 *   <li>删除：先删向量（按分片 ID 直删）、再删清单与物理文件，接口幂等可重入。</li>
 * </ul>
 * 不做分布式事务，采用「尽力而为 + 可重入」，与项目既有的轻量风格一致。
 *
 * <p><b>为何删除不走 {@code vectorStore.delete(Filter.Expression)}？</b>
 * Spring AI 1.1.2 的 {@code RedisFilterExpressionConverter} 对 TAG 过滤值
 * <b>只加花括号、不做字符转义</b>（{@code stringValue()} 直接返回原值）。
 * 而 RediSearch 中 TAG 花括号内的 <b>连字符 {@code -} 属特殊字符</b>，
 * 本项目 {@code docId} 是标准 UUID（含 4 个连字符）→ 生成
 * {@code @docId:{307ea165-ba76-...}} → 抛
 * {@code JedisDataException: Syntax error at offset ... near f2922604688c}。
 * 实测三种写法：裸值 / 花括号（框架生成，报错）/ 转义连字符（命中）。
 * 故改为按分片 ID 直接删除，彻底绕开查询语法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final DocumentParser documentParser;
    private final VectorStore vectorStore;
    private final RagProperties props;
    private final KnowledgeDocumentMapper docMapper;

    /** 构造分片器。TokenTextSplitter 无状态，可安全复用。 */
    private TokenTextSplitter splitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(props.getChunkSize())
                .withMinChunkSizeChars(props.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(props.getMinChunkLengthToEmbed())
                .withMaxNumChunks(props.getMaxNumChunks())
                .withKeepSeparator(props.isKeepSeparator())
                .build();
    }

    @Override
    public KbUploadResult ingest(MultipartFile file) {
        // ---------- 1. 校验 ----------
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("缺少文件名");
        }
        String ext = documentParser.extOf(originalName);
        if (!DocumentParser.SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型：" + originalName + "，仅支持 " + DocumentParser.SUPPORTED_EXTENSIONS);
        }
        if (file.getSize() > props.getMaxFileSize()) {
            throw new IllegalArgumentException(
                    "文件过大：" + file.getSize() + " 字节，上限 " + props.getMaxFileSize() + " 字节");
        }

        String docId = UUID.randomUUID().toString();
        Path saved = saveToDisk(file, docId, originalName);

        try {
            // ---------- 2. 解析 ----------
            List<Document> raw = documentParser.parse(new FileSystemResource(saved), originalName);
            if (raw == null || raw.isEmpty()) {
                // 典型场景：图片型（扫描件）PDF 无文本层
                throw new IllegalArgumentException(
                        "该文件无可提取文本（可能是扫描件），暂不支持：" + originalName);
            }

            // ---------- 3. 切分 ----------
            List<Document> chunks = new ArrayList<>(splitter().apply(raw));
            if (chunks.size() > props.getMaxChunksPerDocument()) {
                log.warn("文档 {} 切分出 {} 个分片，超过上限 {}，将截断",
                        originalName, chunks.size(), props.getMaxChunksPerDocument());
                chunks = chunks.subList(0, props.getMaxChunksPerDocument());
            }

            // ---------- 4. 附加业务 metadata ----------
            long uploadedAt = System.currentTimeMillis();
            List<Document> enriched = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                Document src = chunks.get(i);
                Map<String, Object> meta = new HashMap<>();
                // 保留解析器自带的有用元信息（如 pdf 的页码、md 的标题层级）
                if (src.getMetadata() != null) {
                    meta.putAll(src.getMetadata());
                }
                meta.put("docId", docId);
                meta.put("source", "upload");
                meta.put("fileName", originalName);
                meta.put("fileType", ext);
                meta.put("chunkIndex", i);
                meta.put("uploadedAt", uploadedAt);
                // 显式指定 id，便于排查定位
                enriched.add(new Document(docId + ":" + i, src.getText(), meta));
            }

            // ---------- 5. 分批写入向量库 ----------
            writeInBatches(enriched);

            // ---------- 6. 写 MySQL 清单（失败则回滚向量） ----------
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setDocId(docId);
            doc.setFileName(originalName);
            doc.setFileType(ext);
            doc.setChunkCount(enriched.size());
            doc.setStoragePath(saved.toString());
            try {
                docMapper.insert(doc);
            } catch (Exception e) {
                log.error("写入文档清单失败，回滚已入库的向量 (docId={})", docId, e);
                deleteVectorsByDocId(docId);
                throw e;
            }

            log.info("文档入库完成：{} (docId={}, 分片数={})", originalName, docId, enriched.size());
            return new KbUploadResult(docId, originalName, enriched.size());

        } catch (RuntimeException e) {
            // 解析 / 切分 / 向量写入任一环节失败：必须做两件事，避免留下不一致状态。
            //
            // ① 回滚已写入的分片向量。
            //    关键场景：writeInBatches 是「逐批」写入，若第 N 批 embedding 失败，
            //    第 1..N-1 批已进入 Redis。此时 MySQL 清单尚未插入，
            //    按 docId 查询得到空结果 → deleteVectorsByDocId 会走「SCAN 前缀兜底」分支，
            //    把已写入的孤儿分片清理干净。若不清理，这些分片会永久残留：
            //    检索能召回、清单里查不到，即俗称的「幽灵分片」。
            //
            // ② 清理已落盘的物理文件。
            //
            // 顺序与幂等性：回滚向量可能自身抛异常（如 Redis 不可用），
            // 故用 try/catch 包住并只告警，确保「②清理文件」一定被执行，
            // 且不掩盖原始的入库失败异常 e（最终仍抛 e）。
            try {
                deleteVectorsByDocId(docId);
            } catch (Exception rollbackEx) {
                log.error("回滚已写入的向量失败，可能残留孤儿分片 (docId={})，"
                        + "请用 SCAN agent:kb:<docId>:* 手工核查", docId, rollbackEx);
            }
            log.error("文档入库失败：{}（已回滚向量并清理落盘文件）", originalName, e);
            deleteQuietly(saved);
            throw e;
        }
    }

    @Override
    public List<KbSearchResult> search(String query, Integer topK, Double threshold) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK != null ? topK : props.getDefaultTopK())
                .similarityThreshold(threshold != null ? threshold : props.getDefaultSimilarityThreshold())
                .build();

        return vectorStore.similaritySearch(request).stream()
                .map(d -> new KbSearchResult(
                        asString(d.getMetadata().get("docId")),
                        asString(d.getMetadata().get("fileName")),
                        asString(d.getMetadata().get("chunkIndex")),
                        d.getScore(),
                        d.getText()))
                .toList();
    }

    @Override
    public boolean deleteByDocId(String docId) {
        if (docId == null || docId.isBlank()) {
            return false;
        }
        // 1) 删除该文档的全部分片（按 metadata 过滤，一条调用搞定，无 topK 漏删风险）
        deleteVectorsByDocId(docId);

        // 2) 删除 MySQL 清单行（逻辑删除）并清理物理文件
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getDocId, docId));
        for (KnowledgeDocument doc : docs) {
            if (doc.getStoragePath() != null) {
                deleteQuietly(Paths.get(doc.getStoragePath()));
            }
            docMapper.deleteById(doc.getId());
        }
        log.info("已删除文档：docId={}，清理清单 {} 行", docId, docs.size());
        return true;
    }

    @Override
    public KbStats stats() {
        List<KnowledgeDocument> all = docMapper.selectList(null);
        long chunkTotal = all.stream()
                .mapToLong(d -> d.getChunkCount() == null ? 0 : d.getChunkCount())
                .sum();
        return new KbStats(all.size(), chunkTotal);
    }

    @Override
    public List<KnowledgeDocument> listDocuments() {
        return docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>().orderByDesc(KnowledgeDocument::getId));
    }

    // ==================== 内部方法 ====================

    /**
     * 按 docId 删除向量库中的全部分片。
     *
     * <p><b>实现说明</b>：不走 {@code vectorStore.delete(Filter.Expression)}——
     * 框架生成的 RediSearch 过滤表达式对 UUID 中的连字符不转义，会触发语法错误
     * （详见类注释）。改为<b>按分片 ID 直接删除</b>：
     * 入库时 {@code Document} 的 id 固定为 {@code docId + ":" + i}（i 为分片序号），
     * 故遍历 {@code 0..chunkCount-1} 枚举出全部分片 ID 传给
     * {@link RedisVectorStore#doDelete(List)}，该路径内部直接 {@code JSON.DEL}，不经过查询语法。
     *
     * <p><b>为何仍以「Redis 全库枚举」为兜底、而非只依赖 MySQL 的 chunkCount？</b>
     * 清单写入失败时（入库回滚场景）MySQL 中可能无该行，但向量可能已部分写入。
     * 故优先按清单记录的 chunkCount 枚举；若无清单行，则退化为「按 id 前缀枚举」兜底，
     * 确保任何情况下都能清理干净，不残留孤儿分片。
     */
    private void deleteVectorsByDocId(String docId) {
        RedisVectorStore redisStore = asRedisVectorStore();

        // 1) 优先：按 MySQL 清单记录的 chunkCount 精确枚举分片 ID（无遗留、无多余扫描）
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getDocId, docId));
        if (!docs.isEmpty()) {
            int chunkCount = docs.get(0).getChunkCount() == null ? 0 : docs.get(0).getChunkCount();
            if (chunkCount > 0) {
                List<String> ids = new ArrayList<>(chunkCount);
                for (int i = 0; i < chunkCount; i++) {
                    ids.add(docId + ":" + i);
                }
                redisStore.doDelete(ids);
                log.debug("已按分片 ID 删除向量：docId={}, 分片数={}", docId, chunkCount);
                return;
            }
        }

        // 2) 兜底：清单缺失或 chunkCount 为 0（如入库中断/回滚场景），
        //    按 Redis key 前缀实际枚举已写入的分片，避免残留孤儿向量。
        List<String> orphanIds = scanChunkIds(redisStore, docId);
        if (!orphanIds.isEmpty()) {
            redisStore.doDelete(orphanIds);
            log.warn("清单缺失，已按 key 前缀清理孤儿向量：docId={}, 数量={}", docId, orphanIds.size());
        }
    }

    /**
     * 兜底：用 SCAN（而非 KEYS，避免阻塞 Redis）扫描 {@code <redisKeyPrefix><docId>:*}，
     * 还原出分片 ID（即去掉 key 前缀后的部分，形如 {@code <docId>:<i>}）。
     */
    private List<String> scanChunkIds(RedisVectorStore redisStore, String docId) {
        List<String> ids = new ArrayList<>();
        String prefix = props.getRedisKeyPrefix();
        String match = prefix + docId + ":*";
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(match).count(200);
        do {
            ScanResult<String> result = redisStore.getJedis().scan(cursor, params);
            for (String key : result.getResult()) {
                if (key.startsWith(prefix)) {
                    // 还原为 Document id（RedisVectorStore 内部再自行拼接前缀）
                    ids.add(key.substring(prefix.length()));
                }
            }
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return ids;
    }

    /** 取回底层 {@link RedisVectorStore}（本项目的 VectorStore Bean 即该类型）。 */
    private RedisVectorStore asRedisVectorStore() {
        if (vectorStore instanceof RedisVectorStore rvs) {
            return rvs;
        }
        throw new IllegalStateException(
                "当前 VectorStore 实现不是 RedisVectorStore，无法按分片 ID 删除：" + vectorStore.getClass());
    }

    /** 分批写入，避免单次请求过大或触发 embedding 限流。 */
    private void writeInBatches(List<Document> documents) {
        int batchSize = Math.max(1, props.getBatchSize());
        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);
            vectorStore.add(batch);
            log.debug("向量写入进度：{}/{}", end, documents.size());
        }
    }

    /** 落盘：文件名前缀 docId 以避免同名冲突，并做基本的安全化处理。 */
    private Path saveToDisk(MultipartFile file, String docId, String originalName) {
        try {
            Path dir = Paths.get(props.getStorageDir());
            Files.createDirectories(dir);
            String safeName = originalName.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
            Path target = dir.resolve(docId + "_" + safeName);
            file.transferTo(target.toAbsolutePath().toFile());
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("文件落盘失败：" + originalName, e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("清理物理文件失败：{}", path, e);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
