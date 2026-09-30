package com.example.agentdemo.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 给会话记忆仓库加一层「降级 + 自动恢复」保护。
 *
 * <p>主存储（Redis）正常时全部请求走主存储；一旦抛异常，本次请求立即改走备用存储
 * （进程内实现）并打 WARN，保证 Redis 故障不会让整个服务不可用。
 *
 * <p><b>为什么不是简单的 per-call try/catch</b>：Redis 宕机时每次请求都要等一次连接超时
 * （{@code spring.data.redis.timeout}，本项目配 2 秒），会把响应时间整体拖慢；
 * 而且没有恢复机制。所以这里维护两个状态：
 * <ul>
 *   <li>{@code degraded}：当前是否处于降级态；</li>
 *   <li>{@code nextProbeAt}：下次允许尝试主存储的时间戳。</li>
 * </ul>
 * 降级期内直接走备用存储、完全不碰 Redis；冷却期（{@code app.chat-memory.retry-interval}）
 * 结束后放一次请求去探活，成功即自动恢复。由于探活被冷却期门控，失败日志天然是
 * 「每个冷却窗口最多一条」，无需再做限流。
 *
 * <p><b>为什么主存储直接持有实例而不是用 Supplier 懒建</b>：Lettuce 的连接与命令都是懒执行的，
 * 构造 {@link RedisChatMemoryRepository} 不产生任何 I/O，也就不会在启动期因 Redis 缺席而失败
 * （应用能照常启动正是靠 Lettuce 的懒连接，而不是靠延迟构造）。
 *
 * <p><b>语义边界（重要）</b>：降级期间写入备用存储的对话，在 Redis 恢复后<b>不会回填</b>，
 * 应用重启即丢失。这是「服务不整体不可用」换来的代价。若不能接受，把
 * {@code app.chat-memory.fallback-to-memory} 设为 {@code false} 改为快速失败。
 */
@Slf4j
public class ResilientChatMemoryRepository implements ChatMemoryRepository, AutoCloseable {

    private final ChatMemoryRepository primary;
    private final ChatMemoryRepository fallback;
    private final boolean fallbackEnabled;
    private final long retryIntervalMillis;

    private volatile boolean degraded;
    private volatile long nextProbeAt;
    private volatile Throwable lastError;

    public ResilientChatMemoryRepository(ChatMemoryRepository primary,
                                         ChatMemoryRepository fallback,
                                         boolean fallbackEnabled,
                                         long retryIntervalMillis) {
        this.primary = primary;
        this.fallback = fallback;
        this.fallbackEnabled = fallbackEnabled;
        this.retryIntervalMillis = retryIntervalMillis;
    }

    @Override
    public List<String> findConversationIds() {
        ChatMemoryRepository target = primaryOrNull();
        if (target != null) {
            try {
                List<String> ids = target.findConversationIds();
                markHealthy();
                return ids;
            } catch (Exception e) {
                markDegraded(e);
            }
        }
        failIfNoFallback();
        return fallback.findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        ChatMemoryRepository target = primaryOrNull();
        if (target != null) {
            try {
                List<Message> messages = target.findByConversationId(conversationId);
                markHealthy();
                return messages;
            } catch (Exception e) {
                markDegraded(e);
            }
        }
        failIfNoFallback();
        return fallback.findByConversationId(conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        ChatMemoryRepository target = primaryOrNull();
        if (target != null) {
            try {
                target.saveAll(conversationId, messages);
                markHealthy();
                return;
            } catch (Exception e) {
                markDegraded(e);
            }
        }
        failIfNoFallback();
        fallback.saveAll(conversationId, messages);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        ChatMemoryRepository target = primaryOrNull();
        if (target != null) {
            try {
                target.deleteByConversationId(conversationId);
                markHealthy();
            } catch (Exception e) {
                markDegraded(e);
            }
        }
        failIfNoFallback();
        // 总是同时清理备用存储：降级期间可能往里写过历史，主存储恢复后这里会留下脏数据
        fallback.deleteByConversationId(conversationId);
    }

    /**
     * 取主存储：降级冷却期内返回 {@code null}（表示本次直接用备用存储）。
     */
    private ChatMemoryRepository primaryOrNull() {
        if (degraded && System.currentTimeMillis() < nextProbeAt) {
            return null;
        }
        return primary;
    }

    private void markHealthy() {
        if (degraded) {
            degraded = false;
            log.info("Redis 会话记忆已恢复，后续对话历史将重新写入 Redis");
        }
    }

    private void markDegraded(Throwable cause) {
        nextProbeAt = System.currentTimeMillis() + retryIntervalMillis;
        degraded = true;
        lastError = cause;
        if (fallbackEnabled) {
            log.warn("Redis 会话记忆不可用，已临时降级为进程内记忆（{} ms 后重试）。"
                            + "降级期间的对话不会写入 Redis，应用重启后会丢失。",
                    retryIntervalMillis, cause);
        } else {
            log.error("Redis 会话记忆不可用，且 app.chat-memory.fallback-to-memory=false，本次请求将快速失败",
                    cause);
        }
    }

    private void failIfNoFallback() {
        if (!fallbackEnabled) {
            throw new IllegalStateException(
                    "Redis 会话记忆不可用，且 app.chat-memory.fallback-to-memory=false", lastError);
        }
    }

    @Override
    public void close() {
        // 该对象不是直接由 Spring 以 @Bean 形式暴露给容器的（容器拿到的是本包装类的代理语义），
        // 为保证连接资源确实释放，这里显式向下关闭。
        closeQuietly(primary);
        closeQuietly(fallback);
    }

    private static void closeQuietly(ChatMemoryRepository repository) {
        if (repository instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("关闭会话记忆仓库失败: {}", repository.getClass().getSimpleName(), e);
            }
        }
    }
}
