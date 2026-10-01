package com.example.agentdemo.skill;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级 skill 激活状态（<b>会话隔离的关键</b>）。
 *
 * <p>每个 conversationId 维护一份已激活的 skill 名集合 + 最近访问时间戳。
 * 不同会话激活不同 skill 互不干扰。TTL 惰性淘汰：读取时发现超时即整体移除，
 * 避免会话无限膨胀（与 Redis 记忆 TTL 的思路一致，但这里是进程内态）。
 */
public class SkillSessionState {

    private final Duration ttl;

    private final ConcurrentHashMap<String, SessionSkills> sessions = new ConcurrentHashMap<>();

    public SkillSessionState(Duration ttl) {
        this.ttl = ttl;
    }

    public void activate(String conversationId, String skillName) {
        sessions.compute(conversationId, (k, v) -> {
            SessionSkills s = v == null ? new SessionSkills() : v;
            s.activeSkills.add(skillName);
            s.lastAccess = System.currentTimeMillis();
            return s;
        });
    }

    public boolean isActive(String conversationId, String skillName) {
        SessionSkills s = get(conversationId);
        return s != null && s.activeSkills.contains(skillName);
    }

    public Set<String> activeNames(String conversationId) {
        SessionSkills s = get(conversationId);
        return s == null ? Collections.emptySet() : Set.copyOf(s.activeSkills);
    }

    /** 清空某会话的激活态（「新对话」时调用）。 */
    public void clear(String conversationId) {
        sessions.remove(conversationId);
    }

    private SessionSkills get(String conversationId) {
        SessionSkills s = sessions.get(conversationId);
        if (s == null) {
            return null;
        }
        // 惰性 TTL 淘汰
        if (System.currentTimeMillis() - s.lastAccess > ttl.toMillis()) {
            sessions.remove(conversationId, s);
            return null;
        }
        s.lastAccess = System.currentTimeMillis();
        return s;
    }

    private static final class SessionSkills {
        private final Set<String> activeSkills = ConcurrentHashMap.newKeySet();
        private volatile long lastAccess = System.currentTimeMillis();
    }
}
