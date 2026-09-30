package com.example.agentdemo.agent;

import com.example.agentdemo.dto.KbSearchResult;
import com.example.agentdemo.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具 —— 让模型在需要时主动深入检索。
 *
 * <p>与「自动检索」的分工：{@code RetrievalAugmentationAdvisor} 会在每次提问时
 * 自动召回一批资料（兜底），本工具则用于「资料不足、用户要求深入查找」时主动深挖。
 * 为避免同一轮重复检索，工具的 description 明确收敛了触发场景。
 *
 * <p>遵循项目约定：工具方法统一返回 {@link String}（避免内部 ObjectMapper 的
 * JavaTime 序列化问题），description 写清「什么场景该调用」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeTools {

    private final KnowledgeBaseService knowledgeBaseService;

    @Tool(description = """
            检索本地知识库中的文档资料。仅当用户提问涉及已上传文档的内容，
            且上下文中已有的资料明显不足、或被要求「进一步查找 / 还有别的吗 / 再搜一下」时调用。
            常规知识库问答已由系统自动检索，无需重复调用本工具。
            """)
    public String searchKnowledgeBase(
            @ToolParam(description = "检索关键词或问题，尽量保留用户原始表述") String query,
            @ToolParam(description = "返回条数，取值 1~10，默认 4；用户要求「多找一些」时可调大", required = false)
            Integer topK) {

        log.info("[Tool] searchKnowledgeBase query={} topK={}", query, topK);
        int k = topK == null ? 4 : Math.max(1, Math.min(10, topK));

        List<KbSearchResult> hits = knowledgeBaseService.search(query, k, null);
        if (hits.isEmpty()) {
            return "知识库中未找到与该问题相关的资料。";
        }

        StringBuilder sb = new StringBuilder("知识库检索到 ").append(hits.size()).append(" 条相关资料：\n");
        for (int i = 0; i < hits.size(); i++) {
            KbSearchResult h = hits.get(i);
            sb.append("\n[").append(i + 1).append("] 来源《").append(h.fileName())
                    .append("》第 ").append(h.chunkIndex()).append(" 段\n")
                    .append(h.text()).append("\n");
        }
        return sb.toString();
    }
}
