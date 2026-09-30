package com.example.agentdemo.dto;

/**
 * 知识库统计信息。
 *
 * @param documentCount 文档总数
 * @param chunkCount    分片总数
 */
public record KbStats(long documentCount, long chunkCount) {
}
