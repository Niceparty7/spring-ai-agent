package com.example.agentdemo.skill;

import lombok.RequiredArgsConstructor;

import java.util.Set;

/**
 * 系统提示词装配：基础提示词 + 当前会话已激活 skill 的指令。
 *
 * <p>替代 {@code ChatClientConfig} 里硬编码的 {@code defaultSystem} 文本块，
 * 使「基础系统提示词」与「skill 指令」解耦，二者在 advisor 阶段按会话动态拼装。
 */
@RequiredArgsConstructor
public class SkillInstructionsAssembler {

    private final SkillProperties properties;
    private final SkillRegistry registry;
    private final SkillSessionState sessionState;

    private static final String DEFAULT_BASE_SYSTEM_PROMPT = """
            你是「商品管理系统」的智能助手。
            你可以调用工具完成商品的查询、新增、改价、删除；
            也可以基于「知识库」回答文档相关的问题。

            规则：
            1. 只要用户意图涉及商品操作，就必须调用相应工具，不要凭空编造数据；
            2. 涉及改价或删除时，如果缺少商品 ID，先向用户询问 ID，不要猜测；
            3. 回答知识库问题时，优先依据上下文中已提供的资料；资料不足时
               调用 searchKnowledgeBase 工具进一步检索；
            4. 知识库中查不到的内容，要如实说明「知识库中未找到」，不要编造；
            5. 工具返回的内容要如实转述，并用简洁的中文总结关键结果。

            关于技能(skill)：当用户需要某类专门能力时，可先调用 listSkills 查看
            可用技能，再调用 loadSkill 按需加载；加载后技能会给出详细指令，按其指引执行。
            """;

    /** 基础系统提示词（配置覆盖优先）。 */
    public String baseSystemPrompt() {
        String configured = properties.getBaseSystemPrompt();
        return (configured == null || configured.isBlank()) ? DEFAULT_BASE_SYSTEM_PROMPT : configured;
    }

    /**
     * 当前会话已激活 skill 的指令（拼装成一个可注入 system 的文本块）。
     * 无激活 skill 时返回空串。
     */
    public String activeInstructions(String conversationId) {
        Set<String> names = sessionState.activeNames(conversationId);
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【当前已激活的技能指令】\n");
        for (String name : names) {
            registry.findByName(name).ifPresent(def -> {
                sb.append("\n## 技能：").append(def.name())
                        .append("（").append(def.description()).append("）\n")
                        .append(def.instructions()).append("\n");
            });
        }
        return sb.toString();
    }
}
