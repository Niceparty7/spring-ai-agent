package com.example.agentdemo.config;

import com.example.agentdemo.agent.ProductTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 Agent 使用的 ChatClient。
 *
 * <p>{@code ChatClient.Builder} 由 spring-ai-alibaba-starter-dashscope 传递引入的
 * spring-ai-autoconfigure-model-chat-client 自动装配提供，直接注入即可。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ProductTools productTools) {
        return builder
                .defaultSystem("""
                        你是「商品管理系统」的智能助手。
                        你可以调用工具完成商品的查询、新增、改价、删除。

                        规则：
                        1. 只要用户意图涉及商品操作，就必须调用相应工具，不要凭空编造数据；
                        2. 涉及改价或删除时，如果缺少商品 ID，先向用户询问 ID，不要猜测；
                        3. 工具返回的内容要如实转述，并用简洁的中文总结关键结果。
                        """)
                // 传入带 @Tool 注解的 Bean，框架自动扫描并生成模型可理解的 JSON Schema。
                // 注意：不要再对同一工具调用 .tools()，否则会报 "Multiple tools with the same name"。
                .defaultTools(productTools)
                .build();
    }
}
