package com.medsage.evaluation.model;

import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评估汇总报告
 */
public class EvalReport {

    private final List<EvalResult> results;

    public EvalReport(List<EvalResult> results) {
        this.results = results;
    }

    // ========== 检索层指标 ==========

    public double avgRecall() {
        return results.stream().mapToDouble(EvalResult::getRecallAt5).average().orElse(0);
    }

    public double avgPrecision() {
        return results.stream().mapToDouble(EvalResult::getPrecisionAt5).average().orElse(0);
    }

    public double hitRate() {
        return results.stream().mapToDouble(r -> r.isHit() ? 1.0 : 0.0).average().orElse(0);
    }

    public double avgMrr() {
        return results.stream().mapToDouble(EvalResult::getMrr).average().orElse(0);
    }

    // ========== 生成层指标 ==========

    public double avgFaithfulness() {
        return results.stream().mapToDouble(EvalResult::getFaithfulness).average().orElse(0);
    }

    public double avgAnswerRelevancy() {
        return results.stream().mapToDouble(EvalResult::getAnswerRelevancy).average().orElse(0);
    }

    public double avgAnswerCompleteness() {
        return results.stream().mapToDouble(EvalResult::getAnswerCompleteness).average().orElse(0);
    }

    // ========== 路由指标 ==========

    public double routeAccuracy() {
        return results.stream().mapToDouble(r -> r.isRouteCorrect() ? 1.0 : 0.0).average().orElse(0);
    }

    // ========== 综合指标 ==========

    public double avgTotalScore() {
        return results.stream().mapToDouble(EvalResult::getTotalScore).average().orElse(0);
    }

    public double passRate() {
        return results.stream().mapToDouble(r -> r.isPassed() ? 1.0 : 0.0).average().orElse(0);
    }

    /**
     * 幻觉率：Faithfulness < 0.7 的样本占比
     */
    public double hallucinationRate() {
        long count = results.stream().filter(r -> r.getFaithfulness() < 0.7).count();
        return (double) count / results.size();
    }

    // ========== 性能指标 ==========

    public double avgRetrievalTimeMs() {
        return results.stream().mapToLong(EvalResult::getRetrievalTimeMs).average().orElse(0);
    }

    public double avgTotalTimeMs() {
        return results.stream().mapToLong(EvalResult::getTotalTimeMs).average().orElse(0);
    }

    // ========== 分类统计 ==========

    /**
     * 按 tag 分组统计平均分
     */
    public Map<String, Double> scoreByTag() {
        return results.stream().collect(
                Collectors.groupingBy(
                        r -> r.getSample().tag(),
                        Collectors.averagingDouble(EvalResult::getTotalScore)
                )
        );
    }

    // ========== 输出报告 ==========

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════╗\n");
        sb.append("║            RAG 评估报告                          ║\n");
        sb.append("╠══════════════════════════════════════════════════╣\n");
        sb.append(String.format("║ 样本总数: %-38d ║\n", results.size()));
        sb.append(String.format("║ 通过率:   %-38s ║\n", String.format("%.1f%% (%d/%d)", passRate() * 100, (int)(passRate() * results.size()), results.size())));
        sb.append("╠══════════════════════════════════════════════════╣\n");

        sb.append("║ 【检索层指标】                                   ║\n");
        sb.append(String.format("║   Recall@5:    %-34s ║\n", String.format("%.3f", avgRecall())));
        sb.append(String.format("║   Precision@5: %-34s ║\n", String.format("%.3f", avgPrecision())));
        sb.append(String.format("║   HitRate@5:   %-34s ║\n", String.format("%.3f", hitRate())));
        sb.append(String.format("║   MRR@5:       %-34s ║\n", String.format("%.3f", avgMrr())));
        sb.append("╠══════════════════════════════════════════════════╣\n");

        sb.append("║ 【生成层指标】                                   ║\n");
        sb.append(String.format("║   Faithfulness:     %-29s ║\n", String.format("%.3f", avgFaithfulness())));
        sb.append(String.format("║   Answer Relevancy: %-29s ║\n", String.format("%.3f", avgAnswerRelevancy())));
        sb.append(String.format("║   Completeness:     %-29s ║\n", String.format("%.3f", avgAnswerCompleteness())));
        sb.append(String.format("║   幻觉率:           %-29s ║\n", String.format("%.1f%%", hallucinationRate() * 100)));
        sb.append("╠══════════════════════════════════════════════════╣\n");

        sb.append("║ 【路由指标】                                     ║\n");
        sb.append(String.format("║   路由准确率:   %-34s ║\n", String.format("%.1f%% (%d/%d)", routeAccuracy() * 100, (int)(routeAccuracy() * results.size()), results.size())));
        sb.append("╠══════════════════════════════════════════════════╣\n");

        sb.append("║ 【综合】                                         ║\n");
        sb.append(String.format("║   总分:   %-38s ║\n", String.format("%.3f", avgTotalScore())));
        sb.append("╠══════════════════════════════════════════════════╣\n");

        sb.append("║ 【性能】                                         ║\n");
        sb.append(String.format("║   平均检索耗时: %-32s ║\n", String.format("%.0f ms", avgRetrievalTimeMs())));
        sb.append(String.format("║   平均总耗时:   %-32s ║\n", String.format("%.0f ms", avgTotalTimeMs())));
        sb.append("╠══════════════════════════════════════════════════╣\n");

        sb.append("║ 【分类统计】                                     ║\n");
        scoreByTag().forEach((tag, score) ->
                sb.append(String.format("║   %-15s %-32s ║\n", tag, String.format("%.3f", score)))
        );

        sb.append("╚══════════════════════════════════════════════════╝\n");

        return sb.toString();
    }

    public List<EvalResult> getResults() { return results; }
}
