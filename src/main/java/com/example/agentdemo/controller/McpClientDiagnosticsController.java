package com.example.agentdemo.controller;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Client 侧诊断入口（只读，不依赖模型）。
 *
 * <p><b>为什么需要它</b>：验证「外部 MCP 工具是否真的接进来了」这件事，靠对话很难判断。
 * 如果工具压根没注册进 ChatClient，模型只会把它当普通问题回答 —— 不报错、也不调用工具，
 * 表现为「连上了、但工具从不触发」。这与 SSE/Streamable HTTP 协议不匹配是同一类症状：
 * <b>失败是静默的</b>。因此提供两个探针端点，把连接状态与外部工具清单直接暴露出来：
 * <ul>
 *   <li>{@code GET /api/mcp/client/status} —— 每个 MCP Server 连接是否已 initialized，
 *       以及服务端名称/版本/协议版本/instructions/能力；</li>
 *   <li>{@code GET /api/mcp/client/tools} —— 实时发起 {@code tools/list} JSON-RPC 请求，
 *       列出当前真正可用的外部工具（证明「子进程 → 握手 → listTools」全链路可用）。</li>
 * </ul>
 *
 * <p>本端点刻意只做只读探测，<b>不提供「直接调用远程工具」的接口</b>，避免暴露任意执行面。
 *
 * <p>两个端点都通过 {@link ObjectProvider} 可空注入：{@code MCP_CLIENT_ENABLED=false} 时
 * 容器里没有 {@code List<McpSyncClient>} Bean，端点会返回空结果而不是报错，
 * 可直接用于验证降级行为。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpClientDiagnosticsController {

    private final ObjectProvider<List<McpSyncClient>> mcpClientsProvider;

    public McpClientDiagnosticsController(ObjectProvider<List<McpSyncClient>> mcpClientsProvider) {
        this.mcpClientsProvider = mcpClientsProvider;
    }

    /**
     * 连接总览：MCP 客户端是否启用、连了几个 Server、每个连接的状态与能力。
     *
     * <p>字段解读：{@code enabled=false} 说明 MCP 客户端被关掉了（或注入未生效）；
     * {@code initialized=false} 说明子进程起来了但握手未完成。
     */
    @GetMapping("/client/status")
    public Map<String, Object> clientStatus() {
        List<McpSyncClient> clients = resolveClients();
        List<Map<String, Object>> details = new ArrayList<>();
        for (McpSyncClient client : clients) {
            details.add(describe(client));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", !clients.isEmpty());
        result.put("clientCount", clients.size());
        result.put("clients", details);
        result.put("hint", clients.isEmpty()
                ? "未发现 MCP 客户端。请检查 spring.ai.mcp.client.enabled / MCP_CLIENT_ENABLED 环境变量"
                : "连接正常，可继续访问 /api/mcp/client/tools 查看外部工具清单");
        return result;
    }

    /**
     * 外部工具清单：对每个连接实时发起 {@code tools/list}。
     *
     * <p>返回的 {@code toolCount} 就是「模型额外获得了多少个外部工具」——
     * 这个数字与本地 6 个 @Tool 无关，两者在 ChatClient 里是叠加关系。
     */
    @GetMapping("/client/tools")
    public Map<String, Object> clientTools() {
        List<McpSyncClient> clients = resolveClients();
        List<Map<String, Object>> allTools = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (McpSyncClient client : clients) {
            String server = serverLabel(client);
            try {
                // ★ 活体探测：这是一次真实的 JSON-RPC 往返，不是读缓存
                McpSchema.ListToolsResult listed = client.listTools();
                for (McpSchema.Tool tool : listed.tools()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("server", server);
                    item.put("name", tool.name());
                    item.put("title", tool.title());
                    item.put("description", brief(tool.description()));
                    allTools.add(item);
                }
            }
            catch (Exception e) {
                // 连接还在但 listTools 失败，是最需要暴露的中间态，不能吞掉
                log.error("MCP tools/list 调用失败, server={}", server, e);
                errors.add(server + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clientCount", clients.size());
        result.put("toolCount", allTools.size());
        result.put("tools", allTools);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        return result;
    }

    /** 取当前容器中的 MCP 客户端列表；未启用时为空列表而非 null。 */
    private List<McpSyncClient> resolveClients() {
        return mcpClientsProvider.stream().flatMap(List::stream).toList();
    }

    private Map<String, Object> describe(McpSyncClient client) {
        McpSchema.Implementation serverInfo = client.getServerInfo();
        McpSchema.ServerCapabilities caps = client.getServerCapabilities();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("serverName", serverInfo == null ? null : serverInfo.name());
        item.put("serverVersion", serverInfo == null ? null : serverInfo.version());
        item.put("initialized", client.isInitialized());
        item.put("hasToolsCapability", caps != null && caps.tools() != null);
        item.put("serverInstructions", client.getServerInstructions());
        return item;
    }

    /** 给连接起个可读标签，便于在多 Server 场景下区分是哪一个。 */
    private String serverLabel(McpSyncClient client) {
        McpSchema.Implementation info = client.getServerInfo();
        return info == null ? "unknown" : info.name();
    }

    private static String brief(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }
}
