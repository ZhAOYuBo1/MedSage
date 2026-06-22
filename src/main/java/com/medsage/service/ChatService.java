package com.medsage.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.medsage.memory.service.ConversationLogService;
import com.medsage.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 聊天服务
 *
 * 流程：
 * 1. 路由 + 调用 Agent（RedisSaver 自动管理对话历史）
 * 2. 记录对话日志
 * 3. 返回 ChatResponse
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final LlmRoutingAgent routerAgent;
    private final ConversationLogService conversationLogService;

    public ChatService(LlmRoutingAgent routerAgent,
                       ConversationLogService conversationLogService) {
        this.routerAgent = routerAgent;
        this.conversationLogService = conversationLogService;
    }

    /**
     * 聊天入口
     */
    public Object chat(String conversationId, String message, String userId) {
        log.info("收到消息: conversationId={}, userId={}, message={}", conversationId, userId, message);

        String response;
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(conversationId)
                    .addMetadata("user_id", userId)
                    .build();

            // LlmRoutingAgent 自动路由到对应科室 Agent
            // RedisSaver 自动加载/保存对话历史
            Optional<OverAllState> result = routerAgent.invoke(message, config);

            if (result.isPresent()) {
                OverAllState state = result.get();
                response = extractResponse(state);
            } else {
                response = "抱歉，处理失败，请重试";
            }

            log.info("Agent响应完成: responseLength={}", response != null ? response.length() : 0);

            if (response == null || response.isEmpty()) {
                response = "抱歉，处理失败，请重试";
            }
        } catch (Exception e) {
            log.error("Agent调用失败", e);
            return ChatResponse.error("抱歉，处理过程中出现错误，请稍后重试。");
        }

        // 记录对话日志
        conversationLogService.log(conversationId, userId, message, response);

        return ChatResponse.success(response, "medical", Map.of());
    }

    /**
     * 从 OverAllState 提取最终回复文本
     * 每个 Agent 的 outputKey 不同，需要遍历查找
     */
    private String extractResponse(OverAllState state) {
        // 所有可能的 outputKey
        String[] outputKeys = {"internal_medicine", "surgery", "pediatrics", "obgyn",
                "psychology", "emergency", "general_doctor"};

        // 从各 Agent 的 outputKey 中提取回复
        for (String key : outputKeys) {
            Object value = state.value(key).orElse(null);
            if (value instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }

        // 兜底：从所有 value 中找 AssistantMessage
        for (Object value : state.data().values()) {
            if (value instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }

        return "抱歉，无法提取回复内容";
    }
}
