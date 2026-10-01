package com.example.agentdemo.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * skill 控制工具（常驻，模型随时可用）。
 *
 * <p>三个工具分工：
 * <ul>
 *   <li>{@code listSkills}：发现——列出可用技能；</li>
 *   <li>{@code loadSkill}：按需激活——把技能指令返回给模型并记录到当前会话激活态；</li>
 *   <li>{@code runSkillScript}：执行技能声明的脚本工具（白名单）。</li>
 * </ul>
 *
 * <p>遵循项目约定：方法统一返回 {@link String}，description 写清触发场景。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillTools {

    private final SkillRegistry registry;
    private final SkillSessionState sessionState;
    private final SkillScriptRunner scriptRunner;
    private final SkillDependencyResolver dependencyResolver;

    @Tool(description = "列出当前可用的技能(skill)及其用途。当用户询问「有哪些能力/技能」或需要专门能力时调用。")
    public String listSkills() {
        List<SkillDefinition> skills = registry.list();
        if (skills.isEmpty()) {
            return "当前没有可用技能。";
        }
        return skills.stream()
                .map(s -> "- " + s.name() + "：" + s.description())
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = """
            按需加载并激活一个技能，返回该技能的完整指令。当用户需要某类专门能力、
            或意图对应某个可用技能时调用；调用后请严格按返回的指令执行。
            """)
    public String loadSkill(@ToolParam(description = "要加载的技能名称") String skillName) {
        String conversationId = SkillContextHolder.get();
        String name = skillName == null ? "" : skillName.trim();
        if (name.isEmpty()) {
            return "请指定要加载的技能名称（可用 listSkills 查看）。";
        }

        SkillDefinition def = registry.findByName(name).orElse(null);
        if (def == null) {
            return "未找到名为「" + name + "」的技能。可用 listSkills 查看当前可用技能。";
        }

        // 依赖处理：拓扑序先加载依赖，再加载自身
        List<String> order;
        try {
            order = dependencyResolver.resolve(name, registry);
        } catch (SkillDependencyResolver.CycleException e) {
            return "[加载失败] " + e.getMessage();
        }
        for (String n : order) {
            sessionState.activate(conversationId, n);
        }
        log.info("会话 {} 激活技能: {} (含依赖 {})", conversationId, order, def.dependsOn());

        return buildLoadResult(def, order);
    }

    @Tool(description = """
            执行某个已加载技能声明的脚本工具。仅当已通过 loadSkill 加载该技能、
            且其指令要求运行某脚本时调用；skillName 与 scriptName 需与技能声明一致。
            """)
    public String runSkillScript(@ToolParam(description = "技能名称") String skillName,
                                 @ToolParam(description = "脚本文件名") String scriptName,
                                 @ToolParam(description = "脚本参数，空格分隔，可为空", required = false) String args) {
        List<String> argList = args == null || args.isBlank()
                ? List.of()
                : Arrays.stream(args.trim().split("\\s+")).filter(s -> !s.isEmpty()).toList();
        return scriptRunner.run(skillName == null ? "" : skillName.trim(),
                scriptName == null ? "" : scriptName.trim(), argList);
    }

    /** 组装 loadSkill 的返回文本：指令正文 + 可用工具/脚本清单。 */
    private String buildLoadResult(SkillDefinition def, List<String> order) {
        StringBuilder sb = new StringBuilder();
        sb.append("已加载技能「").append(def.name()).append("」(").append(def.version()).append(")：")
                .append(def.description()).append("\n\n");
        sb.append(def.instructions()).append("\n");

        if (!def.tools().isEmpty()) {
            List<String> valid = def.tools().stream().filter(registry::contains).toList();
            List<String> invalid = def.tools().stream().filter(t -> !registry.contains(t)).toList();
            if (!valid.isEmpty()) {
                sb.append("\n本技能可用的工具：").append(String.join("、", valid)).append("。\n");
            }
            if (!invalid.isEmpty()) {
                sb.append("注意：以下声明的工具未登记，暂不可用：").append(String.join("、", invalid)).append("。\n");
            }
        }
        if (!def.scriptTools().isEmpty()) {
            sb.append("\n本技能可运行的脚本工具：\n");
            for (SkillScriptTool st : def.scriptTools()) {
                sb.append("- ").append(st.name()).append("（文件 ").append(st.file())
                        .append("）：").append(st.description()).append("\n");
            }
            sb.append("用 runSkillScript(skillName=\"").append(def.name())
                    .append("\", scriptName=文件名, args=参数) 执行。\n");
        }
        return sb.toString();
    }
}
