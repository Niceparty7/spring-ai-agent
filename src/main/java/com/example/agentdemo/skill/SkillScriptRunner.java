package com.example.agentdemo.skill;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 脚本执行器：白名单 + 超时 + 截断 + 限流（<b>skill 脚本能力的唯一执行入口</b>）。
 *
 * <p>安全设计（四条硬约束，缺一不可）：
 * <ol>
 *   <li><b>入参校验</b>：skillName / scriptName 必须匹配 {@code ^[a-zA-Z0-9_-]{1,64}$}，
 *       拒绝 {@code ..}、路径分隔符、空串；</li>
 *   <li><b>路径白名单 + 穿越防护</b>：目标文件必须落在
 *       {@code <rootDir>/<skillName>/scripts/} 内，且为常规文件；</li>
 *   <li><b>解释器白名单 + 防注入</b>：按扩展名路由解释器，参数经
 *       {@code ProcessBuilder(List<String>)} 数组传递，<b>绝不拼接 shell 字符串</b>；</li>
 *   <li><b>超时 + 输出截断 + 并发限流</b>：防失控脚本拖垮进程。</li>
 * </ol>
 */
@Slf4j
public class SkillScriptRunner {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final SkillProperties properties;

    private final Semaphore concurrency;

    public SkillScriptRunner(SkillProperties properties) {
        this.properties = properties;
        this.concurrency = new Semaphore(Math.max(1, properties.getScriptMaxConcurrent()));
    }

    /**
     * 执行脚本。
     *
     * @return 脚本输出（stdout+stderr，超长截断）；校验失败返回以 [拒绝] 开头的错误文本（不抛异常）
     */
    public String run(String skillName, String scriptName, List<String> args) {
        // 1. 入参校验
        if (!NAME_PATTERN.matcher(skillName).matches()) {
            return "[拒绝] 非法技能名: " + skillName;
        }
        if (!NAME_PATTERN.matcher(scriptName).matches()) {
            return "[拒绝] 非法脚本名: " + scriptName;
        }
        if (args.size() > properties.getScriptMaxArgs()) {
            return "[拒绝] 参数个数超过上限(" + properties.getScriptMaxArgs() + "): " + args.size();
        }

        // 2. 路径白名单 + 穿越防护
        Path scriptsDir = Paths.get(properties.getRootDir(), skillName, "scripts")
                .toAbsolutePath().normalize();
        Path target = scriptsDir.resolve(scriptName).normalize();
        if (!target.startsWith(scriptsDir) || !Files.isRegularFile(target)) {
            return "[拒绝] 脚本不存在或越出白名单目录: " + skillName + "/" + scriptName;
        }

        // 3. 解释器白名单
        String ext = extensionOf(scriptName);
        List<String> command = buildCommand(ext, target, args);
        if (command == null) {
            return "[拒绝] 不支持的脚本扩展名: " + scriptName;
        }

        // 4. 并发限流 + 执行
        if (!concurrency.tryAcquire()) {
            return "[拒绝] 脚本并发已达上限(" + properties.getScriptMaxConcurrent() + ")，请稍后再试";
        }
        try {
            return execute(command);
        } finally {
            concurrency.release();
        }
    }

    /** 构造解释器 + 脚本 + 参数的命令数组；扩展名不受支持时返回 null。 */
    private List<String> buildCommand(String ext, Path script, List<String> args) {
        String interpreter = resolveInterpreter(ext);
        if (interpreter == null) {
            return null;
        }
        List<String> cmd = new ArrayList<>();
        // Windows 批处理需经 cmd /c 执行；其余直接以解释器作为首参数
        if (ext.equals(".bat") || ext.equals(".cmd")) {
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add(script.toString());
        } else {
            cmd.add(interpreter);
            cmd.add(script.toString());
        }
        cmd.addAll(args);
        return cmd;
    }

    /** 扩展名 -> 解释器。优先用配置覆盖，否则用内置默认。 */
    private String resolveInterpreter(String ext) {
        Map<String, String> configured = properties.getScriptInterpreters();
        if (configured != null && configured.containsKey(ext)) {
            return configured.get(ext);
        }
        Map<String, String> defaults = new HashMap<>();
        defaults.put(".py", "python");
        defaults.put(".sh", "bash");
        defaults.put(".bat", "cmd");
        defaults.put(".cmd", "cmd");
        return defaults.get(ext);
    }

    private String execute(List<String> command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(properties.getScriptTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                return "[超时] 脚本执行超过 " + properties.getScriptTimeout().toSeconds() + "s 被强制终止";
            }
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();
            if (output.length() > properties.getScriptMaxOutputChars()) {
                output = output.substring(0, properties.getScriptMaxOutputChars()) + "\n…[输出已截断]";
            }
            return exitCode == 0
                    ? (output.isBlank() ? "(脚本执行成功，无输出)" : output)
                    : "[脚本退出码 " + exitCode + "]\n" + output;
        } catch (IOException e) {
            return "[脚本启动失败] " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[脚本执行被中断]";
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
