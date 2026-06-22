package com.medsage.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用次数限制拦截器
 * - 每个工具每个对话（threadId）最多调用指定次数
 * - 超限后直接返回提示，不让工具执行，LLM 继续用已有信息回答
 */
public class ToolCallLimitInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLimitInterceptor.class);

    // key = threadId + ":" + toolName, value = 已调用次数
    private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> toolLimits;
    private final int defaultLimit;

    private ToolCallLimitInterceptor(Map<String, Integer> toolLimits, int defaultLimit) {
        this.toolLimits = toolLimits;
        this.defaultLimit = defaultLimit;
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        String threadId = request.getExecutionContext()
                .flatMap(ToolCallExecutionContext::threadId)
                .orElse("default");
        String key = threadId + ":" + toolName;

        int limit = toolLimits.getOrDefault(toolName, defaultLimit);
        AtomicInteger count = callCounts.computeIfAbsent(key, k -> new AtomicInteger(0));

        if (count.get() >= limit) {
            log.info("工具[{}]已达调用上限({}/{}), 跳过调用", toolName, count.get(), limit);
            String tip = String.format("该工具已调用过%d次，不要再调用此工具", limit);
            return ToolCallResponse.of(request.getToolCallId(), toolName, tip);
        }

        ToolCallResponse response = handler.call(request);
        count.incrementAndGet();
        log.info("工具[{}]调用成功({}/{})", toolName, count.get(), limit);
        return response;
    }

    @Override
    public String getName() {
        return "ToolCallLimit";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, Integer> toolLimits = new ConcurrentHashMap<>();
        private int defaultLimit = 1;

        /**
         * 设置单个工具的调用次数限制
         */
        public Builder limit(String toolName, int maxCalls) {
            toolLimits.put(toolName, maxCalls);
            return this;
        }

        /**
         * 设置默认调用次数限制
         */
        public Builder defaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
            return this;
        }

        public ToolCallLimitInterceptor build() {
            return new ToolCallLimitInterceptor(toolLimits, defaultLimit);
        }
    }
}
