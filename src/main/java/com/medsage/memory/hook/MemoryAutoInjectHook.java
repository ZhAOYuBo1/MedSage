package com.medsage.memory.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesAgentHook;
import com.medsage.memory.config.MemoryConfig;
import com.medsage.memory.service.LongTermMemoryService;
import com.medsage.memory.service.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆自动注入 Hook（BEFORE_AGENT）
 * - 在 Agent 开始前自动注入用户画像和相关记忆
 * - 使用 BEFORE_AGENT 位置，确保每次对话只执行一次
 */
@Component
@HookPositions(HookPosition.BEFORE_AGENT)
public class MemoryAutoInjectHook extends MessagesAgentHook {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoInjectHook.class);

    private final LongTermMemoryService longTermMemoryService;
    private final UserProfileService userProfileService;
    private final MemoryConfig memoryConfig;

    public MemoryAutoInjectHook(LongTermMemoryService longTermMemoryService,
                                UserProfileService userProfileService,
                                MemoryConfig memoryConfig) {
        this.longTermMemoryService = longTermMemoryService;
        this.userProfileService = userProfileService;
        this.memoryConfig = memoryConfig;
    }

    @Override
    public String getName() {
        return "memory_auto_inject";
    }

    @Override
    public ReactAgent getAgent() {
        return null;
    }

    @Override
    public void setAgent(ReactAgent agent) {
        // 不需要
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public AgentCommand beforeAgent(List<Message> previousMessages, RunnableConfig config) {
        if (!memoryConfig.isAutoRecallEnabled()) {
            return new AgentCommand(previousMessages);
        }

        // 获取用户 ID
        String userId = config.metadata("user_id").map(Object::toString).orElse(null);
        if (userId == null) {
            return new AgentCommand(previousMessages);
        }

        // 获取当前用户消息（用于语义检索）
        String lastUserMessage = getLastUserMessage(previousMessages);
        if (lastUserMessage == null || lastUserMessage.isBlank()) {
            return new AgentCommand(previousMessages);
        }

        try {
            StringBuilder contextBuilder = new StringBuilder();

            // 1. 注入用户画像
            String profileText = userProfileService.getProfileAsText(userId);
            if (profileText != null) {
                contextBuilder.append(profileText).append("\n");
            }

            // 2. 检索并注入相关记忆
            List<Map<String, Object>> memories = longTermMemoryService.searchMemories(
                    userId, lastUserMessage, memoryConfig.getTopK());

            if (!memories.isEmpty()) {
                contextBuilder.append("\n【相关记忆】\n");
                for (Map<String, Object> mem : memories) {
                    String summary = (String) mem.getOrDefault("summary", mem.get("content"));
                    double importance = ((Number) mem.getOrDefault("importance", 0.5)).doubleValue();
                    contextBuilder.append(String.format("- [重要%.1f] %s\n", importance, summary));
                }
            }

            // 注入到 SystemMessage
            if (contextBuilder.length() > 0) {
                List<Message> newMessages = injectToSystemMessage(previousMessages, contextBuilder.toString());
                log.debug("注入记忆上下文: userId={}", userId);
                return new AgentCommand(newMessages);
            }

            return new AgentCommand(previousMessages);
        } catch (Exception e) {
            log.error("注入记忆失败: userId={}", userId, e);
            return new AgentCommand(previousMessages);
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
     * 注入到 SystemMessage
     */
    private List<Message> injectToSystemMessage(List<Message> messages, String context) {
        List<Message> newMessages = new ArrayList<>();
        boolean injected = false;

        for (Message msg : messages) {
            if (msg instanceof SystemMessage systemMessage && !injected) {
                newMessages.add(new SystemMessage(systemMessage.getText() + "\n\n" + context));
                injected = true;
            } else {
                newMessages.add(msg);
            }
        }

        if (!injected) {
            newMessages.add(0, new SystemMessage(context.trim()));
        }

        return newMessages;
    }
}
