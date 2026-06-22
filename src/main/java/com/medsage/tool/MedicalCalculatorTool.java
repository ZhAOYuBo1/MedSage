package com.medsage.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 医疗计算器工具
 *
 * 提供两个计算功能：
 * 1. BMI 计算：根据身高体重计算体重指数，评估是否健康
 * 2. 用药剂量计算：根据体重计算常用药物（阿莫西林、布洛芬）的单次和每日剂量
 *
 * 注意：剂量计算结果仅供参考，实际用药必须遵医嘱。
 */
public class MedicalCalculatorTool {

    /**
     * 计算 BMI 体重指数
     *
     * @param height 身高，单位：米（如 1.70）
     * @param weight 体重，单位：公斤（如 65）
     * @return BMI 值、分类和健康建议
     */
    @Tool(description = "计算BMI体重指数，评估体重是否健康")
    public String calculateBmi(
            @ToolParam(description = "身高，单位：米") double height,
            @ToolParam(description = "体重，单位：公斤") double weight) {
        if (height <= 0 || weight <= 0) {
            return "身高和体重必须大于0";
        }

        double bmi = weight / (height * height);
        String category;
        String advice;

        if (bmi < 18.5) {
            category = "偏瘦";
            advice = "建议适当增加营养摄入，规律饮食，保证蛋白质摄入";
        } else if (bmi < 24) {
            category = "正常";
            advice = "体重正常，请继续保持均衡饮食和规律运动";
        } else if (bmi < 28) {
            category = "超重";
            advice = "建议控制饮食，减少高热量食物，增加运动量";
        } else {
            category = "肥胖";
            advice = "建议就医制定减重计划，控制饮食，增加运动";
        }

        return String.format("BMI: %.1f (%s)\n建议: %s", bmi, category, advice);
    }

    /**
     * 根据体重计算用药剂量
     *
     * @param drugName 药品名称（目前支持：阿莫西林、布洛芬）
     * @param weight   患者体重，单位：公斤
     * @return 每次剂量、每日总量和用药提示
     */
    @Tool(description = "根据体重计算用药剂量，返回每次剂量和每日总量")
    public String calculateDosage(
            @ToolParam(description = "药品名称") String drugName,
            @ToolParam(description = "患者体重，单位：公斤") double weight) {
        if (weight <= 0) {
            return "体重必须大于0";
        }

        // 剂量计算规则：体重 × 每公斤剂量，取上限
        return switch (drugName) {
            case "阿莫西林" -> {
                double single = Math.min(weight * 25, 500);  // 25mg/kg/次，上限500mg
                double daily = Math.min(single * 3, 3000);    // 每日3次，上限3g
                yield String.format("阿莫西林: 每次%.0fmg, 每日%.0fmg (上限3g/天)\n仅供参考，请遵医嘱", single, daily);
            }
            case "布洛芬" -> {
                double single = Math.min(weight * 10, 400);   // 10mg/kg/次，上限400mg
                double daily = Math.min(single * 3, 1200);    // 每日3次，上限1200mg
                yield String.format("布洛芬: 每次%.0fmg, 每日%.0fmg (上限1200mg/天)\n仅供参考，请遵医嘱", single, daily);
            }
            default -> "未找到该药品的计算规则，请咨询医生";
        };
    }
}
