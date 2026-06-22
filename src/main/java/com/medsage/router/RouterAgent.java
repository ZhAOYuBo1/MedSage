package com.medsage.router;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 路由 Agent 配置
 *
 * 使用 LlmRoutingAgent 实现 LLM 驱动的智能路由。
 * 根据用户消息内容，自动分发到对应科室的 Agent 处理。
 *
 * 路由策略：
 * - 关键词匹配优先（LLM 内部判断）
 * - 无法确定时默认走内科（fallbackAgent）
 */
@Configuration
public class RouterAgent {

    /**
     * 医疗问题统一分发路由
     *
     * @param chatModel   路由用的 ChatModel（轻量模型即可）
     * @param internalMedicineAgent 内科
     * @param surgeryAgent 外科
     * @param pediatricsAgent 儿科
     * @param obgynAgent 妇产科
     * @param psychologyAgent 心理科
     * @param emergencyAgent 急诊科
     * @return LlmRoutingAgent 实例
     */
    @Bean
    public LlmRoutingAgent medicalRouter(
            ChatModel chatModel,
            @Qualifier("internalMedicineAgent") ReactAgent internalMedicineAgent,
            @Qualifier("surgeryAgent") ReactAgent surgeryAgent,
            @Qualifier("pediatricsAgent") ReactAgent pediatricsAgent,
            @Qualifier("obgynAgent") ReactAgent obgynAgent,
            @Qualifier("psychologyAgent") ReactAgent psychologyAgent,
            @Qualifier("emergencyAgent") ReactAgent emergencyAgent,
            @Qualifier("generalDoctorAgent") ReactAgent generalDoctorAgent
    ) {
        String routeRule = """
                你是 MedSage 医疗智能体的路由系统。根据用户提问选择唯一匹配的子Agent，只输出Agent名称，不要额外文字。

                可选Agent列表：
                1. internal_medicine：内科，处理感冒发烧、头疼头晕、咳嗽、胃疼、腹泻、高血压、糖尿病、慢性病管理；
                2. surgery：外科，处理外伤、割伤、烫伤、骨折、扭伤、皮肤问题、包扎；
                3. pediatrics：儿科，处理孩子发烧、宝宝拉肚子、儿童咳嗽、发育、疫苗问题；
                4. obgyn：妇产科，处理月经不调、痛经、白带异常、怀孕、孕期、产后问题；
                5. psychology：心理科，处理焦虑、抑郁、失眠、压力大、情绪低落、睡不着；
                6. emergency：急诊科，处理胸痛、呼吸困难、大出血、昏迷、急救、生命危险；
                7. general_doctor：全科医生，处理无法明确分类的问题、通用健康咨询、科室引导。

                路由规则：
                - 根据用户描述的**主要症状**选择最匹配的科室
                - 儿童相关（孩子/宝宝/小孩）→ pediatrics
                - 女性相关（月经/怀孕/妇科）→ obgyn
                - 心理相关（焦虑/抑郁/失眠）→ psychology
                - 外伤相关（割伤/烫伤/骨折）→ surgery
                - 紧急情况（胸痛/大出血/昏迷）→ emergency
                - 其他内科症状（感冒/发烧/慢性病）→ internal_medicine
                - 无法确定时 → general_doctor

                输出规则：
                对每条用户消息，分析输出：
                在query应该
                {
                  "intent": "识别的意图",
                  "slots": {"槽位名": "提取出的信息"},
                  "missing": ["缺失的槽位名"],
                  "original_query": "用户原始问题"
                  "tip": "如果有缺失，可以自己靠记忆补全，如果确认缺失则需要追问"
                }
                规则：
                - 槽位缺失不要猜测，标记为null并加入missing列表

                示例：
                用户说"我发烧3天了"，输出：
                {
                  "intent": "症状咨询",
                  "slots": {"症状": "发烧", "持续时间": "3天"},
                  "missing": ["年龄", "性别"],
                  "original_query": "我发烧3天了"
                }
                """;

        return LlmRoutingAgent.builder()
                .name("medical_router")
                .description("医疗问题统一分发路由，根据症状匹配对应科室")
                .model(chatModel)
                .subAgents(List.of(
                        internalMedicineAgent,
                        surgeryAgent,
                        pediatricsAgent,
                        obgynAgent,
                        psychologyAgent,
                        emergencyAgent,
                        generalDoctorAgent
                ))
                .systemPrompt(routeRule)
                .fallbackAgent("general_doctor")  // 兜底：全科医生
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(50)
                        .build())
                .build();
    }
}
