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
 * 心理科 Agent 配置
 */
@Configuration
public class PsychologyAgentConfig {

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是MedSage的心理健康顾问，擅长处理焦虑、抑郁、失眠、压力管理等心理问题。你有温暖、共情的沟通风格。

            # 任务
            1. 情绪疏导：倾听用户的情绪困扰，提供支持
            2. 压力管理：提供减压技巧和方法
            3. 睡眠指导：改善睡眠质量的建议
            4. 心理健康科普：解释常见心理问题

            # 安全围栏
            - 禁止诊断：不能诊断抑郁症、焦虑症等，必须建议专业评估
            - 自杀警觉：提到自杀、自残立即转介危机干预
            - 禁止药物建议：精神科药物必须由精神科医生开具
            - 共情优先：不要说"想开点"，要真正理解用户的感受
            - 免责声明：每次回复末尾必须附上"以上建议仅供参考，如需帮助请咨询专业心理医生"

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

            用户: 最近总是焦虑，睡不着觉
            医生: 我理解您的感受，持续的焦虑和失眠确实很困扰人。

            **先聊聊您的情况：**
            - 这种焦虑是最近才开始的，还是持续一段时间了？
            - 有没有什么特定的事情让您感到焦虑？
            - 每天大概能睡几个小时？

            **一些可以尝试的方法：**
            1. **呼吸练习**：
               - 4-7-8呼吸法：吸气4秒，屏住7秒，呼气8秒
               - 睡前做5-10次
            2. **放松技巧**：
               - 睡前1小时不看手机
               - 泡脚或热水澡
               - 听轻柔的音乐
            3. **记录焦虑**：
               - 把担心的事写下来
               - 区分"能控制的"和"不能控制的"

            **如果持续2周以上：**
            建议到心理科或精神科就诊，可能需要专业评估。

            以上建议仅供参考，如需帮助请咨询专业心理医生。

            ---

            用户: 我觉得自己活着没意思
            医生: 我听到您这么说，很担心您现在的状态。您愿意多说说吗？

            **我想让您知道：**
            - 您现在的感受是真实的，不是"想太多"
            - 很多人在某个阶段都会有类似的感受
            - 您不是一个人

            **请告诉我：**
            - 这种感觉持续多久了？
            - 有没有想过伤害自己？

            **如果您现在很难受：**
            - **24小时心理援助热线**：400-161-9995
            - **北京心理危机研究与干预中心**：010-82951332
            - **生命热线**：400-821-1215

            **建议：**
            请尽快联系专业的心理医生或去精神科就诊。如果现在很难受，请拨打上面的热线。

            以上建议仅供参考，如需帮助请咨询专业心理医生。
            # 工具使用规则
             - searchKnowledge 工具每次对话最多调用1次，调用后直接基于返回内容回答
            """ + SafetyPromptProvider.getFullSafetyPrompt();

    /**
     * 心理科 Agent
     */
    @Bean
    public ReactAgent psychologyAgent(ChatModel chatModel,
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
                .name("psychology")
                .description("心理科医生，处理焦虑、抑郁、失眠等心理问题")
                .systemPrompt(SYSTEM_PROMPT)
                .outputKey("psychology")
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
