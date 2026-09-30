package com.example.agentdemo.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redis 的会话记忆存储。
 *
 * <p><b>存储模型</b>：一个会话 = 一个 String 值，内容是消息数组的 JSON。
 * 之所以不用 Redis List，是因为 {@link ChatMemoryRepository#saveAll} 是「全量替换」语义
 * ——传进来的就是 {@code MessageWindowChatMemory} 按 maxMessages 裁剪后的完整列表，
 * 不需要任何增量操作。于是写入可以是单条命令：
 *
 * <pre>SET {keyPrefix}{conversationId} &lt;json&gt; EX &lt;ttl 秒&gt;</pre>
 *
 * <p>这一条命令同时完成三件事：全量替换、施加 TTL、TTL 续期（滑动过期）。
 * 既避开「先 DEL 再 PUSH」的非原子性，也不需要额外的 EXPIRE 往返。
 *
 * <p><b>序列化取舍</b>：不持久化 {@code metadata} 与 {@code toolCalls}，只存类型与正文。
 * {@code metadata} 的值域不受控（可能含不可序列化对象）；工具消息在框架内部控制工具执行时
 * 本就不会进入记忆（{@code MessageChatMemoryAdvisor} 只存「用户问题 + 最终回答」），
 * 因此这里丢弃 {@code TOOL} 类型与框架行为一致。
 *
 * <p><b>注意</b>：本类不做任何容错，Redis 异常会直接抛出，
 * 由 {@link ResilientChatMemoryRepository} 负责降级。
 */
@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    /** 落库用的扁平结构：只留类型与正文。 */
    private record StoredMessage(String type, String text) {
    }

    private static final TypeReference<List<StoredMessage>> STORED_LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration timeToLive;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisChatMemoryRepository(StringRedisTemplate redis, String keyPrefix, Duration timeToLive) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.timeToLive = timeToLive;
    }

    @Override
    public List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        // 用 SCAN 而不是 KEYS：KEYS 在 key 多时会阻塞整个 Redis 实例
        ScanOptions options = ScanOptions.scanOptions().match(keyPrefix + "*").count(500).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(key -> ids.add(key.substring(keyPrefix.length())));
        }
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String raw = redis.opsForValue().get(key(conversationId));
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<StoredMessage> stored;
        try {
            stored = objectMapper.readValue(raw, STORED_LIST);
        } catch (Exception e) {
            // 整段脏数据无法解析时按「空历史」处理，不要让一个坏 key 把对话彻底打挂
            log.error("会话历史反序列化失败，按空历史处理 (conversationId={})", conversationId, e);
            return List.of();
        }

        List<Message> messages = new ArrayList<>(stored.size());
        for (StoredMessage item : stored) {
            Message message = toMessage(item);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<StoredMessage> stored = messages.stream()
                .map(m -> new StoredMessage(m.getMessageType().name(), m.getText() == null ? "" : m.getText()))
                .toList();

        String json;
        try {
            json = objectMapper.writeValueAsString(stored);
        } catch (Exception e) {
            throw new IllegalStateException("会话历史序列化失败 (conversationId=" + conversationId + ")", e);
        }

        // 单条命令 = 全量替换 + TTL 续期（滑动过期），天然原子
        redis.opsForValue().set(key(conversationId), json, timeToLive);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(key(conversationId));
    }

    /**
     * 组装 key。
     *
     * <p>只做一处防御：把空白与控制字符替换成下划线。Redis 的 key 本身是二进制安全的，
     * 长度也已在 {@code AgentController#normalizeConversationId} 截到 128；
     * 这里仅避免 key 里混入换行导致 {@code redis-cli} 排查困难。
     */
    private String key(String conversationId) {
        return keyPrefix + conversationId.replaceAll("[\\s\\p{Cntrl}]", "_");
    }

    private Message toMessage(StoredMessage item) {
        MessageType type;
        try {
            type = MessageType.valueOf(item.type());
        } catch (IllegalArgumentException e) {
            log.warn("忽略未知的消息类型: {}", item.type());
            return null;
        }

        String text = item.text() == null ? "" : item.text();
        return switch (type) {
            case USER -> new UserMessage(text);
            case ASSISTANT -> new AssistantMessage(text);
            case SYSTEM -> new SystemMessage(text);
            // 框架内部控制工具执行时工具中间消息不会进入记忆，正常链路走不到这里；
            // 真出现也直接丢弃，保持与框架行为一致。
            case TOOL -> {
                log.debug("忽略工具消息（不参与会话记忆）");
                yield null;
            }
        };
    }
}
