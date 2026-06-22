package com.medsage.tool;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 报告图片识别工具
 *
 * 通过 DashScope 多模态模型（qwen-vl-max）识别医疗报告图片。
 * 输入图片的公网 URL，返回报告解读结果（检验项目、数值、参考范围、异常标注）。
 *
 * 实现细节：
 * - 临时切换 DashScope API URL，调用后恢复，避免全局副作用
 * - 使用 qwen3.7-plus 多模态模型进行图片理解
 */
public class ReportReadingTool {

    private static final Logger log = LoggerFactory.getLogger(ReportReadingTool.class);
    private static final String DASHSCOPE_API_URL = "https://dashscope.aliyuncs.com/api/v1";

    private final String apiKey;

    public ReportReadingTool(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 识别并解读医疗报告图片
     *
     * @param imageUrl 报告图片的公网 HTTP/HTTPS URL
     * @return 报告解读结果，包含检验项目、数值、参考范围和异常标注
     */
    @Tool(description = "识别并解读医疗报告图片，输入图片URL返回解读结果")
    public String readReport(@ToolParam(description = "报告图片的公网URL") String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return "请提供报告图片URL";
        }

        // 保存原始 URL，调用后恢复
        String originalUrl = Constants.baseHttpApiUrl;
        try {
            Constants.baseHttpApiUrl = DASHSCOPE_API_URL;

            // 构建多模态消息：图片 + 解读指令
            MultiModalMessage userMessage = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(
                            Map.of("image", imageUrl),
                            Map.of("text", "解读这张医疗报告：列出检验项目、数值、参考范围，标注异常指标")
                    ))
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model("qwen3.7-plus")
                    .messages(List.of(userMessage))
                    .build();

            MultiModalConversation conv = new MultiModalConversation();
            MultiModalConversationResult result = conv.call(param);

            // 提取返回文本
            return (String) result.getOutput()
                    .getChoices().get(0)
                    .getMessage().getContent().get(0)
                    .get("text");
        } catch (Exception e) {
            log.error("报告识别失败", e);
            return "识别失败：" + e.getMessage();
        } finally {
            // 恢复原始 URL
            Constants.baseHttpApiUrl = originalUrl;
        }
    }
}
