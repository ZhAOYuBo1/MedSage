package com.medsage.router;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 简单路由器
 * - 用 LLM 判断路由到哪个 Agent
 * - 直接调用目标 Agent 并返回结果（不合并、不生成英文摘要）
 */
@Configuration
public class SimpleRouterAgent {

    private static final Logger log = LoggerFactory.getLogger(SimpleRouterAgent.class);

    private static final String ROUTE_PROMPT = """
            你是 MedSage 医疗智能体的路由系统。根据用户提问选择唯一匹配的科室，只输出科室名称，不要额外文字。

            可选科室：
            1. internal_medicine：内科，处理感冒发烧、头疼头晕、咳嗽、胃疼、腹泻、高血压、糖尿病、慢性病管理；
            2. surgery：外科，处理外伤、割伤、烫伤、骨折、扭伤、皮肤问题、包扎；
            3. pediatrics：儿科，处理孩子发烧、宝宝拉肚子、儿童咳嗽、发育、疫苗问题；
            4. obgyn：妇产科，处理月经不调、痛经、白带异常、怀孕、孕期、产后问题；
            5. psychology：心理科，处理焦虑、抑郁、失眠、压力大、情绪低落、睡不着；
            6. emergency：急诊科，处理胸痛、呼吸困难、大出血、昏迷、急救、生命危险；
            7. general_doctor：全科医生，处理无法明确分类的问题、通用健康咨询、科室引导。

            规则：
            - 根据用户描述的**主要症状**选择最匹配的科室
            - 儿童相关（孩子/宝宝/小孩）→ pediatrics
            - 女性相关（月经/怀孕/妇科）→ obgyn
            - 心理相关（焦虑/抑郁/失眠）→ psychology
            - 外伤相关（割伤/烫伤/骨折）→ surgery
            - 紧急情况（胸痛/大出血/昏迷）→ emergency
            - 其他内科症状（感冒/发烧/慢性病）→ internal_medicine
            - 无法确定时 → general_doctor
            """;

    private final ChatModel chatModel;
    private final Map<String, ReactAgent> agentMap;

    public SimpleRouterAgent(ChatModel chatModel,
                             @Qualifier("internalMedicineAgent") ReactAgent internalMedicineAgent,
                             @Qualifier("surgeryAgent") ReactAgent surgeryAgent,
                             @Qualifier("pediatricsAgent") ReactAgent pediatricsAgent,
                             @Qualifier("obgynAgent") ReactAgent obgynAgent,
                             @Qualifier("psychologyAgent") ReactAgent psychologyAgent,
                             @Qualifier("emergencyAgent") ReactAgent emergencyAgent,
                             @Qualifier("generalDoctorAgent") ReactAgent generalDoctorAgent) {
        this.chatModel = chatModel;
        this.agentMap = Map.of(
                "internal_medicine", internalMedicineAgent,
                "surgery", surgeryAgent,
                "pediatrics", pediatricsAgent,
                "obgyn", obgynAgent,
                "psychology", psychologyAgent,
                "emergency", emergencyAgent,
                "general_doctor", generalDoctorAgent
        );
    }

    /**
     * 路由并调用目标 Agent
     * 使用 call() 方法，会处理完整的工具调用循环并返回最终回复
     *
     * @return Agent 的回复文本
     */
    public String routeAndInvoke(String userMessage, RunnableConfig config) {
        // 1. 用 LLM 判断路由
        String targetAgent = route(userMessage);
        log.info("路由到: {}", targetAgent);

        // 2. 获取目标 Agent
        ReactAgent agent = agentMap.get(targetAgent);
        if (agent == null) {
            log.warn("未找到 Agent: {}, 使用 general_doctor", targetAgent);
            agent = agentMap.get("general_doctor");
        }

        // 3. 调用 Agent（使用 call 方法，处理完整循环）
        try {
            AssistantMessage response = agent.call(userMessage, config);
            if (response != null) {
                String text = response.getText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        } catch (Exception e) {
            log.error("Agent 调用失败: {}", targetAgent, e);
        }

        return "抱歉，处理失败，请重试";
    }

    /**
     * 用 LLM 判断路由目标
     */
    private String route(String userMessage) {
        try {
            Prompt prompt = new Prompt(
                    new SystemMessage(ROUTE_PROMPT),
                    new UserMessage(userMessage)
            );
            ChatResponse response = chatModel.call(prompt);
            String target = response.getResult().getOutput().getText().trim().toLowerCase();

            // 验证返回的是否是有效的 Agent 名称
            if (agentMap.containsKey(target)) {
                return target;
            }

            // 模糊匹配
            for (String key : agentMap.keySet()) {
                if (target.contains(key)) {
                    return key;
                }
            }

            log.warn("路由结果无法识别: {}, 使用 general_doctor", target);
            return "general_doctor";
        } catch (Exception e) {
            log.error("路由判断失败", e);
            return "general_doctor";
        }
    }
}
