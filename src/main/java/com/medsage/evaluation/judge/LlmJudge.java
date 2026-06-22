package com.medsage.evaluation.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * LLM-as-Judge 评分器
 *
 * 用 LLM 评估 RAG 答案质量，输出 0~1 分数。
 * 温度强制设为 0，保证评分稳定。
 */
@Component
public class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);
    private final ChatClient chatClient;

    public LlmJudge(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 评估 Faithfulness（忠实度）
     * 判断答案是否都能在检索文档找到依据，无编造
     */
    public double evaluateFaithfulness(String question, String answer, String context) {
        String prompt = """
                你是一个严格的评估专家。请判断以下答案是否完全基于提供的上下文。

                用户问题: %s

                检索到的上下文:
                %s

                AI答案:
                %s

                评分标准:
                - 1.0: 答案所有内容都能在上下文找到依据
                - 0.7-0.9: 答案大部分基于上下文，有少量推断
                - 0.4-0.6: 答案部分基于上下文，部分编造
                - 0.0-0.3: 答案大部分是编造的

                请只输出一个0到1之间的数字分数，不要解释。
                """.formatted(question, context, answer);

        return callJudge(prompt);
    }

    /**
     * 评估 Answer Relevancy（答案相关性）
     * 判断答案是否精准解决用户提问
     */
    public double evaluateAnswerRelevancy(String question, String answer) {
        String prompt = """
                你是一个严格的评估专家。请判断以下答案是否精准回答了用户的问题。

                用户问题: %s

                AI答案:
                %s

                评分标准:
                - 1.0: 完全回答了用户问题，精准有用
                - 0.7-0.9: 基本回答了问题，但不够精准或有冗余
                - 0.4-0.6: 部分回答了问题，但有跑题
                - 0.0-0.3: 答非所问或完全跑题

                请只输出一个0到1之间的数字分数，不要解释。
                """.formatted(question, answer);

        return callJudge(prompt);
    }

    /**
     * 评估 Answer Completeness（完整性）
     * 判断答案是否覆盖用户问题全部提问点
     */
    public double evaluateCompleteness(String question, String answer, String groundTruth) {
        String prompt = """
                你是一个严格的评估专家。请判断以下答案是否完整覆盖了用户问题的所有要点。

                用户问题: %s

                标准答案要点:
                %s

                AI答案:
                %s

                评分标准:
                - 1.0: 覆盖了所有关键要点
                - 0.7-0.9: 覆盖了大部分要点，遗漏1-2个次要信息
                - 0.4-0.6: 覆盖了部分要点，遗漏重要信息
                - 0.0-0.3: 严重遗漏，只覆盖少量要点

                请只输出一个0到1之间的数字分数，不要解释。
                """.formatted(question, groundTruth, answer);

        return callJudge(prompt);
    }

    /**
     * 调用 LLM 评分，提取数字分数
     */
    private double callJudge(String prompt) {
        try {
            String response = chatClient.prompt()
                    .system("你是一个严格的评估专家，只输出数字分数。")
                    .user(prompt)
                    .call()
                    .content();

            return parseScore(response);
        } catch (Exception e) {
            log.warn("LLM 评分失败: {}", e.getMessage());
            return 0.5;  // 默认中等分数
        }
    }

    /**
     * 从 LLM 响应中提取数字分数
     */
    private double parseScore(String response) {
        if (response == null) return 0.5;

        // 提取数字（支持 0.85、85%、85 等格式）
        String cleaned = response.replaceAll("[^0-9.]", "").trim();
        if (cleaned.isEmpty()) return 0.5;

        try {
            double score = Double.parseDouble(cleaned);
            // 如果是百分制，转换为 0-1
            if (score > 1.0) score = score / 100.0;
            return Math.max(0.0, Math.min(1.0, score));
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }
}
