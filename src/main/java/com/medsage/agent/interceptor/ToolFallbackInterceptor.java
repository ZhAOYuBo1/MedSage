package com.medsage.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ToolFallbackInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolFallbackInterceptor.class);

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        try {
            return handler.call(request);
        } catch (Exception e) {
            String toolName = request.getToolName();
            log.error("工具[{}]调用异常", toolName, e);
            String tip = String.format("工具【%s】暂时无法使用，请基于已有信息回答，不要再调用该工具", toolName);
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), tip);
        }
    }

    @Override
    public String getName() {
        return "CustomToolFallback";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        public ToolFallbackInterceptor build() {
            return new ToolFallbackInterceptor();
        }
    }
}