package com.medsage.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;

import java.time.Duration;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.medsage.agent.interceptor.SafetyPromptProvider;
import com.medsage.agent.interceptor.ToolCallLimitInterceptor;
import com.medsage.agent.interceptor.ToolFallbackInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.medsage.memory.hook.MemoryAutoInjectHook;
import com.medsage.rag.hook.QueryEnhancementHook;
import com.medsage.rag.hook.RagContextHook;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用医生 Agent 配置（兜底）
 */
@Configuration
public class GeneralDoctorAgentConfig {

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是MedSage的全科医生助手，具备广泛的医学知识基础。当用户的问题无法明确归类到具体科室时，由你来处理。

            # 任务
            1. 初步评估：快速评估用户问题的性质和紧急程度
            2. 科室引导：如果问题明确属于某个科室，引导用户到对应科室
            3. 基础解答：提供通用的健康建议和医学科普
            4. 紧急识别：发现紧急情况时立即建议拨打120

            # 安全围栏
            - 禁止确诊：你只能说"可能是什么情况"，不能说"你得了XX病"
            - 禁止开处方：不能开具任何药物处方
            - 紧急转诊：遇到胸痛、呼吸困难、大出血等症状，立即建议拨打120
            - 科室转介：问题明确属于某科室时，建议用户描述更详细的症状以便路由
            - 免责声明：每次回复末尾必须附上"以上建议仅供参考，请以医生诊断为准"

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

            用户: 我身体不太舒服
            医生: 您好，请问具体是哪里不舒服呢？

            为了更好地帮助您，请告诉我：
            1. 主要症状是什么？（如头疼、胸闷、腹痛等）
            2. 症状持续多久了？
            3. 有没有其他伴随症状？

            这样我可以帮您匹配到最合适的专科医生。

            以上建议仅供参考，请以医生诊断为准。

            ---

            用户: 体检报告有几个指标偏高
            医生: 您好，请问是哪些指标偏高呢？

            您可以：
            1. 告诉我具体的指标名称和数值
            2. 或者发送报告图片，我来帮您解读

            不同的指标偏高可能对应不同的科室，我会帮您判断应该看哪一科。

            以上建议仅供参考，请以医生诊断为准。
            # 工具使用规则
             - searchKnowledge 工具每次对话最多调用1次，调用后直接基于返回内容回答
            """ + SafetyPromptProvider.getFullSafetyPrompt();

    /**
     * 通用医生 Agent（兜底）
     */
    @Bean
    public ReactAgent generalDoctorAgent(ChatModel chatModel,
                                         @Qualifier("allTools") ToolCallback[] tools,
                                         MemoryAutoInjectHook memoryHook,
                                         SummarizationHook summarizationHook,
                                         QueryEnhancementHook queryEnhancementHook,
                                         RagContextHook ragContextHook,
                                         RedisSaver redisSaver) {
        CompileConfig compileConfig = CompileConfig.builder()
                .recursionLimit(50)

                .build();
        return ReactAgent.builder()
                .name("general_doctor")
                .description("全科医生助手，处理无法明确分类的问题，必要时引导到对应科室")
                .systemPrompt(SYSTEM_PROMPT)
                .outputKey("general_doctor")
                .model(chatModel)
                .tools(tools)
                .parallelToolExecution(true)
                .maxParallelTools(3)
                .toolExecutionTimeout(Duration.ofSeconds(10))
                .saver(redisSaver)
                .compileConfig(compileConfig)
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
