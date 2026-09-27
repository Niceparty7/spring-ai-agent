package com.example.agentdemo.controller;

import com.example.agentdemo.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 入口：用户说一句自然语言，模型自行决定调用哪个 ProductTools 工具。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final ChatClient chatClient;

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody ChatRequest request) {
        log.info("Agent 收到问题: {}", request.message());

        // 工具调用的完整链路（模型返回 tool_call -> 框架执行 Java 方法 -> 结果回传 -> 模型生成最终答复）
        // 全部由 ChatClient 内部完成，业务侧只需一次 call()。
        String reply = chatClient.prompt()
                .user(request.message())
                .call()
                .content();

        log.info("Agent 回复: {}", reply);
        // 模型偶尔可能返回空 content（例如仅完成工具调用而没生成文本），此处兜底避免 NPE
        return Map.of("reply", reply == null ? "（模型未返回内容）" : reply);
    }
}
