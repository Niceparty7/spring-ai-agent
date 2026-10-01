package com.example.agentdemo.skill;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Skill 系统自定义配置（前缀 {@code app.skill}）。
 *
 * <p>刻意使用 {@code app.*} 前缀，与项目既有的 {@code RagProperties}（{@code app.rag}）、
 * {@code ChatMemoryProperties}（{@code app.chat-memory}）保持一致，彻底避开
 * {@code spring.ai.*} 自动装配的属性绑定。全字段给默认值（配置里可覆盖）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.skill")
public class SkillProperties {

    /** skill 根目录（相对进程工作目录），其下每个子目录 = 一个 skill，内含 SKILL.md。 */
    private String rootDir = "./data/skills";

    /** skill 系统总开关。false 时扫描/激活/脚本全部禁用，Agent 退化为无 skill 行为。 */
    private boolean enabled = true;

    /** 是否在启动时立即扫描一次 skill 目录。 */
    private boolean scanOnStartup = true;

    /** 是否开启热重载（定时轮询 SKILL.md 的 lastModified，变化才重扫）。 */
    private boolean hotReloadEnabled = true;

    /** 热重载轮询间隔。 */
    private Duration hotReloadInterval = Duration.ofSeconds(10);

    /** 单脚本执行超时，超时强制杀进程。 */
    private Duration scriptTimeout = Duration.ofSeconds(30);

    /** 脚本输出（stdout+stderr）最大字符数，超出截断。 */
    private int scriptMaxOutputChars = 8000;

    /** 脚本参数个数上限，超出拒绝。 */
    private int scriptMaxArgs = 8;

    /** 脚本并发执行上限（简单信号量限流）。 */
    private int scriptMaxConcurrent = 2;

    /** 激活态 TTL：会话无交互超过该时长后，其激活的 skill 被惰性回收。 */
    private Duration sessionTtl = Duration.ofHours(1);

    /** 基础系统提示词。留空则用内置默认（商品管理 + 知识库 + 技能使用说明）。 */
    private String baseSystemPrompt = "";

    /** 脚本解释器映射：扩展名(含点) -> 解释器可执行名。默认内置 .py/.sh/.bat/.cmd，可覆盖/追加。 */
    private Map<String, String> scriptInterpreters = new HashMap<>();
}
