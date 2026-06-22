package com.medsage.tool;

import com.medsage.rag.RagDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 知识库检索工具
 *
 * 封装 RagDocumentService 的混合检索（向量 + BM25），
 * 供 Agent 在需要查阅医学知识时自主调用。
 *
 * 检索流程：查询重写 → 向量+BM25并行检索 → 混合打分 → 父文档召回
 */
public class RagSearchTool {

    private static final Logger log = LoggerFactory.getLogger(RagSearchTool.class);

    private final RagDocumentService ragDocumentService;

    public RagSearchTool(RagDocumentService ragDocumentService) {
        this.ragDocumentService = ragDocumentService;
    }

    /**
     * 从医学知识库中检索相关信息
     *
     * @param query 搜索关键词或问题描述
     * @return 检索到的相关医学知识内容
     */
    @Tool(description = "从医学知识库中检索医学知识。重要：每次对话只能调用一次，调用后必须基于返回内容直接回答用户，绝对不能重复调用。")
    public String searchKnowledge(@ToolParam(description = "搜索关键词或问题描述") String query) {
        if (query == null || query.isBlank()) {
            return "请提供搜索关键词或问题描述";
        }

        try {
            List<RagDocumentService.ParentChunkResult> results = ragDocumentService.hybridSearch(query, 3);

            if (results.isEmpty()) {
                return "未找到相关知识，请尝试换个关键词搜索";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【知识库检索结果】请基于以下内容直接回答用户问题，无需再次检索：\n\n");

            for (int i = 0; i < results.size(); i++) {
                RagDocumentService.ParentChunkResult r = results.get(i);
                sb.append("【").append(i + 1).append("】");
                if (r.headerPath() != null) {
                    sb.append(r.headerPath());
                }
                sb.append("\n");
                sb.append(r.content());
                sb.append("\n\n");
            }

            sb.append("⚠️ 以上是全部检索结果，请直接基于这些内容回答，不要再调用 searchKnowledge 工具。整个信息已经完整了");
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("RAG 检索失败: {}", query, e);
            return "检索失败：" + e.getMessage();
        }
    }
}
