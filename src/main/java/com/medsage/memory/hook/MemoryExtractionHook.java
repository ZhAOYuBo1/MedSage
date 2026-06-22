package com.medsage.memory.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesAgentHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medsage.memory.service.LongTermMemoryService;
import com.medsage.memory.service.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 记忆提取 Hook（AFTER_AGENT）
 *
 * 在对话结束后，调用 LLM 分析对话内容：
 * 1. 提取用户画像信息（姓名、偏好、身体情况）
 * 2. 生成对话摘要
 * 3. 评估重要程度
 */
@Component
@HookPositions(HookPosition.AFTER_AGENT)
public class MemoryExtractionHook extends MessagesAgentHook {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionHook.class);

    private final ChatClient chatClient;
    private final UserProfileService userProfileService;
    private final LongTermMemoryService longTermMemoryService;
    private final ObjectMapper objectMapper;

    public MemoryExtractionHook(ChatModel chatModel,
                                UserProfileService userProfileService,
                                LongTermMemoryService longTermMemoryService) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.userProfileService = userProfileService;
        this.longTermMemoryService = longTermMemoryService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "memory_extraction";
    }

    @Override
    public ReactAgent getAgent() {
        return null; // 不需要
    }

    @Override
    public void setAgent(ReactAgent agent) {
        // 不需要
    }

    @Override
    public int getOrder() {
        return 100; // 最后执行
    }

    @Override
    public AgentCommand afterAgent(List<Message> previousMessages, RunnableConfig config) {
        // 获取用户 ID
        String userId = config.metadata("user_id").map(Object::toString).orElse(null);
        if (userId == null) {
            return new AgentCommand(previousMessages);
        }

        // 提取对话内容
        String conversationText = extractConversationText(previousMessages);
        if (conversationText.isBlank()) {
            return new AgentCommand(previousMessages);
        }

        try {
            // 调用 LLM 分析对话
            String analysisResult = analyzeConversation(conversationText);
            if (analysisResult == null) {
                return new AgentCommand(previousMessages);
            }

            // 解析结果
            Map<String, Object> analysis = objectMapper.readValue(analysisResult, Map.class);

            // 1. 更新用户画像
            Map<String, Object> profileUpdates = (Map<String, Object>) analysis.get("user_profile_updates");
            if (profileUpdates != null && !profileUpdates.isEmpty()) {
                userProfileService.updateProfile(userId, profileUpdates);
                log.info("更新用户画像: userId={}, fields={}", userId, profileUpdates.keySet());
            }

            // 2. 保存对话摘要
            String summary = (String) analysis.get("summary");
            Double importance = ((Number) analysis.get("importance")).doubleValue();
            String memoryType = (String) analysis.get("memory_type");

            if (summary != null && !summary.isBlank()) {
                longTermMemoryService.saveMemory(userId, conversationText, summary, importance, memoryType);
                log.info("保存对话摘要: userId={}, importance={}, type={}", userId, importance, memoryType);
            }

        } catch (Exception e) {
            log.error("记忆提取失败: userId={}", userId, e);
        }

        return new AgentCommand(previousMessages);
    }

    /**
     * 提取对话文本
     */
    private String extractConversationText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof UserMessage userMsg) {
                sb.append("用户: ").append(userMsg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage assistantMsg) {
                sb.append("助手: ").append(assistantMsg.getText()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 调用 LLM 分析对话
     */
    private String analyzeConversation(String conversation) {
        try {
            return chatClient.prompt()
                    .system(MEMORY_EXTRACTION_PROMPT)
                    .user(conversation)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("LLM 分析对话失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 记忆提取 Prompt
     */
    private static final String MEMORY_EXTRACTION_PROMPT = """
            你是一个医疗AI的记忆提取助手。分析以下对话，提取关键信息。

            输出严格的 JSON 格式（不要输出其他内容）：
            {
              "user_profile_updates": {
                "name": "患者姓名（如果提到）",
                "gender": "性别（如果提到）",
                "age": 年龄数字（如果提到）,
                "preferences": {"key": "value"},
                "health_info": {"key": "value或数组"}
              },
              "summary": "对话摘要（一句话概括）",
              "importance": 0.0到1.0之间的重要程度分数,
              "memory_type": "对话类型"
            }

            重要程度评分标准：
            - 1.0：紧急医疗信息（严重过敏、重大病史、急救）
            - 0.8：关键医疗信息（用药、诊断、治疗方案）
            - 0.6：一般医疗信息（症状描述、健康咨询）
            - 0.4：普通对话（问候、闲聊）
            - 0.2：无关紧要的内容

            memory_type 类型：
            - 关键医疗信息
            - 症状咨询
            - 用药记录
            - 健康咨询
            - 普通对话

            提取规则：
            1. 只提取明确提到的信息，不要猜测
            2. 偏好包括：用药偏好、就医偏好、饮食偏好等
            3. 健康信息包括：过敏史、病史、症状、检查结果等
            4. 如果对话中没有值得记忆的内容，importance 设为 0.1，summary 设为"普通对话"
            """;
}
