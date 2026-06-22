package com.medsage.memory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 对话日志服务
 * - 每条消息一行，记录到 chat_archive 表
 */
@Service
public class ConversationLogService {

    private static final Logger log = LoggerFactory.getLogger(ConversationLogService.class);

    private final JdbcTemplate jdbcTemplate;

    public ConversationLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录用户消息和 Agent 回复（两条记录）
     */
    public void log(String threadId, String userId, String userMessage, String agentReply) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO chat_archive (thread_id, user_id, role, content) VALUES (?, ?, ?, ?)",
                    threadId, userId, "user", userMessage
            );
            jdbcTemplate.update(
                    "INSERT INTO chat_archive (thread_id, user_id, role, content) VALUES (?, ?, ?, ?)",
                    threadId, userId, "assistant", agentReply
            );
        } catch (Exception e) {
            log.error("对话日志记录失败: threadId={}", threadId, e);
        }
    }
}
