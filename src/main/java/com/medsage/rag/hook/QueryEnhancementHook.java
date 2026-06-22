package com.medsage.rag.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesAgentHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询增强 Hook（两步 RAG 的检索前优化）
 *
 * 在 Agent 开始前，用 LLM 把用户口语化的问题改写成更适合医学文档搜索的形式。
 * 与 RagSearchTool 配合：Hook 负责增强原始查询，Tool 负责执行检索。
 *
 * 使用 BEFORE_AGENT 位置，确保每次对话只执行一次（而非每次模型调用都执行）。
 *
 * 示例：
 * - "我肚子疼咋回事" → "腹痛 常见病因 消化系统"
 * - "孩子老是咳嗽" → "小儿反复咳嗽 呼吸道感染 过敏"
 */
@Component
@HookPositions(HookPosition.BEFORE_AGENT)
public class QueryEnhancementHook extends MessagesAgentHook {

    private static final Logger log = LoggerFactory.getLogger(QueryEnhancementHook.class);

    private ReactAgent agent;
    private final ChatClient chatClient;

    public QueryEnhancementHook(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String getName() {
        return "query_enhancement";
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
        // 找到最后一条用户消息
        String userQuery = getLastUserMessage(previousMessages);
        if (userQuery == null || userQuery.isBlank()) {
            return new AgentCommand(previousMessages);
        }

        // 查询增强
        String enhancedQuery = enhanceQuery(userQuery);
        if (enhancedQuery == null || enhancedQuery.equals(userQuery)) {
            return new AgentCommand(previousMessages);
        }

        // 替换最后一条用户消息
        List<Message> newMessages = replaceLastUserMessage(previousMessages, enhancedQuery);
        log.debug("查询增强: {} → {}", userQuery, enhancedQuery);
        return new AgentCommand(newMessages);
    }

    /**
     * 用 LLM 增强查询
     */
    private String enhanceQuery(String query) {
        try {
            return chatClient.prompt()
                    .system("你是一个医学查询优化助手。把用户的问题改写成更适合医学文档搜索的形式。" +
                            "规则：\n" +
                            "1. 保留核心医学术语和症状描述\n" +
                            "2. 去除口语化表达（咋回事、咋办）\n" +
                            "3. 补充相关医学同义词\n" +
                            "4. 只输出改写后的查询，不要解释，不要加标点")
                    .user(query)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("查询增强失败，使用原始查询: {}", e.getMessage());
            return query;
        }
    }

    /**
     * 获取最后一条用户消息
     */
    private String getLastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMsg) {
                return userMsg.getText();
            }
        }
        return null;
    }

    /**
     * 替换最后一条用户消息
     */
    private List<Message> replaceLastUserMessage(List<Message> messages, String newContent) {
        List<Message> newMessages = new ArrayList<>(messages);
        for (int i = newMessages.size() - 1; i >= 0; i--) {
            if (newMessages.get(i) instanceof UserMessage) {
                newMessages.set(i, new UserMessage(newContent));
                break;
            }
        }
        return newMessages;
    }
}
