package com.example.agentdemo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 知识库配置（前缀 {@code app.rag}）。
 *
 * <p>刻意使用 {@code app.*} 前缀而不放 {@code spring.ai.*}，与项目既有的
 * {@link com.example.agentdemo.memory.ChatMemoryProperties} 保持一致，
 * 避免与 Spring AI / Alibaba 官方自动装配的属性绑定发生冲突。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 上传文件落盘目录（相对进程工作目录；不存在时由应用启动时自动创建）。 */
    private String storageDir = "./data/kb-uploads";

    // ---------------- TokenTextSplitter 分片参数 ----------------

    /** 每个分片的目标 token 数。 */
    private int chunkSize = 800;

    /** 分片的最小字符数。 */
    private int minChunkSizeChars = 350;

    /** 低于该长度的分片不参与 embedding（过滤碎片）。 */
    private int minChunkLengthToEmbed = 5;

    /** 单个文档最多产出的分片数。 */
    private int maxNumChunks = 10000;

    /** 是否保留分隔符（换行等）。 */
    private boolean keepSeparator = true;

    // ---------------- 检索默认参数 ----------------

    /** 默认返回条数。 */
    private int defaultTopK = 4;

    /** 默认相似度阈值（0~1，越大越严格）。 */
    private double defaultSimilarityThreshold = 0.5;

    // ---------------- 上传限制 ----------------

    /** 单文件大小上限（字节），超过直接拒绝。 */
    private long maxFileSize = 20L * 1024 * 1024;

    /** 单文件最大分片数，超过则截断并告警（防超大文档把 embedding 配额打光）。 */
    private int maxChunksPerDocument = 500;

    /**
     * 向量库 Redis key 前缀，<b>须与 {@code spring.ai.vectorstore.redis.prefix} 保持一致</b>。
     * 仅用于删除失败时的兜底「按 key 前缀扫描孤儿分片」，属辅助字段。
     */
    private String redisKeyPrefix = "agent:kb:";

    /**
     * 单次向量库写入的批量大小（避免单请求过大 / embedding 限流）。
     *
     * <p><b>受 embedding 服务商的两道上限约束，须取更小者</b>——以 DashScope
     * {@code text-embedding-v3} 为例：
     * <ol>
     *   <li>SDK 本地断言 ≤ <b>25</b> 条：超出抛
     *       {@code IllegalArgumentException: The input texts limit 25.}（请求尚未发出）；</li>
     *   <li>服务端校验 ≤ <b>10</b> 条：超出返回 {@code HTTP 400:
     *       batch size is invalid, it should not be larger than 10}。</li>
     * </ol>
     * 即真正的硬上限是 <b>10</b>——25 只是本地前置断言，够不着服务端限制。
     * 二者均属不可重试错误（NonTransient）。故默认值取 10；
     * 更换模型/服务商时须重新核对其批量上限。
     */
    private int batchSize = 10;
}
