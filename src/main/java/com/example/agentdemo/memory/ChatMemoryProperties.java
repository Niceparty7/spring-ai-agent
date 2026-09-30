package com.example.agentdemo.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 多轮会话记忆的自定义配置（前缀 {@code app.chat-memory}）。
 *
 * <p>为什么不用 {@code spring.ai.*}：这个前缀被 Spring AI 官方与 Alibaba 扩展占用，
 * 各自的自动装配类会去绑定它。本项目是手工装配（见 {@code ChatMemoryConfig}），
 * 用自己的前缀可以彻底避开自动装配的干扰。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.chat-memory")
public class ChatMemoryProperties {

    /** {@code redis}（默认）= 持久化到 Redis；{@code memory} = 退回进程内实现，仅便于本地调试。 */
    private String type = "redis";

    /** Redis key 前缀，最终 key = keyPrefix + conversationId。 */
    private String keyPrefix = "agent:chat:memory:";

    /** 滑动过期时长：每次写入都会把该会话的 TTL 重置为该值（不是累加）。 */
    private Duration timeToLive = Duration.ofHours(24);

    /** Redis 不可用时是否降级为内存实现。{@code false} = 快速失败，让问题直接暴露。 */
    private boolean fallbackToMemory = true;

    /** 降级后的探活冷却时长：冷却期内不再尝试 Redis，避免每次请求都吃一次连接超时。 */
    private Duration retryInterval = Duration.ofSeconds(30);

    public boolean isRedis() {
        return !"memory".equalsIgnoreCase(type);
    }
}
