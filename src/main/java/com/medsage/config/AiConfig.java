package com.medsage.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 配置 - ChatMemory 和多模态 ChatClient
 */
@Configuration
public class AiConfig {

    /**
     * 聊天记忆 - 使用滑动窗口策略，默认保留最近 20 条消息
     * 后续可替换为 JDBC 或 Redis 实现的 ChatMemoryRepository
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    /**
     * 多模态 ChatClient（用于图片识别）
     * 使用 qwen-vl-max 模型
     */
    @Bean
    public ChatClient multimodalChatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-vl-max")
                        .build())
                .build();
    }
}
