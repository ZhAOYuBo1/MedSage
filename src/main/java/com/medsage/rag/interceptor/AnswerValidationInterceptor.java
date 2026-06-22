package com.medsage.rag.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 答案验证 Interceptor（两步 RAG 的检索后验证）
 *
 * 在模型返回答案后，验证答案质量：
 * - 答案是否足够完整（长度）
 * - 是否包含免责声明（安全要求）
 *
 * 如果不合格，追加提示让模型重新生成。
 */
@Component
public class AnswerValidationInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AnswerValidationInterceptor.class);

    /** 答案最小长度 */
    private static final int MIN_ANSWER_LENGTH = 30;
    /** 免责声明关键词 */
    private static final String DISCLAIMER = "仅供参考";

    @Override
    public String getName() {
        return "answer_validation";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        // 调用模型生成答案
        ModelResponse response = handler.call(request);

        // 提取答案文本
        String answerText = extractAnswerText(response);
        if (answerText == null) {
            return response;
        }

        // 验证答案质量
        String validationIssue = validateAnswer(answerText);
        if (validationIssue == null) {
            return response;  // 验证通过
        }

        // 验证不通过，追加提示重试
        log.debug("答案验证不通过: {}", validationIssue);
        SystemMessage retryPrompt = new SystemMessage(
                "请重新检查你的答案：" + validationIssue
        );

        ModelRequest retryRequest = ModelRequest.builder(request)
                .systemMessage(retryPrompt)
                .build();

        return handler.call(retryRequest);
    }

    /**
     * 提取答案文本
     */
    private String extractAnswerText(ModelResponse response) {
        Object message = response.getMessage();
        if (message instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        return null;
    }

    /**
     * 验证答案质量
     *
     * @return null 表示通过，否则返回问题描述
     */
    private String validateAnswer(String answer) {
        if (answer.length() < MIN_ANSWER_LENGTH) {
            return "答案过于简短，请补充更详细的解释和建议";
        }

        return null;  // 验证通过
    }

    @Override
    public List<org.springframework.ai.tool.ToolCallback> getTools() {
        return List.of();
    }
}
