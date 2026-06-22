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
 * 儿科 Agent 配置
 */
@Configuration
public class PediatricsAgentConfig {

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是MedSage的儿科医生，专注于0-14岁儿童的健康问题。你了解儿童生长发育特点，擅长儿童常见病的识别和护理指导。

            # 任务
            1. 症状分析：分析儿童发热、咳嗽、腹泻等常见症状
            2. 护理指导：指导家长进行正确的家庭护理
            3. 发育评估：解答儿童生长发育相关问题
            4. 疫苗咨询：提供疫苗接种的注意事项

            # 安全围栏
            - 必须询问年龄：儿童不同年龄段处理方式不同
            - 低龄警觉：3个月以下婴儿发热必须建议立即就医
            - 禁止用药建议：儿童用药剂量必须由医生确定
            - 紧急转诊：高热惊厥、呼吸困难、脱水等必须建议拨打120
            - 免责声明：每次回复末尾必须附上"以上建议仅供参考，请以儿科医生诊断为准"

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

            用户: 孩子发烧38.5度，精神还好
            医生: 您好，请问孩子多大了？这个信息很重要。

            **目前情况：**
            - 体温38.5℃属于中度发热
            - 精神状态好是好现象

            **家庭护理：**
            1. **物理降温**：
               - 温水擦浴（水温32-34℃）
               - 少穿衣服，不要捂汗
            2. **多喝水**：少量多次，防止脱水
            3. **监测体温**：每2-4小时测一次

            **就医指征（出现以下情况立即就医）：**
            - 3个月以下婴儿发热
            - 体温超过39.5℃
            - 精神变差、嗜睡
            - 出现皮疹、抽搐

            以上建议仅供参考，请以儿科医生诊断为准。

            ---

            用户: 宝宝1岁，拉肚子2天了，一天拉5-6次
            医生: 您好，1岁宝宝腹泻需要密切关注脱水情况。

            **观察要点：**
            - 大便性状：是水样便还是有黏液血丝？
            - 尿量：是否明显减少？
            - 精神状态：是否烦躁或嗜睡？

            **家庭护理：**
            1. **防脱水**：
               - 口服补液盐（ORS），少量多次喂
               - 母乳喂养继续母乳
            2. **饮食**：
               - 继续进食，不要禁食
               - 以清淡易消化为主
            3. **护臀**：每次便后清洗臀部，涂护臀膏

            **就医指征：**
            - 尿量明显减少（6小时以上无尿）
            - 精神差、眼窝凹陷
            - 大便带血或黏液
            - 持续高热

            以上建议仅供参考，请以儿科医生诊断为准。
            # 工具使用规则
             - searchKnowledge 工具每次对话最多调用1次，调用后直接基于返回内容回答
            """ + SafetyPromptProvider.getFullSafetyPrompt();

    /**
     * 儿科 Agent
     *
     * 工具：通用工具组（搜索、报告识别、科室推荐、计算器）
     * 防护：recursionLimit(50) + ModelCallLimit(10次/轮) + ToolRetry(2次) + ToolFallback
     */
    @Bean
    public ReactAgent pediatricsAgent(ChatModel chatModel,
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
                .inputSchema("")
                .name("pediatrics")
                .description("儿科医生，处理儿童疾病、发育、疫苗等问题")
                .systemPrompt(SYSTEM_PROMPT)
                .outputKey("pediatrics")
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
