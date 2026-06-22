package com.medsage.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库初始化器
 * 项目启动时自动创建记忆系统所需的表
 */
@Component
@Order(1)
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        log.info("开始初始化记忆系统数据库表...");
        try {
            createTables();
            log.info("记忆系统数据库表初始化完成");
        } catch (Exception e) {
            log.error("数据库表初始化失败", e);
        }
    }

    private void createTables() {
        // 1. 对话日志表（每条消息一行）
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS chat_archive (
                id BIGSERIAL PRIMARY KEY,
                thread_id VARCHAR(100) NOT NULL,
                user_id VARCHAR(100),
                role VARCHAR(20) NOT NULL,
                content TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT NOW()
            )
            """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chat_archive_thread_id ON chat_archive(thread_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chat_archive_user_id ON chat_archive(user_id)");

        // 2. 用户画像表（结构化信息）
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS user_profile (
                user_id VARCHAR(100) PRIMARY KEY,
                name VARCHAR(50),
                gender VARCHAR(10),
                age INT,
                preferences JSON DEFAULT '{}',
                health_info JSON DEFAULT '{}',
                updated_at TIMESTAMP DEFAULT NOW()
            )
            """);

        // 3. 长期记忆表（对话摘要 + 重要程度）
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS long_term_memory (
                    id VARCHAR(100) PRIMARY KEY,
                    user_id VARCHAR(100) NOT NULL,
                    content TEXT NOT NULL,
                    summary TEXT,
                    importance FLOAT DEFAULT 0.5,
                    memory_type VARCHAR(50),
                    embedding vector(1024),
                    created_at TIMESTAMP DEFAULT NOW(),
                    last_accessed_at TIMESTAMP DEFAULT NOW(),
                    access_count INT DEFAULT 0
                )
                """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_ltm_user ON long_term_memory(user_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_ltm_embedding ON long_term_memory USING hnsw (embedding vector_cosine_ops)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_ltm_importance ON long_term_memory(importance DESC)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_ltm_created ON long_term_memory(created_at DESC)");
        } catch (Exception e) {
            log.warn("pgvector 扩展创建失败，请确保已安装: {}", e.getMessage());
        }

        // 3. 短期记忆归档表
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS short_term_archive (
                id BIGSERIAL PRIMARY KEY,
                thread_id VARCHAR(100) NOT NULL,
                messages JSONB NOT NULL,
                compressed_summary TEXT,
                archived_at TIMESTAMP DEFAULT NOW()
            )
            """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_sta_thread_id ON short_term_archive(thread_id)");

        // 4. RAG 父子索引表
        try {
            // pg_trgm 扩展：用于 BM25 三元组模糊匹配
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");

            // 父块表：存 H2 章节完整内容
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rag_parent_chunks (
                    id VARCHAR(100) PRIMARY KEY,
                    source_file VARCHAR(500) NOT NULL,
                    header_path TEXT,
                    content TEXT NOT NULL,
                    metadata JSON,
                    created_at TIMESTAMP DEFAULT NOW()
                )
                """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rpc_source ON rag_parent_chunks(source_file)");

            // 子块向量表：存小段落 + embedding
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rag_child_chunks (
                    id VARCHAR(100) PRIMARY KEY,
                    content TEXT NOT NULL,
                    metadata JSONB,
                    embedding vector(1024),
                    created_at TIMESTAMP DEFAULT NOW()
                )
                """);
            // parent_id 从 metadata JSON 自动提取
            jdbcTemplate.execute("ALTER TABLE rag_child_chunks ADD COLUMN IF NOT EXISTS parent_id VARCHAR(100) GENERATED ALWAYS AS (metadata->>'parentId') STORED");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rcc_parent ON rag_child_chunks(parent_id)");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rcc_embedding ON rag_child_chunks USING hnsw (embedding vector_cosine_ops)");
            // BM25：三元组 GIN 索引，用于关键词模糊匹配
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rcc_content_trgm ON rag_child_chunks USING gin (content gin_trgm_ops)");
        } catch (Exception e) {
            log.warn("RAG 父子索引表创建失败: {}", e.getMessage());
        }

        log.info("表创建完成: chat_archive, rag_parent_chunks, rag_child_chunks");
    }
}
