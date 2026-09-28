package com.example.agentdemo.controller;

import com.example.agentdemo.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** 注入共享记忆，供「新对话」主动释放该会话的历史。 */
    private final ChatMemory chatMemory;

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody ChatRequest request) {
        // 向后兼容 + 轻量防护：null/空白 → 默认会话；超长 → 截断
        String conversationId = normalizeConversationId(request.conversationId());

        log.info("Agent 收到问题: {} (conversationId={})", request.message(), conversationId);

        // 工具调用的完整链路（模型返回 tool_call -> 框架执行 Java 方法 -> 结果回传 -> 模型生成最终答复）
        // 全部由 ChatClient 内部完成，业务侧只需一次 call()。
        //
        // .advisors(...) 与 .user(...) 的先后顺序无关紧要：二者都只是往请求规格上累加配置，
        // 真正生效在 .call() 时由 Advisor 链统一执行。放在 .user 之后仅为可读性。
        // 关键：通过 CONVERSATION_ID 参数把"当前会话"告知记忆顾问，单例 ChatClient 借此服务多会话。
        String reply = chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        log.info("Agent 回复: {}", reply);
        // 模型偶尔可能返回空 content（例如仅完成工具调用而没生成文本），此处兜底避免 NPE
        return Map.of("reply", reply == null ? "（模型未返回内容）" : reply);
    }

    /**
     * 「新对话」：前端重置会话 ID 后调用，主动删除后端该会话的历史。
     *
     * <p>内存版记忆下，前端换新 ID 并不会释放旧 ID 占用的内存，因此必须提供此接口清理。
     * 入参缺失时不抛错（前端可能已丢弃旧 ID），按默认会话清理。
     */
    @DeleteMapping("/conversation")
    public Map<String, String> resetConversation(@RequestParam(required = false) String conversationId) {
        String cid = normalizeConversationId(conversationId);
        chatMemory.clear(cid);
        log.info("已清理会话历史: {}", cid);
        return Map.of("status", "cleared", "conversationId", cid);
    }

    /**
     * 归一化 conversationId：
     * <ul>
     *   <li>null / 空白 → {@link ChatMemory#DEFAULT_CONVERSATION_ID}（"default"），保证老前端不 500；</li>
     *   <li>超长 → 截断到 128 字符，降低恶意/超长 ID 占用内存的风险（内存版无 VARCHAR 长度约束）。</li>
     * </ul>
     */
    private String normalizeConversationId(String raw) {
        if (raw == null) {
            return ChatMemory.DEFAULT_CONVERSATION_ID;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return ChatMemory.DEFAULT_CONVERSATION_ID;
        }
        return trimmed.length() > 128 ? trimmed.substring(0, 128) : trimmed;
    }
}
