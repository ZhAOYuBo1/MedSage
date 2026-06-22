package com.medsage.evaluation.model;

import java.util.List;

/**
 * 单条测试样本
 *
 * @param question            用户问题
 * @param groundTruth         标准答案
 * @param groundTruthContexts 标准答案依据的文档片段（金标上下文）
 * @param docIds              关联的文档 ID
 * @param tag                 标签（如 "内科/头痛"）
 * @param expectedAgent       期望路由到的科室 Agent（如 "internal_medicine"）
 */
public record EvalSample(
        String question,
        String groundTruth,
        List<String> groundTruthContexts,
        List<String> docIds,
        String tag,
        String expectedAgent
) {}
