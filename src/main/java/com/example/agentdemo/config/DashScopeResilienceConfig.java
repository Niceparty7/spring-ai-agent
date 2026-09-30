package com.example.agentdemo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.HashMap;
import java.util.Map;

/**
 * DashScope HTTP 调用的容错装配。
 *
 * <h3>问题背景</h3>
 * <p>日志中出现（RAG 自动检索期间）：
 * <pre>
 * org.springframework.web.client.ResourceAccessException:
 *     I/O error on POST request for ".../text-embedding/text-embedding": Connection reset
 *   Caused by: java.net.SocketException: Connection reset
 * </pre>
 * 栈中可见 {@code RetryTemplate.doExecute}，说明框架<b>已重试过</b>仍失败。
 *
 * <h3>框架默认重试策略（反编译 {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} 实测）</h3>
 * <table border="1">
 *   <tr><th>项</th><th>默认值</th><th>问题</th></tr>
 *   <tr><td>maxAttempts</td><td>10</td><td>次数偏多</td></tr>
 *   <tr><td>可重试异常</td><td>{@code TransientAiException},
 *       {@code ResourceAccessException}</td><td>已覆盖 {@code Connection reset}——实测框架会把
 *       底层 I/O 错误包成 {@code ResourceAccessException} 并重试（本项目实测日志：
 *       {@code ResourceAccessException: ClosedChannelException} 确实触发了重试）。故默认策略的
 *       <b>真正问题只在退避时长</b>，而非漏掉异常类型</td></tr>
 *   <tr><td>退避</td><td>指数退避 2s × 5，封顶 <b>180s</b></td>
 *       <td><b>★ 致命</b>：10 次尝试最坏累计退避约 2+10+50+180+180+180+180+180+180 ≈
 *       <b>19 分钟</b>，远超 MVC 异步超时（{@code spring.mvc.async.request-timeout=300000}，5 分钟）
 *       → 前端会先超时断开，用户看到「中断」，而线程仍在后台空转重试</td></tr>
 * </table>
 *
 * <h3>本项目策略</h3>
 * <ul>
 *   <li>重试次数收敛到 <b>3</b> 次、退避 <b>500ms 起、指数 ×2、封顶 4s</b>
 *       → 最坏累计约 500ms + 1s ≈ 1.5s，远小于任何超时，失败就快速失败。</li>
 *   <li>显式对<b>底层网络异常</b>也重试：{@code SocketException} / {@code SocketTimeoutException} /
 *       {@code HttpTimeoutException}——不依赖框架的异常归类是否把 {@code Connection reset} 正确
 *       包成 {@code ResourceAccessException}。</li>
 *   <li>附带 {@link RetryListener} 记录每次重试，便于在日志中区分「偶发抖动已自愈」
 *       与「持续性故障」。</li>
 * </ul>
 *
 * <h3>为什么用 Bean 覆盖而不是 {@code RetryTemplateBuilder} 定制</h3>
 * <p>{@code DashScopeEmbeddingAutoConfiguration#dashscopeEmbeddingModel} 通过
 * {@code ObjectProvider<RetryTemplate>} 取用，<b>容器中存在用户 Bean 时优先用它</b>
 * （无用户 Bean 才回落到 {@code RetryUtils.DEFAULT_RETRY_TEMPLATE}）。
 * 因此定义一个 {@code RetryTemplate} Bean 即可全局生效，无需手工重建模型 Bean。
 * <p>⚠️ 该 Bean <b>全局唯一</b>，chat / embedding / rerank 共用。故这里只放宽「重试哪些异常」，
 * 不改变「重试语义」——原来的 {@code TransientAiException} 等仍照常重试。
 *
 * <h3>与本机 {@code http_proxy} 的关系</h3>
 * <p>本机存在 {@code http_proxy/https_proxy=http://127.0.0.1:7661}，JDK {@code HttpClient}
 * 会读取该环境变量。代理在长连接复用或高频请求时会掐断连接，是 {@code Connection reset}
 * 最常见的诱因。<b>若日志显示本类重试后仍持续失败</b>，应优先排查代理：
 * 在 IDEA 的运行配置中把 {@code NO_PROXY} 设为 {@code dashscope.aliyuncs.com}，
 * 或直接清空 {@code http_proxy/https_proxy}（DashScope 国内直连可用，无需代理）。
 */
@Slf4j
@Configuration
public class DashScopeResilienceConfig {

    /** 重试次数（含首次调用）。3 次足以吸收瞬时抖动，又不至于把等待时间拖长。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 首次退避间隔。 */
    private static final long INITIAL_BACKOFF_MS = 500L;

    /** 退避倍数。 */
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /** 单次退避封顶。 */
    private static final long MAX_BACKOFF_MS = 4_000L;

    @Bean
    public RetryTemplate dashScopeRetryTemplate() {
        // 1) 重试策略：显式列出需要重试的异常类型。
        //    retryOn 之外的类型一律快速失败（如 400 参数错误、401 鉴权失败——重试无意义）。
        Map<Class<? extends Throwable>, Boolean> retryable = new HashMap<>();
        retryable.put(TransientAiException.class, true);      // 框架定义的「瞬时 AI 异常」
        retryable.put(ResourceAccessException.class, true);   // HTTP 层 I/O 错误（Connection reset 的常见包装）
        retryable.put(SocketException.class, true);           // ★ 底层网络：Connection reset 的真正类型
        retryable.put(SocketTimeoutException.class, true);    // 读超时
        retryable.put(HttpTimeoutException.class, true);      // JDK HttpClient 超时

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(MAX_ATTEMPTS, retryable, true);

        // 2) 退避策略：指数 + 封顶，避免默认的 180s 封顶把请求拖过超时线。
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(INITIAL_BACKOFF_MS);
        backOffPolicy.setMultiplier(BACKOFF_MULTIPLIER);
        backOffPolicy.setMaxInterval(MAX_BACKOFF_MS);

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);
        template.setListeners(new RetryListener[]{new LoggingRetryListener()});

        log.info("DashScope 重试策略已装配：maxAttempts={}，退避 {}ms ×{}，封顶 {}ms（默认策略为 10 次 / 封顶 180s，易拖过异步超时）",
                MAX_ATTEMPTS, INITIAL_BACKOFF_MS, BACKOFF_MULTIPLIER, MAX_BACKOFF_MS);
        return template;
    }

    /**
     * 重试日志监听器。
     *
     * <p>框架默认监听器只打一行 {@code Retry error. Retry count:{}}（WARN，不含异常与目标），
     * 排查时信息量不足。这里补充：第几次重试、异常摘要、退避时长——用于快速区分
     * 「偶发抖动（重试后成功）」与「持续故障（每次都耗尽重试）」。
     */
    private static class LoggingRetryListener implements RetryListener {

        @Override
        public <T, E extends Throwable> void onError(RetryContext context,
                                                     RetryCallback<T, E> callback,
                                                     Throwable throwable) {
            // ★ getRetryCount() 在 onError 触发时已递增（第 1 次失败即为 1）。
            //   剩余次数 = MAX_ATTEMPTS - 1 - 已重试次数，不可写成 MAX_ATTEMPTS - 1（会恒为 2）。
            int retried = context.getRetryCount();
            log.warn("DashScope 调用失败，已重试 {} 次，剩余 {} 次机会：{}: {}",
                    retried, Math.max(0, (MAX_ATTEMPTS - 1) - retried),
                    throwable.getClass().getSimpleName(), briefMessage(throwable));
        }

        @Override
        public <T, E extends Throwable> void close(RetryContext context,
                                                   RetryCallback<T, E> callback,
                                                   Throwable throwable) {
            if (throwable != null) {
                log.error("DashScope 调用在 {} 次尝试（含首次）后仍失败，放弃重试：{}: {}",
                        context.getRetryCount() + 1, throwable.getClass().getSimpleName(), briefMessage(throwable));
            }
        }

        private static String briefMessage(Throwable e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String msg = root.getMessage();
            return msg == null || msg.isBlank()
                    ? root.getClass().getSimpleName()
                    : msg.replaceAll("\\s+", " ").trim();
        }
    }
}
