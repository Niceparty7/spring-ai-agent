package com.example.agentdemo.skill;

/**
 * 当前请求会话上下文（ThreadLocal）。
 *
 * <p>作用：让无状态的 {@code @Tool} 方法（{@link SkillTools#loadSkill}）拿到「当前会话 ID」。
 * {@link SkillInstructionAdvisor} 在 {@code before()} 里从请求上下文读出
 * {@code ChatMemory.CONVERSATION_ID} 写入本 ThreadLocal，工具执行期可读，
 * {@code after()} 再清除，避免线程复用时的脏数据。
 *
 * <p><b>已知边界</b>：本机制依赖 advisor 的 {@code before()} 与内部工具执行发生在同一线程，
 * 对非流式 {@code call()} 恒成立；流式 {@code stream()} 经响应式调度器可能跨线程，
 * 该路径下的 skill 激活留待后续完善（不影响主路径验收）。
 */
public final class SkillContextHolder {

    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private SkillContextHolder() {
    }

    public static void set(String conversationId) {
        CONVERSATION_ID.set(conversationId);
    }

    public static String get() {
        String id = CONVERSATION_ID.get();
        return id == null || id.isBlank() ? "default" : id;
    }

    public static void clear() {
        CONVERSATION_ID.remove();
    }
}
