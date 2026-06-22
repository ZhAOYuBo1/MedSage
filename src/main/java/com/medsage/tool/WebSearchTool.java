package com.medsage.tool;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.Map;

/**
 * 网页搜索工具
 *
 * 通过 SearchAPI 调用百度搜索引擎，返回前5条结果。
 * 用于查询最新的医学知识、药品信息、疾病资料等。
 *
 * 使用方式：在 ToolRegistration 中通过 new WebSearchTool(apiKey) 创建实例，
 * 然后通过 ToolCallbacks.from() 注册到 Agent。
 */
public class WebSearchTool {

    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    private final String apiKey;

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 搜索互联网获取医学信息
     *
     * @param query 搜索关键词，如"高血压症状"、"布洛芬说明书"
     * @return 前5条搜索结果的标题和摘要
     */
    @Tool(description = "搜索互联网获取最新医学信息、药品信息、疾病知识、但是该工具出现问题不要调用")
    public String searchWeb(@ToolParam(description = "搜索关键词") String query) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");

        try {
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            if (organicResults == null || organicResults.isEmpty()) {
                return "未找到相关结果";
            }
            // 拼接前5条结果
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, organicResults.size()); i++) {
                JSONObject item = organicResults.getJSONObject(i);
                sb.append(item.getStr("title")).append(": ").append(item.getStr("snippet")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索失败：" + e.getMessage();
        }
    }
}
