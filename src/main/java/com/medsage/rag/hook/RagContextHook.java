package com.medsage.rag.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesAgentHook;
import com.medsage.rag.RagDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 上下文注入 Hook（两步 RAG 的检索步骤）
 *
 * 在 Agent 开始前，自动从知识库检索相关文档，注入到消息上下文中。
 * 与 RagSearchTool（Agentic RAG）配合使用：
 * - Hook：自动注入摘要级上下文（保证有知识可用）
 * - 工具：Agent 主动调用查详情（按需深入）
 *
 * 使用 BEFORE_AGENT 位置，确保每次对话只执行一次（而非每次模型调用都执行）。
 */
@Component
@HookPositions(HookPosition.BEFORE_AGENT)
public class RagContextHook extends MessagesAgentHook {

    private static final Logger log = LoggerFactory.getLogger(RagContextHook.class);
    private static final int TOP_K = 3;

    private ReactAgent agent;
    private final RagDocumentService ragDocumentService;

    public RagContextHook(RagDocumentService ragDocumentService) {
        this.ragDocumentService = ragDocumentService;
    }

    @Override
    public String getName() {
        return "rag_context_hook";
    }

    @Override
    public ReactAgent getAgent() {
        return agent;
    }

    @Override
    public void setAgent(ReactAgent agent) {
        this.agent = agent;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public AgentCommand beforeAgent(List<Message> previousMessages, RunnableConfig config) {
        // 提取用户问题
        String userQuestion = extractUserQuestion(previousMessages);
        if (userQuestion == null || userQuestion.isBlank()) {
            return new AgentCommand(previousMessages);
        }

        try {
            // 检索相关文档
            List<RagDocumentService.ParentChunkResult> results =
                    ragDocumentService.hybridSearch(userQuestion, TOP_K);

            if (results.isEmpty()) {
                return new AgentCommand(previousMessages);
            }

            // 构建上下文
            String context = buildContext(results);

            // 注入到消息列表
            List<Message> enhancedMessages = injectContext(previousMessages, context);

            log.debug("RAG 上下文注入: query={}, 文档数={}", userQuestion, results.size());
            return new AgentCommand(enhancedMessages);
        } catch (Exception e) {
            log.error("RAG 上下文注入失败", e);
            return new AgentCommand(previousMessages);
        }
    }

    /**
     * 提取最后一条用户消息
     */
    private String extractUserQuestion(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return null;
    }

    /**
     * 构建检索上下文
     */
    private String buildContext(List<RagDocumentService.ParentChunkResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RagDocumentService.ParentChunkResult r = results.get(i);
            sb.append("【").append(i + 1).append("】");
            if (r.headerPath() != null) {
                sb.append(r.headerPath());
            }
            sb.append("\n");
            // 限制每个文档长度，避免 token 过多
            String content = r.content();
            if (content.length() > 800) {
                content = content.substring(0, 800) + "...";
            }
            sb.append(content);
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 注入上下文到消息列表
     */
    private List<Message> injectContext(List<Message> messages, String context) {
        List<Message> enhancedMessages = new ArrayList<>();
        boolean injected = false;

        for (Message msg : messages) {
            if (msg instanceof SystemMessage systemMsg && !injected) {
                // 在第一个 SystemMessage 后追加上下文
                String enhancedText = systemMsg.getText() +
                        "\n\n以下是知识库中的相关参考资料：\n" + context;
                enhancedMessages.add(new SystemMessage(enhancedText));
                injected = true;
            } else {
                enhancedMessages.add(msg);
            }
        }

        // 如果没有 SystemMessage，创建一个
        if (!injected) {
            enhancedMessages.add(0, new SystemMessage(
                    "以下是知识库中的相关参考资料：\n" + context));
        }

        return enhancedMessages;
    }
}
