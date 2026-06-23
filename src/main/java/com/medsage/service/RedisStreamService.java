package com.medsage.service;

import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 服务
 *
 * 用于流式消息传递：
 * - 生产者（MQ消费者）调用 writeToken() 写入 token
 * - 消费者（SSE服务端）调用 readTokens() 读取 token
 */
@Service
public class RedisStreamService {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamService.class);

    /**
     * Stream 前缀
     */
    private static final String STREAM_PREFIX = "medsage:stream:";

    /**
     * 结束标记
     */
    public static final String DONE_MARKER = "[DONE]";

    /**
     * 错误标记
     */
    public static final String ERROR_MARKER = "[ERROR]";

    /**
     * 风控中断标记
     */
    public static final String BLOCKED_MARKER = "[BLOCKED]";

    private final RedisTemplate<String, String> redisTemplate;
    private final RedissonClient redissonClient;

    public RedisStreamService(RedisTemplate<String, String> redisTemplate,
                              RedissonClient redissonClient) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
    }

    /**
     * 获取 Stream Key
     */
    public String getStreamKey(String taskId) {
        return STREAM_PREFIX + taskId;
    }

    /**
     * 写入 token 到 Stream
     *
     * @param taskId 任务ID
     * @param token  token 内容
     * @return RecordId
     */
    public RecordId writeToken(String taskId, String token) {
        String streamKey = getStreamKey(taskId);
        RecordId recordId = redisTemplate.opsForStream()
                .add(MapRecord.create(streamKey, Map.of("token", token)));
        log.debug("写入 token: taskId={}, recordId={}", taskId, recordId);
        return recordId;
    }

    /**
     * 写入结束标记
     */
    public void writeDone(String taskId) {
        writeToken(taskId, DONE_MARKER);
        log.info("写入结束标记: taskId={}", taskId);
    }

    /**
     * 写入错误标记
     */
    public void writeError(String taskId, String errorMsg) {
        writeToken(taskId, ERROR_MARKER + errorMsg);
        log.error("写入错误标记: taskId={}, error={}", taskId, errorMsg);
    }

    /**
     * 写入风控中断标记
     */
    public void writeBlocked(String taskId) {
        writeToken(taskId, BLOCKED_MARKER);
        log.warn("写入风控中断标记: taskId={}", taskId);
    }

    /**
     * 读取 token 列表（非阻塞模式）
     *
     * @param taskId 任务ID
     * @param lastId 上次读取的最后一条记录ID，null 表示从头读取
     * @return token 列表
     */
    public List<MapRecord<String, String, String>> readTokens(String taskId, String lastId) {
        String streamKey = getStreamKey(taskId);

        try {
            // 使用 XREAD 非阻塞读取
            StreamReadOptions options = StreamReadOptions.empty().count(10);
            ReadOffset offset;

            if (lastId == null) {
                // 首次读取，从头开始
                offset = ReadOffset.from("0-0");
            } else {
                // 后续读取，从 lastId 之后开始
                // Redis XREAD 的行为是包含 lastId，所以需要手动跳过
                offset = ReadOffset.from(lastId);
            }

            @SuppressWarnings("unchecked")
            List<MapRecord<String, String, String>> records = (List<MapRecord<String, String, String>>)
                    (List<?>) redisTemplate.opsForStream()
                            .read(options, StreamOffset.create(streamKey, offset));

            // 跳过已读取的记录（ID <= lastId）
            if (lastId != null && records != null && !records.isEmpty()) {
                records = records.stream()
                        .filter(record -> record.getId().getValue().compareTo(lastId) > 0)
                        .toList();
            }

            return records;
        } catch (Exception e) {
            log.error("读取 Stream 失败: taskId={}", taskId, e);
            return List.of();
        }
    }

    /**
     * 检查 Stream 是否存在
     */
    public boolean streamExists(String taskId) {
        String streamKey = getStreamKey(taskId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(streamKey));
    }

    /**
     * 删除 Stream
     */
    public void deleteStream(String taskId) {
        String streamKey = getStreamKey(taskId);
        redisTemplate.delete(streamKey);
        log.info("删除 Stream: taskId={}", taskId);
    }

    /**
     * 设置 Stream 过期时间
     * 使用 Redisson 原生 API 避免 StackOverflowError
     */
    public void expireStream(String taskId, Duration duration) {
        String streamKey = getStreamKey(taskId);
        RStream<String, String> stream = redissonClient.getStream(streamKey);
        stream.expire(duration);
        log.debug("设置 Stream 过期: taskId={}, duration={}", taskId, duration);
    }
}
