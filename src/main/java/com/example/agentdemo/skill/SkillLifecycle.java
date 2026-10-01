package com.example.agentdemo.skill;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * skill 生命周期：启动扫描 + 热重载。
 *
 * <p>热重载选「定时轮询」而非 {@code WatchService}：Windows 下 WatchService 对
 * 本地/映射盘/IDE 写盘行为不可靠（本项目已在 MCP 里踩过 Windows 进程坑），
 * skill 变更低频，轮询足够且无状态、易测试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillLifecycle {

    private final SkillProperties properties;
    private final SkillScanner scanner;
    private final SkillRegistry registry;

    /** skill 目录指纹（变化才重扫），用于热重载去抖。 */
    private volatile String lastFingerprint = "";

    @PostConstruct
    public void startupScan() {
        if (!properties.isEnabled()) {
            log.info("skill 系统已禁用（app.skill.enabled=false），跳过扫描");
            return;
        }
        if (!properties.isScanOnStartup()) {
            log.info("app.skill.scan-on-startup=false，跳过启动扫描（等待热重载）");
            return;
        }
        rescan();
    }

    @Scheduled(fixedDelayString = "${app.skill.hot-reload-interval:10s}")
    public void hotReload() {
        if (!properties.isEnabled() || !properties.isHotReloadEnabled()) {
            return;
        }
        String fingerprint = fingerprint(rootDir());
        if (!fingerprint.equals(lastFingerprint)) {
            log.info("检测到 skill 目录变化，触发重扫");
            rescan();
        }
    }

    private void rescan() {
        Path root = rootDir();
        List<SkillDefinition> defs = scanner.scan(root);
        registry.replaceAll(defs);
        lastFingerprint = fingerprint(root);
    }

    private Path rootDir() {
        return Paths.get(properties.getRootDir()).toAbsolutePath().normalize();
    }

    /** 目录指纹：各 SKILL.md 的路径 + lastModified + size 拼接，变化即触发重扫。 */
    private String fingerprint(Path root) {
        try {
            if (!Files.isDirectory(root)) {
                return "no-dir";
            }
            StringBuilder sb = new StringBuilder();
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                        .sorted()
                        .forEach(p -> sb.append(p).append(':')
                                .append(p.toFile().lastModified()).append(':')
                                .append(p.toFile().length()).append(';'));
            }
            return sb.toString();
        } catch (Exception e) {
            return "fingerprint-error";
        }
    }
}
