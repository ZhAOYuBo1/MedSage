package com.medsage.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

/**
 * 科室推荐工具
 *
 * 根据用户描述的症状，匹配推荐就诊科室。
 * 内置常见症状到科室的映射表，支持多科室推荐（首选 + 备选）。
 *
 * 匹配规则：用户输入包含关键词即命中（正向匹配）。
 * 未匹配到时建议挂全科或内科。
 */
public class DepartmentRecommendationTool {

    /** 症状 -> 推荐科室映射表 */
    private static final Map<String, List<String>> SYMPTOM_MAP = Map.ofEntries(
            Map.entry("头疼", List.of("神经内科")),
            Map.entry("头痛", List.of("神经内科")),
            Map.entry("头晕", List.of("神经内科", "耳鼻喉科")),
            Map.entry("胸闷", List.of("心内科", "呼吸内科")),
            Map.entry("胸痛", List.of("心内科", "急诊科")),
            Map.entry("咳嗽", List.of("呼吸内科")),
            Map.entry("发烧", List.of("内科", "发热门诊")),
            Map.entry("腹泻", List.of("消化内科")),
            Map.entry("腹痛", List.of("消化内科", "普外科")),
            Map.entry("胃痛", List.of("消化内科")),
            Map.entry("皮疹", List.of("皮肤科")),
            Map.entry("关节痛", List.of("骨科", "风湿免疫科")),
            Map.entry("腰痛", List.of("骨科", "泌尿外科")),
            Map.entry("失眠", List.of("神经内科", "精神心理科")),
            Map.entry("焦虑", List.of("精神心理科")),
            Map.entry("抑郁", List.of("精神心理科"))
    );

    /**
     * 根据症状推荐就诊科室
     *
     * @param symptoms 症状描述，如"头疼"、"胸闷"
     * @return 推荐科室列表，首选科室标注"(首选)"
     */
    @Tool(description = "根据症状推荐就诊科室")
    public String recommendDepartment(@ToolParam(description = "症状描述") String symptoms) {
        if (symptoms == null || symptoms.isBlank()) {
            return "请描述症状";
        }

        // 遍历映射表，找到第一个匹配的症状
        for (Map.Entry<String, List<String>> entry : SYMPTOM_MAP.entrySet()) {
            if (symptoms.contains(entry.getKey())) {
                List<String> depts = entry.getValue();
                StringBuilder sb = new StringBuilder();
                sb.append("推荐科室: ");
                for (int i = 0; i < depts.size(); i++) {
                    if (i > 0) sb.append("、");
                    sb.append(depts.get(i));
                    if (i == 0) sb.append("(首选)");
                }
                sb.append("\n如不确定可先挂全科或内科");
                return sb.toString();
            }
        }

        return "未匹配到明确科室，建议先挂全科或内科，由导诊台指引";
    }
}
