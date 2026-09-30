package com.example.agentdemo.dto;

/**
 * 知识库检索命中的单条分片。
 *
 * @param docId      所属文档 ID
 * @param fileName   所属文件名
 * @param chunkIndex 分片序号
 * @param score      相似度分数（0~1，越大越相似）
 * @param text       分片正文
 */
public record KbSearchResult(String docId, String fileName, String chunkIndex,
                             Double score, String text) {
}
