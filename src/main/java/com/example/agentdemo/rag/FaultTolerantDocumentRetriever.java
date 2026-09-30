package com.example.agentdemo.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;

/**
 * 容错检索器：包装任意 {@link DocumentRetriever}，把「检索阶段异常」降级为「检索为空」。
 *
 * <h3>为什么需要它</h3>
 * <p>{@code RetrievalAugmentationAdvisor.before()} 在 {@code CompletableFuture} 异步线程里调用
 * {@link DocumentRetriever#retrieve}。它需要的 query 向量由 <b>embedding 服务</b>实时计算——
 * 这一步要跨公网访问 DashScope，天然存在失败可能：
 * <ul>
 *   <li>{@code java.net.SocketException: Connection reset}（代理/网关掐断长连接、服务端限流）</li>
 *   <li>{@code ResourceAccessException: I/O error on POST ...}</li>
 *   <li>{@code HTTP 429} 超出 QPS/配额</li>
 * </ul>
 *
 * <p>而框架的行为是：<b>检索抛异常 → 整个 advisor 链中断 → Flux 以 error 终止</b>，
 * 用户看到的是「流式输出中断」，连一句普通对话都问不出来。
 *
 * <p>但从语义上讲，RAG 检索只是「<b>增强</b>」而非「必需」：
 * 知识库检索失败/未命中，正确的降级路径是<b>按纯对话继续回答</b>
 * （配合 {@code ContextualQueryAugmenter.allowEmptyContext(true)}，空上下文不会触发固定拒答）。
 * 因此这里把异常吞掉、返回空列表，让主流程不受影响。
 *
 * <h3>为什么用装饰器而不是 try/catch 包在 Advisor 外面</h3>
 * <p>异常发生在 advisor 链内部（且是异步线程），在 controller 层捕获只能得到
 * 「已经中断的 Flux」，无法续接。唯一正确的切入点就是检索器本身——
 * 在<b>异常产生的位置</b>把它转换成合法的业务结果（空结果集）。
 *
 * <h3>副作用与权衡</h3>
 * <ul>
 *   <li><b>可观测性</b>：降级会打 WARN 日志（含异常摘要），便于统计「网络抖动的实际频率」。
 *       若某段时间 WARN 持续刷屏，说明是<b>持续性</b>问题（代理配置错误 / 配额耗尽），
 *       而非偶发抖动，需按日志摘要去查真实原因，不要因为「界面不报错」而忽略。</li>
 *   <li><b>与工具检索的差异</b>：模型主动调 {@code searchKnowledgeBase} 工具时，
 *       失败会<b>如实返回错误文本给模型</b>（见 {@code KnowledgeTools}），
 *       模型可据此告知用户「检索暂时不可用」。两条路径的降级语义不同是刻意的：
 *       自动检索对用户不可见，静默降级；主动检索是显式动作，必须给出明确反馈。</li>
 * </ul>
 */
@Slf4j
public class FaultTolerantDocumentRetriever implements DocumentRetriever {

    private final DocumentRetriever delegate;

    public FaultTolerantDocumentRetriever(DocumentRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Document> retrieve(Query query) {
        try {
            return delegate.retrieve(query);
        } catch (Exception e) {
            // 仅降级，不抛出：让对话按「无知识库上下文」继续。
            log.warn("知识库检索失败，已降级为无上下文对话（query=\"{}\"，原因={}: {}）。"
                            + "若该日志持续出现，请检查 DashScope 连通性/配额，而非视作偶发抖动。",
                    query == null ? null : query.text(),
                    e.getClass().getSimpleName(),
                    summarize(e));
            return List.of();
        }
    }

    /** 把异常压成单行摘要，避免栈信息刷屏（完整栈仍可由 DEBUG 级别或全局异常处理获取）。 */
    private static String summarize(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : msg.replaceAll("\\s+", " ").trim();
    }
}
