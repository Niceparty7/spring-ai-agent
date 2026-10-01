package com.example.agentdemo.skill;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * skill 内存注册表：索引 + 检索。
 *
 * <p>热重载采用「整体替换」：{@link #replaceAll(List)} 原子换掉整个索引。
 * 因 {@link SkillDefinition} 是不可变 record，会话里存的是技能名（String），
 * 激活态经 {@code findByName} 始终解析到最新版本——无需为已激活会话保留旧实例。
 */
@Slf4j
public class SkillRegistry {

    private volatile Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    /** 全量替换（启动扫描 / 热重载共用）。 */
    public void replaceAll(List<SkillDefinition> defs) {
        Map<String, SkillDefinition> next = new ConcurrentHashMap<>();
        for (SkillDefinition def : defs) {
            next.put(def.name(), def);
        }
        this.skills = next;
        log.info("skill 注册表已更新：共 {} 个 skill -> {}", defs.size(),
                defs.stream().map(SkillDefinition::name).sorted().toList());
    }

    public Optional<SkillDefinition> findByName(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public boolean contains(String name) {
        return skills.containsKey(name);
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    /** 全部 skill（按名称排序）。 */
    public List<SkillDefinition> list() {
        return skills.values().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    /**
     * 关键词检索：对 name / description / keywords 做大小写不敏感的子串匹配。
     * 空关键词返回全部。
     */
    public List<SkillDefinition> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return list();
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        List<SkillDefinition> result = new ArrayList<>();
        for (SkillDefinition def : skills.values()) {
            if (def.name().toLowerCase(Locale.ROOT).contains(kw)
                    || def.description().toLowerCase(Locale.ROOT).contains(kw)
                    || def.keywords().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(kw))) {
                result.add(def);
            }
        }
        result.sort((a, b) -> a.name().compareTo(b.name()));
        return result;
    }
}
