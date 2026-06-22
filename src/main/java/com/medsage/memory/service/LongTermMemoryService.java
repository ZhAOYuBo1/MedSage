package com.medsage.memory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 长期记忆服务（重构版）
 * - 存储对话摘要，包含重要程度
 * - 召回策略：重要程度 + 相似度 + 时间衰减
 * - 自动清理：重要程度 < 0.3 且超过 30 天的记忆
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    // 召回权重
    private static final double IMPORTANCE_WEIGHT = 0.4;
    private static final double SIMILARITY_WEIGHT = 0.4;
    private static final double TIME_WEIGHT = 0.2;

    // 清理阈值
    private static final double LOW_IMPORTANCE_THRESHOLD = 0.3;
    private static final int CLEANUP_DAYS = 30;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public LongTermMemoryService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存长期记忆（对话摘要）
     */
    public void saveMemory(String userId, String content, String summary,
                           double importance, String memoryType) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", userId);
        metadata.put("memoryType", memoryType);
        metadata.put("importance", importance);

        Document document = new Document(id, content, metadata);

        try {
            vectorStore.add(List.of(document));

            // 更新 summary 和 importance（PgVectorStore 不支持自定义字段，需要手动更新）
            jdbcTemplate.update(
                    "UPDATE long_term_memory SET summary = ?, importance = ? WHERE id = ?",
                    summary, importance, id
            );

            log.info("保存长期记忆: userId={}, type={}, importance={}, content={}",
                    userId, memoryType, importance,
                    content.length() > 50 ? content.substring(0, 50) + "..." : content);
        } catch (Exception e) {
            log.error("保存长期记忆失败: userId={}", userId, e);
        }
    }

    /**
     * 语义检索长期记忆（带重要程度和时间加权）
     */
    public List<Map<String, Object>> searchMemories(String userId, String query, int topK) {
        try {
            // 1. 向量检索（多取一些，后面排序）
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK * 3)
                    .similarityThreshold(0.5)
                    .filterExpression("userId == '" + userId + "'")
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);

            if (results == null || results.isEmpty()) {
                return List.of();
            }

            // 2. 获取完整信息（包括 importance）
            List<Map<String, Object>> memories = new ArrayList<>();
            for (Document doc : results) {
                Map<String, Object> detail = getMemoryDetail(doc.getId());
                if (detail != null) {
                    detail.put("similarity", doc.getMetadata().getOrDefault("distance", 0.0));
                    memories.add(detail);
                }
            }

            // 3. 计算综合得分并排序
            memories.sort((a, b) -> {
                double scoreA = calculateScore(a);
                double scoreB = calculateScore(b);
                return Double.compare(scoreB, scoreA);
            });

            // 4. 返回 topK
            if (memories.size() > topK) {
                memories = memories.subList(0, topK);
            }

            // 5. 更新访问时间
            for (Map<String, Object> mem : memories) {
                updateAccessTime((String) mem.get("id"));
            }

            return memories;
        } catch (Exception e) {
            log.error("检索长期记忆失败: userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 计算综合得分
     * - 重要程度 > 0.8：忽略时间，只看重要程度 + 相似度
     * - 重要程度 < 0.3：大幅降低权重
     * - 其他：综合考虑
     */
    private double calculateScore(Map<String, Object> memory) {
        double importance = ((Number) memory.getOrDefault("importance", 0.5)).doubleValue();
        double similarity = ((Number) memory.getOrDefault("similarity", 0.0)).doubleValue();
        java.sql.Timestamp createdAt = (java.sql.Timestamp) memory.get("created_at");

        // 时间衰减（天数）
        double daysSinceCreation = 0;
        if (createdAt != null) {
            daysSinceCreation = (System.currentTimeMillis() - createdAt.getTime()) / (1000.0 * 60 * 60 * 24);
        }
        double timeDecay = Math.exp(-daysSinceCreation / 30.0); // 30天半衰期

        // 高重要程度：忽略时间
        if (importance >= 0.8) {
            return IMPORTANCE_WEIGHT * importance + SIMILARITY_WEIGHT * similarity;
        }

        // 低重要程度：降低权重
        if (importance < LOW_IMPORTANCE_THRESHOLD) {
            return (IMPORTANCE_WEIGHT * importance + SIMILARITY_WEIGHT * similarity + TIME_WEIGHT * timeDecay) * 0.5;
        }

        // 正常计算
        return IMPORTANCE_WEIGHT * importance + SIMILARITY_WEIGHT * similarity + TIME_WEIGHT * timeDecay;
    }

    /**
     * 获取记忆详情
     */
    private Map<String, Object> getMemoryDetail(String id) {
        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    "SELECT id, content, summary, importance, memory_type, created_at, last_accessed_at, access_count " +
                            "FROM long_term_memory WHERE id = ?", id);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            log.error("获取记忆详情失败: id={}", id, e);
            return null;
        }
    }

    /**
     * 更新访问时间
     */
    private void updateAccessTime(String id) {
        try {
            jdbcTemplate.update(
                    "UPDATE long_term_memory SET last_accessed_at = NOW(), access_count = access_count + 1 WHERE id = ?",
                    id
            );
        } catch (Exception e) {
            log.warn("更新访问时间失败: id={}", id);
        }
    }

    /**
     * 清理低重要程度且过期的记忆
     * - 重要程度 < 0.3 且超过 30 天
     */
    public int cleanupExpiredMemories() {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM long_term_memory WHERE importance < ? AND created_at < NOW() - INTERVAL '%d days'"
                            .formatted(CLEANUP_DAYS),
                    LOW_IMPORTANCE_THRESHOLD
            );
            if (deleted > 0) {
                log.info("清理过期记忆: 删除 {} 条", deleted);
            }
            return deleted;
        } catch (Exception e) {
            log.error("清理过期记忆失败", e);
            return 0;
        }
    }

    /**
     * 删除指定记忆
     */
    public boolean deleteMemory(String memoryId, String userId) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM long_term_memory WHERE id = ? AND user_id = ?",
                    memoryId, userId
            );
            return deleted > 0;
        } catch (Exception e) {
            log.error("删除记忆失败: id={}", memoryId, e);
            return false;
        }
    }
}
