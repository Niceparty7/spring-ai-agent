package com.example.agentdemo.dto;

/**
 * 对话请求体：{"message": "...", "conversationId": "可选"}
 *
 * <p>conversationId 可空：老前端只传 message 时不会 500，
 * 由 Controller 兜底成默认会话 ID（"default"）。
 */
public record ChatRequest(String message, String conversationId) {
}
