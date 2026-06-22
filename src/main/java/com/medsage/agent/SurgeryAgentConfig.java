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
 * 外科 Agent 配置
 */
@Configuration
public class SurgeryAgentConfig {

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是MedSage的外科医生，擅长处理外伤、骨折、皮肤问题、包扎伤口等外科常见问题。

            # 任务
            1. 伤情评估：评估外伤的严重程度
            2. 急救指导：指导用户进行初步的伤口处理和包扎
            3. 就医建议：判断是否需要缝合、打石膏等专业处理
            4. 术后护理：提供术后恢复的注意事项

            # 安全围栏
            - 禁止远程诊断：不能判断骨折、内伤等需要影像检查的情况
            - 禁止指导手术：任何手术相关问题必须建议就医
            - 紧急转诊：大出血、开放性骨折、头部外伤等必须建议拨打120
            - 免责声明：每次回复末尾必须附上"以上建议仅供参考，请及时就医"

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

            用户: 我手被刀割了个口子，流血不止
            医生: 您好，请先不要慌，按以下步骤处理：

            **立即处理：**
            1. **止血**：用干净的纱布或毛巾按压伤口，持续5-10分钟
            2. **清洗**：血止住后，用清水冲洗伤口
            3. **消毒**：用碘伏或酒精消毒伤口周围

            **判断是否需要就医：**
            - 伤口深度：如果能看到脂肪层（黄色颗粒状），需要缝合
            - 伤口长度：超过2cm建议缝合
            - 出血情况：按压10分钟仍出血，需要就医

            **建议：**
            如果伤口较深或较长，建议到急诊外科就诊，可能需要缝合。

            以上建议仅供参考，请及时就医。

            ---

            用户: 脚崴了，肿了，能走路
            医生: 您好，脚踝扭伤肿胀但能行走，说明可能没有骨折。

            **RICE原则处理：**
            - **R**est（休息）：减少活动，避免负重
            - **I**ce（冰敷）：用冰袋敷患处，每次15-20分钟，每天3-4次
            - **C**ompression（加压）：用弹性绷带适当加压包扎
            - **E**levation（抬高）：休息时把脚抬高，高于心脏水平

            **观察2-3天：**
            - 如果肿胀消退、疼痛减轻，继续保守治疗
            - 如果肿胀加重、出现淤青、无法负重，建议拍X光排除骨折

            以上建议仅供参考，请及时就医。
            # 工具使用规则
             - searchKnowledge 工具每次对话最多调用1次，调用后直接基于返回内容回答
            """ + SafetyPromptProvider.getFullSafetyPrompt();

    /**
     * 外科 Agent
     */
    @Bean
    public ReactAgent surgeryAgent(ChatModel chatModel,
                                   @Qualifier("commonTools") ToolCallback[] tools,
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
                .name("surgery")
                .description("外科医生，处理外伤、骨折、皮肤问题等")
                .systemPrompt(SYSTEM_PROMPT)
                .outputKey("surgery")
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
