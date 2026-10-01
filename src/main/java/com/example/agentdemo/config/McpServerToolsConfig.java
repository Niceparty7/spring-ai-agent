package com.example.agentdemo.config;

import com.example.agentdemo.agent.KnowledgeTools;
import com.example.agentdemo.agent.ProductTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 工具暴露装配：把本应用的业务工具经 SSE 端点（/sse + /mcp/message）
 * 提供给外部 MCP 客户端（Claude Desktop、WorkBuddy 等）。
 *
 * <p>机制（Spring AI 1.1.2 源码核实）：server 侧的
 * {@code ToolCallbackConverterAutoConfiguration} 会收集【容器中全部
 * {@link ToolCallbackProvider} / {@code ToolCallback} Bean】，转换为 MCP 协议的工具描述。
 * 因此——
 * <ul>
 *   <li>本 Bean 是唯一应被收集的 provider，最终只暴露这 6 个本地工具；</li>
 *   <li>MCP Client 侧的 {@code SyncMcpToolCallbackProvider} 已通过
 *       {@code spring.ai.mcp.client.toolcallback.enabled=false} 禁止注册为容器 Bean
 *       （否则 filesystem 等远程工具会被本 Server 一并收集、对外重复暴露），
 *       它由 {@code ChatClientConfig} 手工构建后直接接入 ChatClient。</li>
 * </ul>
 *
 * <p>注意：这里传入的是与 ChatClient 完全相同的两个工具 Bean 实例，
 * {@code MethodToolCallbackProvider} 只读取注解元数据生成另一份 ToolCallback，
 * 不存在「同一工具重复注册」问题（那个坑仅针对 ChatClient 的
 * {@code .defaultTools(x).tools(x)} 链式重复调用）。
 */
@Configuration
public class McpServerToolsConfig {

    @Bean
    public ToolCallbackProvider localToolCallbackProvider(ProductTools productTools,
                                                          KnowledgeTools knowledgeTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(productTools, knowledgeTools)
                .build();
    }
}
