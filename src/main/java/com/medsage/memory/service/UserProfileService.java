package com.medsage.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 用户画像服务
 * - 存储用户结构化信息：姓名、性别、年龄、偏好、身体情况
 * - 覆盖更新（传入的字段直接覆盖，不合并）
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public UserProfileService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取用户画像
     */
    public Map<String, Object> getProfile(String userId) {
        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    "SELECT * FROM user_profile WHERE user_id = ?", userId);
            if (results.isEmpty()) {
                return null;
            }
            Map<String, Object> row = results.get(0);
            Map<String, Object> profile = new HashMap<>();
            profile.put("userId", row.get("user_id"));
            profile.put("name", row.get("name"));
            profile.put("gender", row.get("gender"));
            profile.put("age", row.get("age"));
            profile.put("preferences", parseJson(row.get("preferences")));
            profile.put("healthInfo", parseJson(row.get("health_info")));
            return profile;
        } catch (Exception e) {
            log.error("获取用户画像失败: userId={}", userId, e);
            return null;
        }
    }

    /**
     * 更新用户画像（覆盖模式）
     * - 传入的字段直接覆盖
     * - 不传的字段保持不变
     */
    public void updateProfile(String userId, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }

        try {
            // 检查是否存在
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_profile WHERE user_id = ?", Integer.class, userId);

            if (count == null || count == 0) {
                // 插入新记录
                insertProfile(userId, updates);
            } else {
                // 覆盖更新
                overwriteProfile(userId, updates);
            }

            log.info("更新用户画像: userId={}, fields={}", userId, updates.keySet());
        } catch (Exception e) {
            log.error("更新用户画像失败: userId={}", userId, e);
        }
    }

    /**
     * 插入新用户画像
     */
    private void insertProfile(String userId, Map<String, Object> updates) {
        String name = (String) updates.get("name");
        String gender = (String) updates.get("gender");
        Integer age = updates.get("age") != null ? ((Number) updates.get("age")).intValue() : null;
        String preferences = toJson(updates.get("preferences"));
        String healthInfo = toJson(updates.get("health_info"));

        jdbcTemplate.update(
                "INSERT INTO user_profile (user_id, name, gender, age, preferences, health_info) VALUES (?, ?, ?, ?, ?::json, ?::json)",
                userId, name, gender, age, preferences, healthInfo
        );
    }

    /**
     * 覆盖更新用户画像
     * - 传入的字段直接覆盖
     * - 不传的字段保持不变
     */
    private void overwriteProfile(String userId, Map<String, Object> updates) {
        StringBuilder sql = new StringBuilder("UPDATE user_profile SET updated_at = NOW()");
        List<Object> params = new ArrayList<>();

        if (updates.containsKey("name")) {
            sql.append(", name = ?");
            params.add(updates.get("name"));
        }
        if (updates.containsKey("gender")) {
            sql.append(", gender = ?");
            params.add(updates.get("gender"));
        }
        if (updates.containsKey("age")) {
            sql.append(", age = ?");
            params.add(updates.get("age") != null ? ((Number) updates.get("age")).intValue() : null);
        }

        // preferences 覆盖（不是合并）
        if (updates.containsKey("preferences")) {
            sql.append(", preferences = ?::json");
            params.add(toJson(updates.get("preferences")));
        }

        // health_info 覆盖（不是合并）
        if (updates.containsKey("health_info")) {
            sql.append(", health_info = ?::json");
            params.add(toJson(updates.get("health_info")));
        }

        sql.append(" WHERE user_id = ?");
        params.add(userId);

        jdbcTemplate.update(sql.toString(), params.toArray());
    }

    /**
     * 获取用户画像（格式化文本，用于注入到对话）
     */
    public String getProfileAsText(String userId) {
        Map<String, Object> profile = getProfile(userId);
        if (profile == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【患者信息】\n");

        if (profile.get("name") != null) {
            sb.append("- 姓名: ").append(profile.get("name")).append("\n");
        }
        if (profile.get("gender") != null) {
            sb.append("- 性别: ").append(profile.get("gender")).append("\n");
        }
        if (profile.get("age") != null) {
            sb.append("- 年龄: ").append(profile.get("age")).append("\n");
        }

        Map<String, Object> healthInfo = (Map<String, Object>) profile.get("healthInfo");
        if (healthInfo != null && !healthInfo.isEmpty()) {
            sb.append("- 健康信息: ").append(formatJsonMap(healthInfo)).append("\n");
        }

        Map<String, Object> preferences = (Map<String, Object>) profile.get("preferences");
        if (preferences != null && !preferences.isEmpty()) {
            sb.append("- 偏好: ").append(formatJsonMap(preferences)).append("\n");
        }

        return sb.toString();
    }

    private String formatJsonMap(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return sb.toString();
    }

    private String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            if (obj instanceof Map || obj instanceof List) {
                return objectMapper.writeValueAsString(obj);
            }
            return "{}";
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, Object> parseJson(Object json) {
        if (json == null) return new HashMap<>();
        try {
            if (json instanceof String str) {
                return objectMapper.readValue(str, Map.class);
            }
            return (Map<String, Object>) json;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
