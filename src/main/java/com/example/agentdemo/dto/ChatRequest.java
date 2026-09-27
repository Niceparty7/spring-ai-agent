package com.example.agentdemo.dto;

/**
 * 对话请求体：{"message": "帮我看看现在有哪些商品"}
 */
public record ChatRequest(String message) {
}
