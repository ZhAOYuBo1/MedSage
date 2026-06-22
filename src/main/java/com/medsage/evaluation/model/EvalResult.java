package com.medsage.evaluation.model;

import java.util.List;

/**
 * 单条评估结果
 */
public class EvalResult {

    private final EvalSample sample;
    private final String agentAnswer;
    private final List<String> retrievedContexts;

    // 检索层指标
    private double recallAt5;
    private double precisionAt5;
    private boolean hit;
    private double mrr;

    // 生成层指标
    private double faithfulness;
    private double answerRelevancy;
    private double answerCompleteness;

    // 路由指标
    private String actualAgent;
    private boolean routeCorrect;

    // 性能指标
    private long retrievalTimeMs;
    private long totalTimeMs;

    public EvalResult(EvalSample sample, String agentAnswer, List<String> retrievedContexts) {
        this.sample = sample;
        this.agentAnswer = agentAnswer;
        this.retrievedContexts = retrievedContexts;
    }

    // ========== 综合得分 ==========

    /**
     * 检索层综合分（四项均值）
     */
    public double getRetrievalScore() {
        return (recallAt5 + precisionAt5 + (hit ? 1.0 : 0.0) + mrr) / 4.0;
    }

    /**
     * 生成层综合分（三项均值）
     */
    public double getGenerationScore() {
        return (faithfulness + answerRelevancy + answerCompleteness) / 3.0;
    }

    /**
     * 总分（检索 40% + 生成 60%）
     */
    public double getTotalScore() {
        return getRetrievalScore() * 0.4 + getGenerationScore() * 0.6;
    }

    /**
     * 是否通过（总分 >= 0.7）
     */
    public boolean isPassed() {
        return getTotalScore() >= 0.7;
    }

    // ========== Getters & Setters ==========

    public EvalSample getSample() { return sample; }
    public String getAgentAnswer() { return agentAnswer; }
    public List<String> getRetrievedContexts() { return retrievedContexts; }

    public double getRecallAt5() { return recallAt5; }
    public void setRecallAt5(double recallAt5) { this.recallAt5 = recallAt5; }

    public double getPrecisionAt5() { return precisionAt5; }
    public void setPrecisionAt5(double precisionAt5) { this.precisionAt5 = precisionAt5; }

    public boolean isHit() { return hit; }
    public void setHit(boolean hit) { this.hit = hit; }

    public double getMrr() { return mrr; }
    public void setMrr(double mrr) { this.mrr = mrr; }

    public double getFaithfulness() { return faithfulness; }
    public void setFaithfulness(double faithfulness) { this.faithfulness = faithfulness; }

    public double getAnswerRelevancy() { return answerRelevancy; }
    public void setAnswerRelevancy(double answerRelevancy) { this.answerRelevancy = answerRelevancy; }

    public double getAnswerCompleteness() { return answerCompleteness; }
    public void setAnswerCompleteness(double answerCompleteness) { this.answerCompleteness = answerCompleteness; }

    public long getRetrievalTimeMs() { return retrievalTimeMs; }
    public void setRetrievalTimeMs(long retrievalTimeMs) { this.retrievalTimeMs = retrievalTimeMs; }

    public long getTotalTimeMs() { return totalTimeMs; }
    public void setTotalTimeMs(long totalTimeMs) { this.totalTimeMs = totalTimeMs; }

    public String getActualAgent() { return actualAgent; }
    public void setActualAgent(String actualAgent) { this.actualAgent = actualAgent; }

    public boolean isRouteCorrect() { return routeCorrect; }
    public void setRouteCorrect(boolean routeCorrect) { this.routeCorrect = routeCorrect; }
}
