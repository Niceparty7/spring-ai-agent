package com.example.agentdemo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.util.StringUtils;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * RAG 向量库装配。
 *
 * <p><b>为什么要手工定义 {@code VectorStore} 而不用官方自动配置？</b>
 * Spring AI 1.1.2 的 {@code RedisVectorStoreAutoConfiguration} 只从
 * {@code RedisVectorStoreProperties} 读取 {@code indexName} 与 {@code prefix} 两个属性，
 * <b>无法声明 metadata 索引字段</b>。而 RediSearch 要求：只有建索引时声明为
 * TAG / NUMERIC / TEXT 的字段才能被 {@code FT.SEARCH} 的过滤表达式命中。
 * 本项目「按 docId 删除某文档的全部分片」依赖 {@code Filter.Expression} 对
 * {@code docId} 的精确匹配——若未声明为 TAG 字段，删除会静默失败（召回 0 条）。
 *
 * <p>因此手工构建 {@link RedisVectorStore} 并显式声明 metadataFields。
 * <b>但手工定义后必须排除官方自动配置</b>：{@code RedisVectorStoreAutoConfiguration}
 * 同样注册名为 {@code vectorStore} 的 Bean，二者同名会导致启动失败
 * （{@code @ConditionalOnMissingBean} 在自动配置之间<b>不一定</b>能感知到用户配置类的 Bean，
 * 因为自动配置类在启动早期即被排序注册，条件评估可能早于用户 {@code @Bean} 方法注册）。
 * 排除动作已写在 {@code AgentCrudDemoApplication} 的 {@code @SpringBootApplication(exclude = ...)} 上。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    /**
     * Redis 向量库。
     *
     * <p>{@code EmbeddingModel} 由 dashscope starter 自动装配（默认 text-embedding-v3）；
     * {@code JedisConnectionFactory} 因 jedis 在 classpath 而成为全局唯一的
     * RedisConnectionFactory，此处复用它持有的连接参数。
     *
     * <p>{@code initializeSchema(true)}：启动时先 {@code FT.INFO} 探测索引，不存在才
     * {@code FT.CREATE}，反复重启幂等。<b>注意</b>：该动作发生在应用启动期，
     * Redis 不在线会导致应用启动失败。
     */
    @Bean
    public VectorStore vectorStore(JedisConnectionFactory jedisConnectionFactory,
                                   EmbeddingModel embeddingModel,
                                   RagProperties properties,
                                   @Value("${spring.ai.vectorstore.redis.index-name:agent-knowledge-index}")
                                   String indexName,
                                   @Value("${spring.ai.vectorstore.redis.prefix:agent:kb:}")
                                   String prefix) {

        // 官方自动配置内部也是从 JedisConnectionFactory 取出连接参数后自建 JedisPooled
        // （其 jedisPooled 方法为 private），此处按同样思路重建，供手工装配场景使用。
        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
                .database(jedisConnectionFactory.getDatabase());
        // 仅在配置了密码时才设置：空串会被当成「要求 AUTH」，在未设密码的 Redis 上会报错
        if (StringUtils.hasText(jedisConnectionFactory.getPassword())) {
            clientConfig.password(jedisConnectionFactory.getPassword());
        }

        JedisPooled jedisPooled = new JedisPooled(
                new HostAndPort(jedisConnectionFactory.getHostName(), jedisConnectionFactory.getPort()),
                clientConfig.build());

        RedisVectorStore store = RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .initializeSchema(true)
                // ★ 必须声明：RediSearch 只允许对建索引时已声明的字段做过滤。
                //   docId 用于「按文档删除全部分片」，必须为 TAG。
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("docId"),
                        RedisVectorStore.MetadataField.tag("source"),
                        RedisVectorStore.MetadataField.tag("fileType"),
                        RedisVectorStore.MetadataField.text("fileName"),
                        RedisVectorStore.MetadataField.numeric("chunkIndex"),
                        RedisVectorStore.MetadataField.numeric("uploadedAt"))
                .build();

        log.info("RAG 向量库已装配：index={}, prefix={}, 分片token={}, 检索默认 topK={} / threshold={}",
                indexName, prefix, properties.getChunkSize(),
                properties.getDefaultTopK(), properties.getDefaultSimilarityThreshold());
        return store;
    }
}
