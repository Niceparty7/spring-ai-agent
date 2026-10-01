package com.example.agentdemo.skill;

/**
 * 脚本工具声明（SKILL.md frontmatter 中 {@code scriptTools} 列表的一项）。
 *
 * <p>脚本工具不是「代码」，而是「数据」：一个 skill 声明某脚本文件能做什么，
 * 模型通过常驻工具 {@code runSkillScript(skillName, scriptName, args...)} 触发执行，
 * 由 {@link SkillScriptRunner} 做白名单校验 + 超时 + 截断。因此无需动态注册工具。
 *
 * @param name        脚本工具名（模型调用时用于定位）
 * @param description 描述（告知模型何时调用）
 * @param file        脚本文件名，必须位于该 skill 的 scripts 目录内
 * @param maxArgs     最大参数个数（0 表示无参脚本）
 */
public record SkillScriptTool(String name, String description, String file, int maxArgs) {
}
