package com.example.agentdemo.skill;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析 {@code SKILL.md} → {@link SkillDefinition}。
 *
 * <p>文件结构：首行以 {@code ---} 开始的 YAML frontmatter（元数据），随后到下一个
 * {@code ---} 为止；剩余部分为 markdown 指令正文。
 *
 * <p><b>安全解析</b>：使用 {@code new Yaml(new SafeConstructor(new LoaderOptions()))}，
 * 只允许标量/列表/映射，禁止任意对象反序列化（杜绝 YAML 反序列化漏洞）。
 * frontmatter 里只认白名单字段，未知字段忽略——保证未来扩展不破坏兼容。
 */
@Slf4j
public class SkillParser {

    /** 目录名/技能名合法性：仅字母数字、下划线、连字符，长度 1~64。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /**
     * 解析单个 SKILL.md 文件。
     *
     * @throws SkillParseException 解析失败（调用方捕获后降级跳过，不炸启动）
     */
    public SkillDefinition parse(Path file) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            throw new SkillParseException("读取 SKILL.md 失败: " + file, e);
        }

        // 切分 frontmatter 与正文
        String[] parts = splitFrontmatter(raw, file);
        Map<String, Object> meta = parseFrontmatter(parts[0], file);
        String body = parts[1] == null ? "" : parts[1].trim();

        String name = str(meta.get("name"));
        if (name == null || name.isBlank()) {
            throw new SkillParseException("SKILL.md 缺少必填字段 name: " + file);
        }
        if (!NAME_PATTERN.matcher(name.trim()).matches()) {
            throw new SkillParseException("非法技能名（仅允许 a-zA-Z0-9_-，1~64 字符）: " + name);
        }

        List<String> keywords = strList(meta.get("keywords"));
        List<String> dependsOn = strList(meta.get("dependsOn"));
        List<String> tools = strList(meta.get("tools"));
        List<SkillScriptTool> scriptTools = parseScriptTools(meta.get("scriptTools"), file);

        return new SkillDefinition(
                name.trim(),
                str(meta.get("version")) == null ? "1.0.0" : str(meta.get("version")),
                str(meta.get("description")) == null ? "" : str(meta.get("description")),
                keywords,
                !Boolean.FALSE.equals(meta.get("enabled")),
                dependsOn,
                tools,
                scriptTools,
                body);
    }

    /** 返回 [frontmatter, body]；无 frontmatter 时 [null, 原文]。 */
    private String[] splitFrontmatter(String raw, Path file) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---")) {
            return new String[] { null, text };
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            throw new SkillParseException("SKILL.md frontmatter 未闭合（缺少第二个 ---）: " + file);
        }
        String fm = text.substring(3, end).trim();
        String body = text.substring(end + 4);
        return new String[] { fm, body };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String frontmatter, Path file) {
        if (frontmatter == null || frontmatter.isBlank()) {
            return Map.of();
        }
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(frontmatter);
            if (loaded == null) {
                return Map.of();
            }
            if (!(loaded instanceof Map)) {
                throw new SkillParseException("frontmatter 不是 YAML 映射: " + file);
            }
            return (Map<String, Object>) loaded;
        } catch (SkillParseException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillParseException("frontmatter YAML 解析失败: " + file + " -> " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<SkillScriptTool> parseScriptTools(Object raw, Path file) {
        List<SkillScriptTool> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (!(raw instanceof List)) {
            throw new SkillParseException("scriptTools 必须是列表: " + file);
        }
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                throw new SkillParseException("scriptTools 的每一项必须是映射: " + file);
            }
            Map<String, Object> m = (Map<String, Object>) item;
            String n = str(m.get("name"));
            String f = str(m.get("file"));
            if (n == null || n.isBlank() || f == null || f.isBlank()) {
                throw new SkillParseException("scriptTools 项缺少 name/file: " + file);
            }
            int maxArgs = m.get("maxArgs") instanceof Number num ? num.intValue() : 0;
            result.add(new SkillScriptTool(n.trim(), str(m.get("description")) == null ? "" : str(m.get("description")), f.trim(), maxArgs));
        }
        return result;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> list) {
            List<String> r = new ArrayList<>();
            for (Object x : list) {
                r.add(String.valueOf(x));
            }
            return r;
        }
        return List.of(String.valueOf(o));
    }

    /** skill 解析失败专用异常（区别于 IO 异常，便于调用方按需降级）。 */
    public static class SkillParseException extends RuntimeException {
        public SkillParseException(String message) {
            super(message);
        }

        public SkillParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
