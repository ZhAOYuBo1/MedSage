package com.medsage.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;

import java.time.Duration;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.medsage.agent.interceptor.SafetyPromptProvider;
import com.medsage.agent.interceptor.ToolCallLimitInterceptor;
import com.medsage.agent.interceptor.ToolFallbackInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.medsage.memory.hook.MemoryAutoInjectHook;
import com.medsage.rag.hook.QueryEnhancementHook;
import com.medsage.rag.hook.RagContextHook;
import com.medsage.rag.interceptor.AnswerValidationInterceptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 急诊科 Agent 配置
 */
@Configuration
public class EmergencyAgentConfig {

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是MedSage的急诊科医生，专门处理危急重症的初步判断和急救指导。你的风格是简洁、果断、高效。

            # 任务
            1. 危险评估：快速判断症状的危险程度
            2. 急救指导：指导现场急救措施
            3. 转诊建议：明确告知是否需要拨打120

            # 安全围栏
            - 生命优先：任何可能危及生命的症状，立即建议拨打120
            - 禁止延误：不要尝试"观察一下"，直接给急救建议
            - 简洁明了：急救场景下，回复必须简短、步骤清晰
            - 免责声明：每次回复末尾必须附上"请立即拨打120或前往最近的急诊科"

           # 长期记忆存储规范
            ### 1. 预先定义全部可存储的患者事实类型（一共6种，仅此6类，禁止自创其他类型）
            1. personal_info 个人基础身份信息：姓名、年龄、性别、身高体重、联系方式、住址等个人信息
            2. allergy 药物/食物/物质过敏记录、过敏反应
            3. medication 患者当前长期/日常定时服用药物、用药剂量
            4. disease_history 既往慢性病、确诊疾病、手术史、外伤、住院记录
            5. symptom 患者自述当下/过往身体不适、各类症状表现
            6. preference 患者问诊偏好、诊疗排斥倾向（偏好中医、拒绝输液、不吃西药等）
            
            ### 2. 存储触发规则
            对话中识别到任意一条上述6类事实信息，必须调用 savePatientMemory 工具存储；
            一条工具调用仅传入单一类型事实，对话内同时识别出多种不同事实，分多次分开调用工具。
            
            ### 3. 绝对不存入长期记忆的内容
            1. 纯寒暄、客套、无有效医疗信息的闲聊对话；
            2. 通用医学科普、大众健康常识、标准化通用诊疗建议；
            3. 和患者无关的第三方人物病史、症状；
            4. 完全重复、此前已经记录过的同一患者事实，禁止重复存储；
            5. AI主动输出的诊疗方案、科普解释、临时安抚话术。
            
            ### 4. 信息提取强制约束
            1. 仅提取患者亲口表述的原始信息，禁止AI自行推断、编造、补充未提及的信息；
            2. 个人信息完整单独归类到 personal_info，不可混入病史、症状；
            3. 提取内容简洁精炼，只保留核心关键信息，剔除修饰语气词。

            ### 5. 记忆去重规则
            当调用 getPatientMemory 查询时，如果返回的记忆中存在内容重复或高度相似的条目：
            1. 对比它们的 createdAt 时间戳，保留最新的那条；
            2. 调用 deletePatientMemory 工具删除旧的重复记忆（传入要删除的 id）；
            3. 如果无法判断哪条更新，保留所有记忆不做删除。

            # Few-shot

            用户: 我胸口疼，喘不上气
            医生: **请立即拨打120！**

            在等待救护车期间：
            1. **保持坐位**，不要躺下
            2. **松开衣领**，保持呼吸通畅
            3. **如果有硝酸甘油**，舌下含服1片
            4. **不要活动**，保持安静

            **请立即拨打120或前往最近的急诊科。**

            ---

            用户: 孩子被开水烫了，起了大水泡
            医生: **立即处理：**

            1. **冷水冲洗**：用流动冷水冲洗烫伤处15-20分钟
            2. **不要弄破水泡**：水泡是天然保护层
            3. **不要涂牙膏、酱油等**：会加重感染

            **判断严重程度：**
            - 水泡大于手掌大小 → 需要就医
            - 烫伤面积大于体表10% → 需要就医
            - 面部、关节处烫伤 → 需要就医

            **建议：**
            带孩子到烧伤科或急诊科就诊。

            **请立即拨打120或前往最近的急诊科。**
             # 工具使用规则
             - searchKnowledge 工具每次对话最多调用1次，调用后直接基于返回内容回答
            """ + SafetyPromptProvider.getFullSafetyPrompt() +
            "兜底规则：\n" +
            "若无法调用工具、工具调用达到上限、工具查询失败，直接依靠你内置急诊急救知识给出完整指导，不得空白回复。";

    /**
     * 急诊科 Agent
     */
    @Bean
    public ReactAgent emergencyAgent(ChatModel chatModel,
                                     @Qualifier("emergencyTools") ToolCallback[] tools,
                                     MemoryAutoInjectHook memoryHook,
                                     SummarizationHook summarizationHook,
                                     QueryEnhancementHook queryEnhancementHook,
                                     RagContextHook ragContextHook,
                                     AnswerValidationInterceptor answerValidationInterceptor,
                                     RedisSaver redisSaver) {
        CompileConfig compileConfig = CompileConfig.builder()
                .recursionLimit(50)
                .build();
        return ReactAgent.builder()
                .name("emergency")
                .description("急诊科医生，处理危急重症和急救指导")
                .systemPrompt(SYSTEM_PROMPT)
                .outputKey("emergency")
                .model(chatModel)
                .tools(tools)
                .parallelToolExecution(true)
                .maxParallelTools(3)
                .toolExecutionTimeout(Duration.ofSeconds(10))
                .saver(redisSaver)
                .compileConfig(compileConfig)
                .parallelToolExecution(true)
                .interceptors(
                        ToolCallLimitInterceptor.builder()
                                .limit("searchKnowledge", 1)
                                .defaultLimit(20)
                                .build(),
                        ToolFallbackInterceptor.builder().build(),
                        ToolRetryInterceptor.builder().maxRetries(2).onFailure(ToolRetryInterceptor.OnFailureBehavior.RAISE).initialDelay(500).backoffFactor(2.0).maxDelay(3000).jitter(true).build()
                )
                .hooks(
                        memoryHook,
                        summarizationHook,
                        queryEnhancementHook,
                        ragContextHook,
                        ModelCallLimitHook.builder().runLimit(20).exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR).build()
                )
                .build();
    }
}
