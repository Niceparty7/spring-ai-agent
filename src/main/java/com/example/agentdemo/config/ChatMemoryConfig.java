package com.example.agentdemo.config;

import com.example.agentdemo.memory.ChatMemoryProperties;
import com.example.agentdemo.memory.RedisChatMemoryRepository;
import com.example.agentdemo.memory.ResilientChatMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 会话记忆存储装配。
 *
 * <p>只产出 {@link ChatMemoryRepository}，不碰 {@link org.springframework.ai.chat.memory.ChatMemory}
 * ——裁剪逻辑（maxMessages）留在 {@code ChatClientConfig} 里，本类只负责「存哪里」。
 *
 * <p>两个分支用 {@code @ConditionalOnProperty} 区分，必须放在同一个 {@code @Configuration} 类中；
 * 且 {@code destroyMethod = "close"} 只能挂在 redis 分支上——
 * {@link InMemoryChatMemoryRepository} 并没有 {@code close()} 方法，挂上去会在销毁阶段报错。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class ChatMemoryConfig {

    /** 进程内实现：整体切回单机内存，重启即丢，仅用于本地调试对比。 */
    @Bean
    @ConditionalOnProperty(name = "app.chat-memory.type", havingValue = "memory")
    public ChatMemoryRepository inMemoryChatMemoryRepository() {
        log.warn("app.chat-memory.type=memory —— 多轮会话记忆使用进程内实现，应用重启后历史将丢失");
        return new InMemoryChatMemoryRepository();
    }

    /** Redis 实现（默认）：外挂 {@link ResilientChatMemoryRepository} 提供降级与自动恢复。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.chat-memory.type", havingValue = "redis", matchIfMissing = true)
    public ChatMemoryRepository redisChatMemoryRepository(ChatMemoryProperties properties,
                                                          StringRedisTemplate redis) {
        ChatMemoryRepository primary =
                new RedisChatMemoryRepository(redis, properties.getKeyPrefix(), properties.getTimeToLive());

        log.info("多轮会话记忆已启用 Redis 持久化：keyPrefix={}, ttl={}（滑动续期）, Redis 故障时{}",
                properties.getKeyPrefix(),
                properties.getTimeToLive(),
                properties.isFallbackToMemory() ? "降级为进程内记忆" : "快速失败");

        return new ResilientChatMemoryRepository(
                primary,
                new InMemoryChatMemoryRepository(),
                properties.isFallbackToMemory(),
                properties.getRetryInterval().toMillis());
    }
}
