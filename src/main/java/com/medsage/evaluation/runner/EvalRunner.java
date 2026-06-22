package com.medsage.evaluation.runner;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medsage.evaluation.metric.GenerationMetrics;
import com.medsage.evaluation.metric.RetrievalMetrics;
import com.medsage.evaluation.model.EvalReport;
import com.medsage.evaluation.model.EvalResult;
import com.medsage.evaluation.model.EvalSample;
import com.medsage.rag.RagDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 评估执行器（测路由 Agent）
 *
 * 流程：
 * 1. 加载测试数据集
 * 2. 对每条样本：调用路由 Agent → 检测实际路由科室 → 计算检索/生成指标
 * 3. 汇总报告
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final ReactAgent generalDoctorAgent;
    private final RagDocumentService ragDocumentService;
    private final GenerationMetrics generationMetrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 科室 Agent 的 outputKey 映射
    private static final Map<String, String> AGENT_OUTPUT_KEYS = Map.of(
            "internal_medicine", "internal_medicine",
            "surgery", "surgery",
            "pediatrics", "pediatrics",
            "obgyn", "obgyn",
            "psychology", "psychology",
            "emergency", "emergency"
    );

    public EvalRunner(
            @org.springframework.beans.factory.annotation.Qualifier("generalDoctorAgent") ReactAgent generalDoctorAgent,
            RagDocumentService ragDocumentService,
            GenerationMetrics generationMetrics) {
        this.generalDoctorAgent = generalDoctorAgent;
        this.ragDocumentService = ragDocumentService;
        this.generationMetrics = generationMetrics;
    }

    /**
     * 加载测试数据集
     */
    public List<EvalSample> loadDataset(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<List<EvalSample>>() {});
        }
    }

    /**
     * 评估单条样本
     */
    public EvalResult evaluateSample(EvalSample sample) {
        log.info("评估: {}", sample.question());

        // 1. 记录开始时间
        long startTime = System.currentTimeMillis();

        // 2. 检索相关文档
        long retrievalStart = System.currentTimeMillis();
        List<RagDocumentService.ParentChunkResult> retrievalResults =
                ragDocumentService.hybridSearch(sample.question(), 5);
        long retrievalTime = System.currentTimeMillis() - retrievalStart;

        List<String> retrievedContexts = retrievalResults.stream()
                .map(RagDocumentService.ParentChunkResult::content)
                .collect(Collectors.toList());

        // 3. 调用路由 Agent 获取回答
        String agentAnswer = "";
        String actualAgent = "unknown";
        try {
            var resultOpt = generalDoctorAgent.invoke(sample.question());

            if (resultOpt.isPresent()) {
                OverAllState state = resultOpt.get();

                // 从 state 中检测实际路由到哪个科室
                actualAgent = detectActualAgent(state);

                // 提取最终回答
                agentAnswer = extractAnswer(state);
            }
        } catch (Exception e) {
            log.warn("Agent 调用失败: {}", e.getMessage());
        }
        long totalTime = System.currentTimeMillis() - startTime;

        // 4. 构建评估结果
        EvalResult evalResult = new EvalResult(sample, agentAnswer, retrievedContexts);
        evalResult.setRetrievalTimeMs(retrievalTime);
        evalResult.setTotalTimeMs(totalTime);

        // 5. 路由准确率
        evalResult.setActualAgent(actualAgent);
        evalResult.setRouteCorrect(actualAgent.equals(sample.expectedAgent()));

        // 6. 计算检索层指标
        evalResult.setRecallAt5(RetrievalMetrics.recallAtK(sample.groundTruthContexts(), retrievedContexts));
        evalResult.setPrecisionAt5(RetrievalMetrics.precisionAtK(sample.groundTruthContexts(), retrievedContexts));
        evalResult.setHit(RetrievalMetrics.hitRate(sample.groundTruthContexts(), retrievedContexts) > 0);
        evalResult.setMrr(RetrievalMetrics.mrr(sample.groundTruthContexts(), retrievedContexts));

        // 7. 计算生成层指标
        String context = String.join("\n\n", retrievedContexts);
        evalResult.setFaithfulness(generationMetrics.faithfulness(sample.question(), agentAnswer, context));
        evalResult.setAnswerRelevancy(generationMetrics.answerRelevancy(sample.question(), agentAnswer));
        evalResult.setAnswerCompleteness(generationMetrics.answerCompleteness(sample.question(), agentAnswer, sample.groundTruth()));

        log.info("评估完成: route={}, expected={}, score={}",
                actualAgent, sample.expectedAgent(),
                String.format("%.2f", evalResult.getTotalScore()));

        return evalResult;
    }

    /**
     * 从 state 中检测实际路由到哪个科室
     * 通过检查哪个科室的 outputKey 有内容来判断
     */
    private String detectActualAgent(OverAllState state) {
        for (Map.Entry<String, String> entry : AGENT_OUTPUT_KEYS.entrySet()) {
            String agentName = entry.getKey();
            String outputKey = entry.getValue();

            Object value = state.value(outputKey).orElse(null);
            if (value != null && !value.toString().isEmpty()) {
                return agentName;
            }
        }
        return "general_doctor";
    }

    /**
     * 从 state 中提取最终回答
     */
    private String extractAnswer(OverAllState state) {
        // 尝试从各科室 outputKey 中提取
        for (String outputKey : AGENT_OUTPUT_KEYS.values()) {
            Object value = state.value(outputKey).orElse(null);
            if (value != null && !value.toString().isEmpty()) {
                return value.toString();
            }
        }
        // 如果都没有，返回空
        return "";
    }

    /**
     * 运行完整评估
     */
    public EvalReport run(String datasetPath) throws IOException {
        log.info("开始 RAG 评估...");

        // 1. 加载数据集
        List<EvalSample> dataset = loadDataset(datasetPath);
        log.info("加载 {} 条测试样本", dataset.size());

        // 2. 逐条评估
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < dataset.size(); i++) {
            log.info("进度: {}/{}", i + 1, dataset.size());
            try {
                EvalResult result = evaluateSample(dataset.get(i));
                results.add(result);
            } catch (Exception e) {
                log.error("评估失败: {}", dataset.get(i).question(), e);
            }
        }

        // 3. 生成报告
        EvalReport report = new EvalReport(results);
        log.info("评估完成\n{}", report.format());

        return report;
    }
}
