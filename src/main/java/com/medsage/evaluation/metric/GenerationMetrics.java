package com.medsage.evaluation.metric;

import com.medsage.evaluation.judge.LlmJudge;
import org.springframework.stereotype.Component;

/**
 * 生成层指标计算
 *
 * 使用 LLM-as-Judge 评估答案质量。
 */
@Component
public class GenerationMetrics {

    private final LlmJudge judge;

    public GenerationMetrics(LlmJudge judge) {
        this.judge = judge;
    }

    /**
     * Faithfulness（忠实度）：答案是否基于检索文档，无编造
     */
    public double faithfulness(String question, String answer, String context) {
        return judge.evaluateFaithfulness(question, answer, context);
    }

    /**
     * Answer Relevancy（答案相关性）：答案是否回答了用户问题
     */
    public double answerRelevancy(String question, String answer) {
        return judge.evaluateAnswerRelevancy(question, answer);
    }

    /**
     * Answer Completeness（完整性）：答案是否覆盖所有要点
     */
    public double answerCompleteness(String question, String answer, String groundTruth) {
        return judge.evaluateCompleteness(question, answer, groundTruth);
    }
}
