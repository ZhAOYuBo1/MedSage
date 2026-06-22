package com.medsage.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 急救指南工具
 *
 * 根据急救场景返回标准化的急救步骤。
 * 内置常见场景：心肺复苏、大出血、烫伤、中暑、异物卡喉。
 *
 * 仅分配给急诊科 Agent 使用，其他科室不挂载此工具。
 */
public class EmergencyGuideTool {

    /**
     * 查询急救指南
     *
     * @param scenario 急救场景描述，如"心脏骤停"、"烫伤"、"噎住"
     * @return 对应场景的急救步骤，未匹配时返回通用原则
     */
    @Tool(description = "查询急救指南，输入场景返回急救步骤")
    public String getEmergencyGuide(@ToolParam(description = "急救场景，如：心脏骤停、大出血、烫伤") String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return "请描述急救场景";
        }

        String s = scenario.toLowerCase();

        // 心肺复苏
        if (s.contains("心脏骤停") || s.contains("心肺复苏") || s.contains("cpr")) {
            return """
                    【心肺复苏】立即拨打120！
                    1. 确认环境安全，检查意识和呼吸
                    2. 胸外按压：两乳头连线中点，深度5-6cm，频率100-120次/分
                    3. 按压30次后人工呼吸2次
                    4. 30:2循环直到急救人员到达
                    5. 如有AED立即使用
                    """;
        }
        // 大出血
        if (s.contains("大出血") || s.contains("止血")) {
            return """
                    【大出血急救】立即拨打120！
                    1. 戴手套或用塑料袋隔离
                    2. 用纱布直接按压伤口至少10分钟
                    3. 抬高受伤肢体高于心脏
                    4. 不要取出伤口中的异物
                    """;
        }
        // 烫伤/烧伤
        if (s.contains("烫伤") || s.contains("烧伤")) {
            return """
                    【烫伤急救】
                    1. 脱离热源
                    2. 流动冷水冲洗15-20分钟
                    3. 用干净纱布覆盖，不要涂牙膏酱油
                    4. 水泡不要弄破
                    5. 大面积烫伤立即拨打120
                    """;
        }
        // 中暑
        if (s.contains("中暑")) {
            return """
                    【中暑急救】
                    1. 转移到阴凉通风处
                    2. 解开衣物，湿毛巾擦拭全身
                    3. 意识清醒者饮用淡盐水
                    4. 重度中暑（意识不清、高热）立即拨打120
                    """;
        }
        // 异物卡喉
        if (s.contains("噎住") || s.contains("异物卡喉")) {
            return """
                    【海姆立克急救法】
                    成人：站在患者身后，双臂环绕腰部，一手握拳置于肚脐上两指，另一手包住拳头，快速向内向上冲击
                    婴儿：面朝下放前臂，掌根拍背5次，翻转按胸5次，重复
                    无法呼吸立即拨打120
                    """;
        }

        // 未匹配到具体场景，返回通用原则
        return "通用原则：保持冷静→拨打120→确保安全→等待急救人员";
    }
}
