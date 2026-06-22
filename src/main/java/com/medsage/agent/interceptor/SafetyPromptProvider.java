package com.medsage.agent.interceptor;

/**
 * 安全提示词提供者
 * 为 Agent 提供统一的安全防护提示词
 */
public class SafetyPromptProvider {

    /**
     * 获取循环防护提示词
     */
    public static String getLoopPreventionPrompt() {
        return """
                尽量从RAG检索的内容回答，如果发现信息缺少严重，请着重提醒用户可信度，并且回答应专业精简，着重回答用户问题，减少废话
                # Loop Prevention (循环防护规则)
                1. 最多调用 5 次工具，达到上限后必须基于已有信息回答
                2. 当你认为已经收集到足够信息时，直接给出最终回答，不要再调用工具
                3. 不要重复调用同一个工具获取相同信息
                4. 如果工具返回错误或无法获取信息，直接告知用户，不要反复尝试
                5. 避免重复分析相同的内容，每次推理应该推进问题的解决
                """;
    }

    /**
     * 获取自动结束提示词
     */
    public static String getEarlyStopPrompt() {
        return """

                # Auto-Stop Rules (自动结束规则)
                当以下情况时，立即给出最终回答，停止调用工具：
                - 已经获得足够的信息来回答用户问题
                - 工具返回的结果已经足够清晰
                - 连续两次工具调用都没有获取到新信息
                - 用户的问题比较简单，不需要多次工具调用
                """;
    }

    /**
     * 获取完整的安全提示词
     */
    public static String getFullSafetyPrompt() {
        return getLoopPreventionPrompt() + getEarlyStopPrompt();
    }
}
