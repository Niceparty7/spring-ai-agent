package com.example.agentdemo.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * skill 依赖解析：DFS 拓扑序 + 循环依赖检测 + 缺失依赖降级。
 *
 * <p>加载顺序：先加载依赖（拓扑序靠前者先），再加载自身。
 * <ul>
 *   <li><b>循环依赖</b>：三色标记（visiting/visited）检测，成环抛 {@link CycleException}，不静默；</li>
 *   <li><b>缺失依赖</b>：降级——WARN 后跳过缺失项继续加载自身（与项目「降级不炸链」范式一致）。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class SkillDependencyResolver {

    /**
     * 解析加载顺序。
     *
     * @return 按加载顺序排列的技能名（含自身，依赖在前）；自身不存在时返回空列表
     * @throws CycleException 依赖成环
     */
    public List<String> resolve(String name, SkillRegistry registry) {
        if (!registry.contains(name)) {
            return List.of();
        }
        List<String> order = new ArrayList<>();
        List<String> visiting = new ArrayList<>();
        List<String> visited = new ArrayList<>();
        dfs(name, registry, order, visiting, visited);
        return order;
    }

    private void dfs(String name, SkillRegistry registry,
                     List<String> order, List<String> visiting, List<String> visited) {
        if (visited.contains(name)) {
            return;
        }
        if (visiting.contains(name)) {
            int i = visiting.indexOf(name);
            String cycle = String.join("->", visiting.subList(i, visiting.size())) + "->" + name;
            throw new CycleException("技能依赖成环: " + cycle);
        }
        visiting.add(name);
        registry.findByName(name).ifPresent(def -> {
            for (String dep : def.dependsOn()) {
                if (dep == null || dep.isBlank()) {
                    continue;
                }
                if (registry.contains(dep)) {
                    dfs(dep, registry, order, visiting, visited);
                } else {
                    // 缺失依赖：降级跳过（不炸链），仅告警
                    log.warn("技能 [{}] 依赖的 [{}] 不存在，已跳过该依赖", name, dep);
                }
            }
        });
        visiting.remove(name);
        visited.add(name);
        order.add(name);
    }

    /** 依赖成环异常（调用方捕获后把明确错误回给模型）。 */
    public static class CycleException extends RuntimeException {
        public CycleException(String message) {
            super(message);
        }
    }
}
