package com.medsage.model;

import java.util.Map;

/**
 * 统一的聊天响应 schema
 * 所有返回都遵循这个格式（追问除外）
 */
public record ChatResponse(
        String response,           // 回复内容（追问时为空）
        String clarifyQuestion,    // 追问问题（不需要追问时为 null）
        String intent,             // 识别的意图
        Map<String, Object> slots, // 槽位信息
        boolean needsClarification // 是否需要追问
) {
    /**
     * 正常响应（不需要追问）
     */
    public static ChatResponse success(String response, String intent, Map<String, Object> slots) {
        return new ChatResponse(response, null, intent, slots, false);
    }

    /**
     * 追问响应（直接返回问题字符串，不包装 schema）
     */
    public static ChatResponse clarify(String clarifyQuestion, String intent, Map<String, Object> slots) {
        return new ChatResponse("", clarifyQuestion, intent, slots, true);
    }

    /**
     * 错误响应
     */
    public static ChatResponse error(String message) {
        return new ChatResponse(message, null, "unknown", Map.of(), false);
    }
}
