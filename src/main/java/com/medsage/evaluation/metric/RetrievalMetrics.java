package com.medsage.evaluation.metric;

import java.util.List;

/**
 * 检索层指标计算
 *
 * 纯代码计算，不依赖 LLM。
 * 对比标准答案上下文（ground_truth_contexts）和实际检索到的上下文。
 */
public class RetrievalMetrics {

    /**
     * Recall@K：标准答案需要的关键 chunk，在 topK 召回了多少
     *
     * @param groundTruthContexts 标准答案依据的文档片段
     * @param retrievedContexts   实际检索到的文档片段
     * @return 0~1
     */
    public static double recallAtK(List<String> groundTruthContexts, List<String> retrievedContexts) {
        if (groundTruthContexts.isEmpty()) return 1.0;

        long matched = groundTruthContexts.stream()
                .filter(gt -> retrievedContexts.stream().anyMatch(r -> containsSimilar(r, gt)))
                .count();

        return (double) matched / groundTruthContexts.size();
    }

    /**
     * Precision@K：topK 里有多少 chunk 是相关的
     *
     * @param groundTruthContexts 标准答案依据的文档片段
     * @param retrievedContexts   实际检索到的文档片段
     * @return 0~1
     */
    public static double precisionAtK(List<String> groundTruthContexts, List<String> retrievedContexts) {
        if (retrievedContexts.isEmpty()) return 0.0;

        long matched = retrievedContexts.stream()
                .filter(r -> groundTruthContexts.stream().anyMatch(gt -> containsSimilar(r, gt)))
                .count();

        return (double) matched / retrievedContexts.size();
    }

    /**
     * HitRate：至少召回 1 条相关文档
     *
     * @return 1.0 = 命中, 0.0 = 未命中
     */
    public static double hitRate(List<String> groundTruthContexts, List<String> retrievedContexts) {
        if (groundTruthContexts.isEmpty()) return 1.0;

        return groundTruthContexts.stream()
                .anyMatch(gt -> retrievedContexts.stream().anyMatch(r -> containsSimilar(r, gt)))
                ? 1.0 : 0.0;
    }

    /**
     * MRR（Mean Reciprocal Rank）：第一条相关文档的倒数排名
     *
     * @return 0~1，第一条相关文档排在第1位得1分，第2位得0.5分
     */
    public static double mrr(List<String> groundTruthContexts, List<String> retrievedContexts) {
        if (groundTruthContexts.isEmpty() || retrievedContexts.isEmpty()) return 0.0;

        for (int i = 0; i < retrievedContexts.size(); i++) {
            String r = retrievedContexts.get(i);
            boolean isRelevant = groundTruthContexts.stream().anyMatch(gt -> containsSimilar(r, gt));
            if (isRelevant) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 判断两个文本是否相似（包含关系或高重叠）
     * 简化实现：检查是否有超过 30% 的词重叠
     */
    private static boolean containsSimilar(String text, String keyword) {
        if (text == null || keyword == null) return false;

        // 简单包含检查
        if (text.contains(keyword) || keyword.contains(text)) return true;

        // 词重叠检查
        String[] words = keyword.split("\\s+");
        long matched = 0;
        for (String word : words) {
            if (word.length() > 1 && text.contains(word)) {
                matched++;
            }
        }
        return words.length > 0 && (double) matched / words.length > 0.3;
    }
}
