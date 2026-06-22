package com.medsage.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 向量存储配置
 * - 手动配置 PgVectorStore Bean
 * - 用于长期记忆的向量存储和检索
 */
@Configuration
public class VectorStoreConfig {

    /**
     * 配置 PgVectorStore
     *
     * @param jdbcTemplate    JDBC 模板（Spring Boot 自动配置）
     * @param embeddingModel  Embedding 模型（DashScope 自动配置）
     * @return VectorStore 实例
     */
    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("long_term_memory")
                .dimensions(1024)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false)
                .build();
    }
}
