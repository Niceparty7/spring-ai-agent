package com.example.agentdemo.dto;

/**
 * 知识库上传结果。
 *
 * @param docId      生成的业务文档 ID（UUID）
 * @param fileName   原始文件名
 * @param chunkCount 切分入库的分片数量
 */
public record KbUploadResult(String docId, String fileName, int chunkCount) {
}
