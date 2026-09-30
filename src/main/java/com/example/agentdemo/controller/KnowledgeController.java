package com.example.agentdemo.controller;

import com.example.agentdemo.dto.KbSearchResult;
import com.example.agentdemo.dto.KbStats;
import com.example.agentdemo.dto.KbUploadResult;
import com.example.agentdemo.entity.KnowledgeDocument;
import com.example.agentdemo.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理入口：上传、列表、删除、检索测试、统计、探活。
 */
@Slf4j
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final VectorStore vectorStore;

    /** 上传文档（multipart/form-data，字段名 file），支持 txt / md / pdf。 */
    @PostMapping("/documents")
    public KbUploadResult upload(@RequestParam("file") MultipartFile file) {
        log.info("知识库上传：name={}, size={}", file.getOriginalFilename(), file.getSize());
        return knowledgeBaseService.ingest(file);
    }

    /** 文档清单（读 MySQL）。 */
    @GetMapping("/documents")
    public List<KnowledgeDocument> list() {
        return knowledgeBaseService.listDocuments();
    }

    /** 按 docId 删除文档及其全部分片。 */
    @DeleteMapping("/documents/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) {
        boolean ok = knowledgeBaseService.deleteByDocId(docId);
        return Map.of("docId", docId, "deleted", ok);
    }

    /** 检索测试：GET /api/kb/search?query=xxx&topK=4&threshold=0.5 */
    @GetMapping("/search")
    public List<KbSearchResult> search(@RequestParam String query,
                                       @RequestParam(required = false) Integer topK,
                                       @RequestParam(required = false) Double threshold) {
        return knowledgeBaseService.search(query, topK, threshold);
    }

    /** 统计信息。 */
    @GetMapping("/stats")
    public KbStats stats() {
        return knowledgeBaseService.stats();
    }

    /**
     * 向量库探活：触发一次轻量查询以确认 Redis Stack 与索引可用。
     * 不依赖模型生成，仅验证 embedding + RediSearch 链路。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean ok;
        try {
            vectorStore.similaritySearch(SearchRequest.builder().query("health-check").topK(1).build());
            ok = true;
        } catch (Exception e) {
            log.warn("向量库探活失败", e);
            ok = false;
        }
        return Map.of("vectorStore", ok ? "up" : "down");
    }
}
