package com.medsage.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * RAG 父子索引 + 混合检索服务
 *
 * 架构：
 * - 父块：H2 章节完整内容，存 rag_parent_chunks
 * - 子块：小段落，存 rag_child_chunks（向量 + pg_trgm），带 parent_id 关联
 *
 * 检索流程：
 * 1. 查询重写（LLM）
 * 2. 并行检索：向量 topK + BM25(pg_trgm) topK
 * 3. 混合打分合并 → 取 topK
 * 4. 召回父文档
 * 5. 检索验证（不够 → 优化查询重试）
 * 6. 生成答案
 * 7. 答案验证（不合格 → 重试）
 */
@Service
public class RagDocumentService {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentService.class);

    /** 向量检索权重 */
    private static final double VECTOR_WEIGHT = 0.6;
    /** BM25 检索权重 */
    private static final double BM25_WEIGHT = 0.4;
    /** 检索相关性阈值 */
    private static final double RELEVANCE_THRESHOLD = 0.3;
    /** 最大重试次数 */
    private static final int MAX_RETRY = 2;

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore childVectorStore;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public RagDocumentService(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, ChatModel chatModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.chatClient = ChatClient.builder(chatModel).build();
        // 子块专用向量存储
        this.childVectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("rag_child_chunks")
                .dimensions(1024)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false)
                .build();
    }

    // ========== 写入 ==========

    /**
     * 保存父块
     */
    public void saveParentChunk(String id, String sourceFile, String headerPath, String content, Map<String, Object> metadata) {
        jdbcTemplate.update(
                "INSERT INTO rag_parent_chunks (id, source_file, header_path, content, metadata) VALUES (?, ?, ?, ?, ?::json) ON CONFLICT (id) DO NOTHING",
                id, sourceFile, headerPath, content, metadata != null ? toJson(metadata) : "{}"
        );
    }

    /**
     * 保存子块（带 embedding）
     */
    public void saveChildChunks(List<ChildChunk> chunks) {
        if (chunks.isEmpty()) return;

        List<Document> documents = new ArrayList<>();
        for (ChildChunk chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("parentId", chunk.parentId());
            metadata.put("sourceFile", chunk.sourceFile());
            metadata.put("headerPath", chunk.headerPath());
            if (chunk.metadata() != null) {
                metadata.putAll(chunk.metadata());
            }
            documents.add(new Document(chunk.id(), chunk.content(), metadata));
        }

        // DashScope embedding API 限制每次最多 10 条，分批写入
        int batchSize = 10;
        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);
            childVectorStore.add(batch);
        }
    }

    // ========== 混合检索主流程 ==========

    /**
     * 混合 RAG 检索（完整流程）
     *
     * @param query 用户问题（已经过 QueryEnhancementHook 重写）
     * @param topK  最终返回的父文档数量
     * @return 父文档列表
     */
    public List<ParentChunkResult> hybridSearch(String query, int topK) {
        // 1. 并行检索（向量 + BM25）
        int fetchK = topK * 3;  // 多取一些用于合并
        List<ScoredChild> vectorResults = vectorSearch(query, fetchK);
        List<ScoredChild> bm25Results = bm25Search(query, fetchK);

        // 3. 混合打分合并
        List<ScoredChild> merged = mergeScores(vectorResults, bm25Results, topK);

        if (merged.isEmpty()) {
            log.debug("混合检索无结果");
            return List.of();
        }

        // 4. 召回父文档
        List<ParentChunkResult> parentResults = recallParents(merged);

        log.debug("混合检索完成: query={}, 向量命中={}, BM25命中={}, 合并后={}, 父文档={}",
                query, vectorResults.size(), bm25Results.size(), merged.size(), parentResults.size());

        return parentResults;
    }

    /**
     * 带重试的混合 RAG（参考示例中的混合 RAG 模式）
     *
     * @param query 用户问题（已经过 QueryEnhancementHook 重写）
     * @param topK  返回的父文档数量
     * @return 父文档列表（经过检索验证）
     */
    public List<ParentChunkResult> hybridSearchWithRetry(String query, int topK) {
        String currentQuery = query;

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            // 并行检索
            int fetchK = topK * 3;
            List<ScoredChild> vectorResults = vectorSearch(currentQuery, fetchK);
            List<ScoredChild> bm25Results = bm25Search(currentQuery, fetchK);

            // 混合打分
            List<ScoredChild> merged = mergeScores(vectorResults, bm25Results, topK);

            if (merged.isEmpty()) {
                currentQuery = refineQuery(currentQuery, "检索结果为空，请换一种表述");
                continue;
            }

            // 检索验证
            if (!isRetrievalSufficient(merged)) {
                log.debug("检索结果不够相关，优化查询重试 (attempt={})", attempt + 1);
                currentQuery = refineQuery(currentQuery, "检索结果不够相关，请优化查询");
                continue;
            }

            // 召回父文档
            return recallParents(merged);
        }

        // 重试用尽，返回最后一次结果
        log.debug("重试用尽，返回最后结果");
        List<ScoredChild> vectorResults = vectorSearch(currentQuery, topK * 3);
        List<ScoredChild> bm25Results = bm25Search(currentQuery, topK * 3);
        List<ScoredChild> merged = mergeScores(vectorResults, bm25Results, topK);
        return recallParents(merged);
    }

    // ========== 查询优化 ==========

    /**
     * 查询优化：根据检索失败原因优化查询（重试时用）
     */
    private String refineQuery(String query, String reason) {
        try {
            return chatClient.prompt()
                    .system("你是一个查询优化助手。根据失败原因优化医学文档搜索查询。" +
                            "规则：1.换一种表述 2.扩展关键词 3.只输出优化后的查询")
                    .user("原始查询: " + query + "\n失败原因: " + reason)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("查询优化失败: {}", e.getMessage());
            return query;
        }
    }

    // ========== 向量检索 ==========

    /**
     * 向量检索子块
     */
    private List<ScoredChild> vectorSearch(String query, int topK) {
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(0.3)
                    .build();

            List<Document> results = childVectorStore.similaritySearch(searchRequest);
            if (results == null) return List.of();

            return results.stream()
                    .map(doc -> {
                        String childId = doc.getId();
                        String parentId = (String) doc.getMetadata().get("parentId");
                        // pgvector cosine distance → similarity: 1 - distance
                        double score = 1.0 - (doc.getScore() != null ? doc.getScore() : 1.0);
                        return new ScoredChild(childId, parentId, doc.getText(), score);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ========== BM25 检索（pg_trgm） ==========

    /**
     * BM25 检索子块（pg_trgm similarity）
     */
    private List<ScoredChild> bm25Search(String query, int topK) {
        try {
            // pg_trgm similarity 返回 0~1 的相似度分数
            // 模糊匹配：similarity > 0.1 才算相关
            String sql = """
                    SELECT id, content, parent_id, similarity(content, ?) AS score
                    FROM rag_child_chunks
                    WHERE content % ?
                    ORDER BY score DESC
                    LIMIT ?
                    """;

            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                String id = rs.getString("id");
                String content = rs.getString("content");
                String parentId = rs.getString("parent_id");
                double score = rs.getDouble("score");
                return new ScoredChild(id, parentId, content, score);
            }, query, query, topK);
        } catch (Exception e) {
            log.warn("BM25 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ========== 混合打分 ==========

    /**
     * 混合打分合并：向量分数 * VECTOR_WEIGHT + BM25分数 * BM25_WEIGHT
     *
     * @return 按混合分数排序的 topK 子块
     */
    private List<ScoredChild> mergeScores(List<ScoredChild> vectorResults, List<ScoredChild> bm25Results, int topK) {
        // 归一化：分别把两路分数映射到 [0, 1]
        double maxVector = vectorResults.stream().mapToDouble(ScoredChild::score).max().orElse(1.0);
        double maxBm25 = bm25Results.stream().mapToDouble(ScoredChild::score).max().orElse(1.0);

        // 合并到 map: childId → combinedScore
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, ScoredChild> childMap = new HashMap<>();

        for (ScoredChild child : vectorResults) {
            double normalized = maxVector > 0 ? child.score() / maxVector : 0;
            scoreMap.merge(child.childId(), normalized * VECTOR_WEIGHT, Double::sum);
            childMap.putIfAbsent(child.childId(), child);
        }

        for (ScoredChild child : bm25Results) {
            double normalized = maxBm25 > 0 ? child.score() / maxBm25 : 0;
            scoreMap.merge(child.childId(), normalized * BM25_WEIGHT, Double::sum);
            childMap.putIfAbsent(child.childId(), child);
        }

        // 按混合分数排序，取 topK
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    ScoredChild child = childMap.get(entry.getKey());
                    return new ScoredChild(child.childId(), child.parentId(), child.content(), entry.getValue());
                })
                .collect(Collectors.toList());
    }

    // ========== 检索验证 ==========

    /**
     * 检索验证：判断检索结果是否足够相关
     */
    private boolean isRetrievalSufficient(List<ScoredChild> results) {
        if (results.isEmpty()) return false;
        // 平均分数 > 阈值
        double avgScore = results.stream().mapToDouble(ScoredChild::score).average().orElse(0);
        return avgScore >= RELEVANCE_THRESHOLD;
    }

    // ========== 父文档召回 ==========

    /**
     * 根据子块召回父文档（去重，保持顺序）
     */
    private List<ParentChunkResult> recallParents(List<ScoredChild> children) {
        LinkedHashSet<String> parentIds = new LinkedHashSet<>();
        for (ScoredChild child : children) {
            if (child.parentId() != null) {
                parentIds.add(child.parentId());
            }
        }

        if (parentIds.isEmpty()) return List.of();

        List<ParentChunkResult> results = new ArrayList<>();
        for (String parentId : parentIds) {
            jdbcTemplate.query(
                    "SELECT content, header_path, source_file FROM rag_parent_chunks WHERE id = ?",
                    rs -> {
                        results.add(new ParentChunkResult(
                                parentId,
                                rs.getString("content"),
                                rs.getString("header_path"),
                                rs.getString("source_file")
                        ));
                    },
                    parentId
            );
        }

        return results;
    }

    // ========== 工具方法 ==========

    public int getParentChunkCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_parent_chunks", Integer.class);
        return count != null ? count : 0;
    }

    public int getChildChunkCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_child_chunks", Integer.class);
        return count != null ? count : 0;
    }

    public void clearAll() {
        jdbcTemplate.execute("DELETE FROM rag_child_chunks");
        jdbcTemplate.execute("DELETE FROM rag_parent_chunks");
        log.info("已清空 RAG 父子索引数据");
    }

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    // ========== 数据结构 ==========

    /** 子块（带分数） */
    public record ScoredChild(String childId, String parentId, String content, double score) {}

    public record ChildChunk(String id, String parentId, String content, String sourceFile, String headerPath, Map<String, Object> metadata) {}

    public record ParentChunkResult(String id, String content, String headerPath, String sourceFile) {}
}
