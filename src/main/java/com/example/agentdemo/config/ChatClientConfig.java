package com.example.agentdemo.config;

import com.example.agentdemo.agent.KnowledgeTools;
import com.example.agentdemo.agent.ProductTools;
import com.example.agentdemo.rag.FaultTolerantDocumentRetriever;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
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
     * <p>本类只负责「记多少」（{@code maxMessages} 窗口裁剪），「存哪里」交给注入的
     * {@link ChatMemoryRepository}——由 {@code ChatMemoryConfig} 按
     * {@code app.chat-memory.type} 装配为 Redis 或进程内实现。
     *
     * <p>为什么独立成 Bean：
     * <ol>
     *   <li>{@link MessageWindowChatMemory} 内部持有 repository（记忆的实际存储容器），
     *       必须单例共享；</li>
     *   <li>「新对话」需要注入它调用 {@code clear(conversationId)} —— 对 Redis 实现而言，
     *       这一步会删掉对应的 key。</li>
     * </ol>
     *
     * <p>maxMessages 只限制「单个会话」的消息条数（默认 20，SystemMessage 不参与裁剪），
     * <b>不限制会话数量</b>——会话数量的收敛靠 Redis key 的 TTL（见
     * {@code app.chat-memory.time-to-live}）。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ProductTools productTools,
                                 KnowledgeTools knowledgeTools,
                                 ChatMemory chatMemory,
                                 VectorStore vectorStore,
                                 RagProperties ragProps) {
        return builder
                .defaultSystem("""
                        你是「商品管理系统」的智能助手。
                        你可以调用工具完成商品的查询、新增、改价、删除；
                        也可以基于「知识库」回答文档相关的问题。

                        规则：
                        1. 只要用户意图涉及商品操作，就必须调用相应工具，不要凭空编造数据；
                        2. 涉及改价或删除时，如果缺少商品 ID，先向用户询问 ID，不要猜测；
                        3. 回答知识库问题时，优先依据上下文中已提供的资料；资料不足时
                           调用 searchKnowledgeBase 工具进一步检索；
                        4. 知识库中查不到的内容，要如实说明「知识库中未找到」，不要编造；
                        5. 工具返回的内容要如实转述，并用简洁的中文总结关键结果。
                        """)
                // 传入带 @Tool 注解的 Bean，框架自动扫描并生成模型可理解的 JSON Schema。
                // 注意：不要再对同一工具调用 .tools()，否则会报 "Multiple tools with the same name"。
                .defaultTools(productTools, knowledgeTools)
                // 记忆顾问以 defaultAdvisor 形式注册：它本身无状态，具体使用哪个会话由
                // 每次请求的 CONVERSATION_ID 参数决定（见 AgentController），
                // 因此一个单例 ChatClient + 一个 advisor 实例即可服务任意多个会话。
                // 该类构造函数为 private，必须用 builder 创建。
                //
                // RAG 顾问紧随其后：RetrievalAugmentationAdvisor（在 spring-ai-rag 中，
                // 已由 dashscope starter 传递引入，无需额外依赖）。
                //
                // 关于顺序：advisor 链在 before() 阶段按 order 升序执行。
                // MessageChatMemoryAdvisor 默认 order = HIGHEST_PRECEDENCE + 1000，
                // RetrievalAugmentationAdvisor 默认 order = 0，故「记忆顾问先执行、RAG 顾问后执行」，
                // 这正是期望行为 —— 会话历史只记录用户原话，不含 RAG 注入的大段文档上下文，
                // 避免多轮对话后历史迅速膨胀。因此这里无需显式指定 order。
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        RetrievalAugmentationAdvisor.builder()
                                // ★ 关键：用容错装饰器包住检索器。
                                //   RAG 检索需要实时调用 embedding 服务（跨公网），失败时框架会让
                                //   整条 advisor 链抛异常 → Flux error → 前端「流式输出中断」，
                                //   连普通对话都无法回答。而检索只是「增强」不是「必需」，
                                //   故在异常产生处降级为「空上下文」，让对话继续。
                                //   详见 FaultTolerantDocumentRetriever 的类注释。
                                .documentRetriever(new FaultTolerantDocumentRetriever(
                                        VectorStoreDocumentRetriever.builder()
                                                .vectorStore(vectorStore)
                                                .similarityThreshold(ragProps.getDefaultSimilarityThreshold())
                                                .topK(ragProps.getDefaultTopK())
                                                .build()))
                                .queryAugmenter(ContextualQueryAugmenter.builder()
                                        // ★ 必须为 true：默认 false 时，若检索结果为空，
                                        //   顾问会直接让模型回固定拒答话术，普通闲聊也会被拦截。
                                        .allowEmptyContext(true)
                                        .build())
                                .build())
                .build();
    }
}
