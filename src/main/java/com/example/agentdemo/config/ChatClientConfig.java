package com.example.agentdemo.config;

import com.example.agentdemo.agent.ProductTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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

    /**
     * 共享对话记忆。
     *
     * <p>为什么独立成 Bean：
     * <ol>
     *   <li>{@link MessageWindowChatMemory} 内部持有 {@link InMemoryChatMemoryRepository}，
     *       那是"所有会话"共用的存储容器（以 conversationId 为 key），必须单例共享；</li>
     *   <li>「新对话」需要注入它调用 {@code clear(conversationId)} 主动释放内存。</li>
     * </ol>
     *
     * <p>maxMessages 只限制"单个会话"的消息条数（默认 20，SystemMessage 不参与裁剪），
     * <b>不限制会话数量</b>——后者是内存版的固有限制。
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                // 不传 repository 时框架也会自动 new InMemoryChatMemoryRepository()，
                // 这里显式写出便于阅读，也便于日后替换为 JDBC / Redis 实现。
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ProductTools productTools,
                                 ChatMemory chatMemory) {
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
                // 记忆顾问以 defaultAdvisor 形式注册：它本身无状态，具体使用哪个会话由
                // 每次请求的 CONVERSATION_ID 参数决定（见 AgentController），
                // 因此一个单例 ChatClient + 一个 advisor 实例即可服务任意多个会话。
                // 该类构造函数为 private，必须用 builder 创建。
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
