package com.example.agentdemo.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * skill 指令注入顾问：把「当前会话已激活 skill 的指令」动态拼进系统提示词。
 *
 * <p>无状态单例，服务多会话——会话由请求上下文里的 {@code ChatMemory.CONVERSATION_ID}
 * 区分（与 {@code MessageChatMemoryAdvisor} 同款思路）。
 *
 * <p>{@code before()} 做两件事：
 * <ol>
 *   <li>把 conversationId 写入 {@link SkillContextHolder}，供 {@link SkillTools#loadSkill}
 *       在工具执行期读取（同一线程）；</li>
 *   <li>把已激活 skill 的指令经 {@code Prompt.augmentSystemMessage} 注入系统提示词。</li>
 * </ol>
 * {@code after()} 清理 ThreadLocal。
 *
 * <p>基于 Spring AI 1.1.2 新 advisor API（{@code BaseAdvisor.before/after}），
 * 旧的 {@code AdvisedRequest} 已在 1.1.2 移除。
 */
@Slf4j
@RequiredArgsConstructor
public class SkillInstructionAdvisor implements BaseAdvisor {

    private final SkillInstructionsAssembler assembler;

    @Override
    public String getName() {
        return "skill-instruction-advisor";
    }

    /**
     * 顺序：置于记忆顾问（HIGHEST_PRECEDENCE+1000）之后、RAG 顾问（0）之前。
     * 取一个中间正值即可——只需保证先于内部工具执行（before 阶段按 order 升序）。
     */
    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Object cid = request.context().get(ChatMemory.CONVERSATION_ID);
        String conversationId = cid == null ? ChatMemory.DEFAULT_CONVERSATION_ID : cid.toString();
        SkillContextHolder.set(conversationId);

        String instructions = assembler.activeInstructions(conversationId);
        if (instructions != null && !instructions.isBlank()) {
            Prompt augmented = request.prompt().augmentSystemMessage(instructions);
            return request.mutate().prompt(augmented).build();
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        SkillContextHolder.clear();
        return response;
    }
}
