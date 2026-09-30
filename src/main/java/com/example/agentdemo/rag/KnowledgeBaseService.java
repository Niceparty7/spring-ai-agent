package com.example.agentdemo.rag;

import com.example.agentdemo.dto.KbSearchResult;
import com.example.agentdemo.dto.KbStats;
import com.example.agentdemo.dto.KbUploadResult;
import com.example.agentdemo.entity.KnowledgeDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库服务：文档入库、向量检索、按文档删除、统计。
 */
public interface KnowledgeBaseService {

    /**
     * 上传文件并入库。
     * 链路：校验 → 落盘 → 解析 → 切分 → 附加 metadata → 分批写入向量库 → 写 MySQL 清单。
     */
    KbUploadResult ingest(MultipartFile file);

    /**
     * 向量检索。
     *
     * @param query     查询文本
     * @param topK      返回条数（null → 用配置默认值）
     * @param threshold 相似度阈值（null → 用配置默认值）
     */
    List<KbSearchResult> search(String query, Integer topK, Double threshold);

    /** 按 docId 删除该文档的全部分片、清单行与物理文件。 */
    boolean deleteByDocId(String docId);

    /** 统计文档数与分片数。 */
    KbStats stats();

    /** 列出全部文档清单。 */
    List<KnowledgeDocument> listDocuments();
}
