package com.example.agentdemo.skill;

import java.util.List;

/**
 * 一个已解析完成的 skill 定义（不可变）。
 *
 * <p>由 {@link SkillParser} 从 {@code SKILL.md} 解析而来，字段对应 YAML frontmatter
 * 与 markdown 正文。全字段 final，天然线程安全，可被多个会话并发引用。
 *
 * @param name         技能名（唯一标识，也即目录名）
 * @param version      版本号（字符串，仅展示用）
 * @param description  一句话描述，用于 {@code listSkills} 与检索匹配
 * @param keywords     检索关键词
 * @param enabled      是否启用
 * @param dependsOn    依赖的其他 skill 名（加载时先加载依赖）
 * @param tools        引用的 Java {@code @Tool} 工具名（须在 SkillToolRegistry 中登记）
 * @param scriptTools  脚本工具声明（经 runSkillScript 执行）
 * @param instructions 指令正文（注入给模型的 prompt）
 */
public record SkillDefinition(
        String name,
        String version,
        String description,
        List<String> keywords,
        boolean enabled,
        List<String> dependsOn,
        List<String> tools,
        List<SkillScriptTool> scriptTools,
        String instructions) {
}
