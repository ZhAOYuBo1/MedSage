package com.medsage.config;

import com.medsage.rag.RagDocumentService;
import com.medsage.tool.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具注册配置
 *
 * 集中管理所有工具的创建和分组，避免各 AgentConfig 重复创建。
 * 工具分三组：
 * 1. commonTools — 通用工具，科室共享
 * 2. emergencyTools — 急诊专用，额外包含急救指南
 * 3. allTools — 全量工具，兜底 Agent 使用
 */
@Configuration
public class ToolRegistration {

    @Value("${spring.ai.dashscope.api-key}")
    private String dashscopeApiKey;

    @Value("${search-api.api-key:}")
    private String searchApiKey;

    /**
     * 通用工具组（内科/外科/儿科/妇产科/心理科 共用）
     *
     * 包含：
     * - WebSearchTool: 网络搜索，查询医学知识
     * - ReportReadingTool: 报告图片识别（DashScope 多模态）
     * - DepartmentRecommendationTool: 症状→科室推荐
     * - MedicalCalculatorTool: BMI计算 + 用药剂量计算
     * - RagSearchTool: 医学知识库检索（混合检索）
     * - updateUserProfile: 更新用户画像
     * - searchMemories: 搜索对话记忆
     * - deleteMemory: 删除对话记忆
     */
    @Bean
    public ToolCallback[] commonTools(
            @Qualifier("updateUserProfileTool") ToolCallback updateProfile,
            @Qualifier("searchMemoriesTool") ToolCallback searchMemories,
            @Qualifier("deleteMemoryTool") ToolCallback deleteMemory,
            RagDocumentService ragDocumentService) {

        return concat(
                ToolCallbacks.from(
                        new WebSearchTool(searchApiKey),
                        new ReportReadingTool(dashscopeApiKey),
                        new DepartmentRecommendationTool(),
                        new MedicalCalculatorTool(),
                        new RagSearchTool(ragDocumentService)
                ),
                updateProfile, searchMemories, deleteMemory
        );
    }

    /**
     * 急诊专用工具组
     */
    @Bean
    public ToolCallback[] emergencyTools(
            @Qualifier("updateUserProfileTool") ToolCallback updateProfile,
            @Qualifier("searchMemoriesTool") ToolCallback searchMemories,
            @Qualifier("deleteMemoryTool") ToolCallback deleteMemory,
            RagDocumentService ragDocumentService) {

        return concat(
                ToolCallbacks.from(
                        new WebSearchTool(searchApiKey),
                        new ReportReadingTool(dashscopeApiKey),
                        new DepartmentRecommendationTool(),
                        new MedicalCalculatorTool(),
                        new EmergencyGuideTool(),
                        new RagSearchTool(ragDocumentService)
                ),
                updateProfile, searchMemories, deleteMemory
        );
    }

    /**
     * 全量工具组（兜底 Agent 使用）
     */
    @Bean
    public ToolCallback[] allTools(
            @Qualifier("updateUserProfileTool") ToolCallback updateProfile,
            @Qualifier("searchMemoriesTool") ToolCallback searchMemories,
            @Qualifier("deleteMemoryTool") ToolCallback deleteMemory,
            RagDocumentService ragDocumentService) {

        return concat(
                ToolCallbacks.from(
                        new WebSearchTool(searchApiKey),
                        new ReportReadingTool(dashscopeApiKey),
                        new DepartmentRecommendationTool(),
                        new MedicalCalculatorTool(),
                        new EmergencyGuideTool(),
                        new RagSearchTool(ragDocumentService)
                ),
                updateProfile, searchMemories, deleteMemory
        );
    }

    /**
     * 合并工具数组
     */
    private ToolCallback[] concat(ToolCallback[] base, ToolCallback... extra) {
        ToolCallback[] result = new ToolCallback[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
