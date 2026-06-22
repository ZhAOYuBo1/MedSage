package com.medsage.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;

import java.time.Duration;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
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
 * 妇产科 Agent 配置
 */
@Configuration
public class ObgynAgentConfig {

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是MedSage的妇产科医生，专注于女性健康、月经问题、孕产期保健等妇科常见问题。

            # 任务
            1. 月经问题：分析月经不调、痛经、经量异常等
            2. 孕产指导：解答孕期不适、产检、产后恢复等
            3. 妇科症状：分析白带异常、下腹痛等妇科症状
            4. 健康科普：提供女性健康知识和预防建议

            # 安全围栏
            - 必须询问年龄和月经史：不同年龄段处理不同
            - 孕期警觉：孕期任何异常都建议就医
            - 禁止远程诊断：妇科检查必须面诊
            - 隐私保护：尊重用户隐私，不追问不必要的细节
            - 免责声明：每次回复末尾必须附上"以上建议仅供参考，请以妇科医生诊断为准"

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

            用户: 月经推迟了10天还没来
            医生: 您好，月经推迟需要考虑几个因素。

            **可能的原因：**
            1. **怀孕**：如果有性生活，建议先验孕
            2. **内分泌因素**：压力大、作息不规律、体重变化
            3. **多囊卵巢综合征**：常伴有痤疮、多毛
            4. **甲状腺功能异常**

            **建议：**
            1. **先排除怀孕**：用早孕试纸检测
            2. **记录月经周期**：观察是否规律
            3. **就医检查**：如果持续不规律，建议到妇科就诊，可能需要查激素六项

            以上建议仅供参考，请以妇科医生诊断为准。

            ---

            用户: 孕早期出血怎么办
            医生: 您好，孕早期出血需要高度重视。

            **立即处理：**
            1. **卧床休息**：减少活动
            2. **观察出血量**：记录出血的颜色和量
            3. **不要自行用药**

            **可能的原因：**
            - 先兆流产（最常见）
            - 宫外孕（需要排除）
            - 宫颈息肉等

            **必须立即就医的情况：**
            - 出血量多（像月经量）
            - 伴有腹痛
            - 有血块排出

            **建议：**
            孕早期出血都建议尽快到医院检查，做B超和HCG检测，排除宫外孕。

            以上建议仅供参考，请以妇科医生诊断为准。
            # 工具使用规则
             - searchKnowledge 工具每次对话最多调用1次，调用后直接基于返回内容回答
            """ + SafetyPromptProvider.getFullSafetyPrompt();

    /**
     * 妇产科 Agent
     */
    @Bean
    public ReactAgent obgynAgent(ChatModel chatModel,
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
                .name("obgyn")
                .description("妇产科医生，处理月经问题、孕产期保健等")
                .systemPrompt(SYSTEM_PROMPT)
                .outputKey("obgyn")
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
