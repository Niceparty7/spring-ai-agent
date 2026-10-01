package com.example.agentdemo.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java {@code @Tool} 工具名索引（skill 引用工具时的合法性校验 + 命名查询）。
 *
 * <p>启动时把基础工具（ProductTools / KnowledgeTools）经 {@code MethodToolCallbackProvider}
 * 生成 {@link ToolCallback} 列表后按名索引。skill 的 frontmatter {@code tools: [name]}
 * 引用这些已登记工具名——因为这些工具<b>本就在 ChatClient 基础工具集里</b>，
 * 模型在 loadSkill 后即可直接调用，无需动态注入。
 *
 * <p>若未来出现「skill 私有工具」（不在基础集、需激活后才暴露），可基于本索引
 * 扩展出按会话动态注入（显式多轮循环），属 P2 范围，当前不做。
 */
@Slf4j
public class SkillToolRegistry {

    private final Map<String, ToolCallback> callbacks = new ConcurrentHashMap<>();

    /** 用现成的 ToolCallback 列表建立索引（重名后写覆盖前写，以最后一次为准）。 */
    public void index(ToolCallback... toolCallbacks) {
        for (ToolCallback cb : toolCallbacks) {
            callbacks.put(cb.getToolDefinition().name(), cb);
        }
    }

    public boolean contains(String name) {
        return callbacks.containsKey(name);
    }

    public List<String> names() {
        return callbacks.keySet().stream().sorted().toList();
    }

    public ToolCallback resolve(String name) {
        return callbacks.get(name);
    }
}
