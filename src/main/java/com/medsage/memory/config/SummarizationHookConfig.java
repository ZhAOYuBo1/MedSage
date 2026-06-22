package com.medsage.memory.config;

import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话压缩 Hook 配置
 * - 当对话 token 超过阈值时，自动压缩历史消息为摘要
 * - 保留最近 N 条消息 + 第一条用户消息
 */
@Configuration
public class SummarizationHookConfig {

    private static final String MEDICAL_SUMMARY_PROMPT = """
            请压缩以下医疗问诊对话，严格遵循规则：
            必须原样完整保留，不得概括删减：
            - 药物过敏信息（如：青霉素过敏）
            - 当前/长期用药记录
            - 既往病史、手术史、确诊年份
            - 医生给出的正式诊断结论
            - 用户问诊偏好（偏好中医/拒绝输液等）
            
            可压缩概括为短句：
            - 症状细节描述、普通健康问答
            直接丢弃：寒暄、重复表述、无意义语气词
            
            输出固定格式：
            [关键事实]
            - 逐条列出所有强制保留医疗信息
            [对话摘要]
            2-3句话概括整体问诊流程，只保留核心逻辑
            待压缩对话：
            %s
            """;

    /**
     * 共享的 SummarizationHook
     * - maxTokensBeforeSummary: 8000 token 时触发压缩
     * - messagesToKeep: 保留最近 20 条消息
     * - keepFirstUserMessage: 保留第一条用户消息（便于理解上下文）
     */
    @Bean
    public SummarizationHook summarizationHook(ChatModel chatModel) {
        return SummarizationHook.builder()
                .summaryPrompt(MEDICAL_SUMMARY_PROMPT)
                .model(chatModel)
                .maxTokensBeforeSummary(140000)
                .messagesToKeep(3)
                .summaryPrefix("## 历史对话精简摘要")
                .tokenCounter(TokenCounter.approximateMsgCounter())
                .keepFirstUserMessage(true)
                .build();
    }
}
