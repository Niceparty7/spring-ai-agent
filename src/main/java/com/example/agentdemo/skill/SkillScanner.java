package com.example.agentdemo.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 扫描 skill 目录，产出 {@link SkillDefinition} 列表。
 *
 * <p>约定布局：{@code <rootDir>/<skillName>/SKILL.md}。只认直接子目录下的 SKILL.md，
 * 不递归。单个 skill 解析失败（坏 SKILL.md）不抛异常，由调用方决定是否降级跳过。
 */
@Slf4j
@RequiredArgsConstructor
public class SkillScanner {

    private final SkillParser parser;

    /**
     * 扫描根目录下所有 skill。
     *
     * @return 解析成功的 skill 定义（按名称排序）；目录不存在或不可读时返回空列表
     */
    public List<SkillDefinition> scan(Path rootDir) {
        List<SkillDefinition> result = new ArrayList<>();
        if (rootDir == null || !Files.isDirectory(rootDir)) {
            return result;
        }

        try (Stream<Path> entries = Files.list(rootDir)) {
            List<Path> dirs = entries
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();

            for (Path dir : dirs) {
                Path skillFile = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                try {
                    SkillDefinition def = parser.parse(skillFile);
                    if (def.enabled()) {
                        result.add(def);
                    } else {
                        log.debug("跳过已禁用 skill: {}", def.name());
                    }
                } catch (SkillParser.SkillParseException e) {
                    // 解析失败静默降级：坏 SKILL.md 不应拖垮整个扫描/启动
                    log.warn("跳过解析失败的 skill（目录 {}）: {}", dir.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描 skill 目录失败 {}: {}", rootDir, e.getMessage());
        }
        return result;
    }
}
