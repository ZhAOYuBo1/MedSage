package com.medsage.memory.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.medsage.memory.service.LongTermMemoryService;
import com.medsage.memory.service.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * 记忆工具配置
 * - updateUserProfile: 更新用户画像（覆盖模式）
 * - searchMemories: 搜索对话记忆
 * - deleteMemory: 删除对话记忆
 */
@Configuration
public class MemoryTools {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);

    /**
     * 更新用户画像工具（覆盖模式）
     * - Agent 发现用户提到个人信息或偏好变化时调用
     * - 传入的字段直接覆盖，不传的字段保持不变
     */
    @Bean
    public ToolCallback updateUserProfileTool(UserProfileService userProfileService) {
        BiFunction<UpdateProfileRequest, ToolContext, MemoryResponse> function = (request, context) -> {
            RunnableConfig config = ToolContextHelper.getConfig(context).orElse(null);
            String userId = config != null ? config.metadata("user_id").map(Object::toString).orElse("unknown") : "unknown";

            Map<String, Object> updates = new java.util.HashMap<>();
            if (request.name() != null) updates.put("name", request.name());
            if (request.gender() != null) updates.put("gender", request.gender());
            if (request.age() != null) updates.put("age", request.age());
            if (request.preferences() != null) updates.put("preferences", request.preferences());
            if (request.healthInfo() != null) updates.put("health_info", request.healthInfo());

            userProfileService.updateProfile(userId, updates);

            log.info("Agent 更新用户画像: userId={}, fields={}", userId, updates.keySet());
            return new MemoryResponse("已更新患者画像");
        };

        return FunctionToolCallback.builder("updateUserProfile", function)
                .description("更新患者画像（姓名、性别、年龄、偏好、健康信息）。" +
                        "当患者提到个人信息或修改之前的偏好时调用。" +
                        "传入的字段会覆盖原有值，不传的字段保持不变。")
                .inputType(UpdateProfileRequest.class)
                .build();
    }

    /**
     * 搜索对话记忆工具
     */
    @Bean
    public ToolCallback searchMemoriesTool(LongTermMemoryService longTermMemoryService) {
        BiFunction<SearchMemoryRequest, ToolContext, MemoryResponse> function = (request, context) -> {
            RunnableConfig config = ToolContextHelper.getConfig(context).orElse(null);
            String userId = config != null ? config.metadata("user_id").map(Object::toString).orElse("unknown") : "unknown";

            var memories = longTermMemoryService.searchMemories(userId, request.query(), 5);
            if (memories.isEmpty()) {
                return new MemoryResponse("未找到相关记忆");
            }

            StringBuilder sb = new StringBuilder("找到以下相关记忆：\n");
            for (var mem : memories) {
                sb.append(String.format("- [id=%s] [重要%.1f] %s\n",
                        mem.get("id"),
                        mem.getOrDefault("importance", 0.5),
                        mem.getOrDefault("summary", mem.get("content"))));
            }
            return new MemoryResponse(sb.toString());
        };

        return FunctionToolCallback.builder("searchMemories", function)
                .description("搜索患者的医疗记忆（过敏史、用药情况、病史等）。返回结果包含记忆ID，可用于删除。")
                .inputType(SearchMemoryRequest.class)
                .build();
    }

    /**
     * 删除对话记忆工具
     */
    @Bean
    public ToolCallback deleteMemoryTool(LongTermMemoryService longTermMemoryService) {
        BiFunction<DeleteMemoryRequest, ToolContext, MemoryResponse> function = (request, context) -> {
            RunnableConfig config = ToolContextHelper.getConfig(context).orElse(null);
            String userId = config != null ? config.metadata("user_id").map(Object::toString).orElse("unknown") : "unknown";

            boolean deleted = longTermMemoryService.deleteMemory(request.memoryId(), userId);
            return new MemoryResponse(deleted ? "已删除记忆" : "删除失败，记忆不存在或不属于当前用户");
        };

        return FunctionToolCallback.builder("deleteMemory", function)
                .description("删除一条对话记忆。需要传入记忆的ID（通过searchMemories获取）。")
                .inputType(DeleteMemoryRequest.class)
                .build();
    }

    // ========== 请求/响应记录 ==========

    public record UpdateProfileRequest(
            String name,
            String gender,
            Integer age,
            Map<String, Object> preferences,
            Map<String, Object> healthInfo
    ) {}

    public record SearchMemoryRequest(String query) {}

    public record DeleteMemoryRequest(String memoryId) {}

    public record MemoryResponse(String message) {}
}
