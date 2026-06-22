package com.medsage.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * SSE 流式服务
 *
 * 负责从 Redis Stream 读取 token 并推送给前端
 * - 使用 Flux 实现响应式流
 * - 轮询读取，有数据立刻推送
 */
@Service
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);

    /**
     * 轮询间隔
     */
    private static final long POLL_INTERVAL_MS = 50;

    /**
     * 最大空闲次数 - 超过则认为超时
     */
    private static final int MAX_IDLE_COUNT = 600; // 30秒 / 50ms = 600次

    private final RedisStreamService redisStreamService;

    public StreamService(RedisStreamService redisStreamService) {
        this.redisStreamService = redisStreamService;
    }

    /**
     * 创建 SSE 流
     *
     * @param taskId 任务ID
     * @return Flux<String> - token 流
     */
    public Flux<String> createStream(String taskId) {
        return Flux.<String>create(sink -> {
            log.info("创建 SSE 流: taskId={}", taskId);

            String lastId = null;
            int idleCount = 0;

            while (!sink.isCancelled()) {
                try {
                    // 从 Redis Stream 读取
                    List<MapRecord<String, String, String>> records =
                            redisStreamService.readTokens(taskId, lastId);

                    if (records == null || records.isEmpty()) {
                        idleCount++;
                        if (idleCount >= MAX_IDLE_COUNT) {
                            log.warn("SSE 流超时: taskId={}, idleCount={}", taskId, idleCount);
                            sink.next("抱歉，处理超时，请稍后重试。");
                            sink.next("[DONE]");
                            sink.complete();
                            return;
                        }
                        // 短暂休眠，避免忙等待
                        Thread.sleep(POLL_INTERVAL_MS);
                        continue;
                    }

                    // 重置空闲计数
                    idleCount = 0;

                    // 处理每条记录
                    for (MapRecord<String, String, String> record : records) {
                        lastId = record.getId().getValue();
                        String token = record.getValue().get("token");

                        if (token == null) {
                            continue;
                        }

                        // 检查结束标记
                        if (RedisStreamService.DONE_MARKER.equals(token)) {
                            log.info("SSE 流完成: taskId={}", taskId);
                            sink.next("[DONE]");
                            sink.complete();
                            return;
                        }

                        // 检查错误标记
                        if (token.startsWith(RedisStreamService.ERROR_MARKER)) {
                            String errorMsg = token.substring(RedisStreamService.ERROR_MARKER.length());
                            log.error("SSE 流收到错误: taskId={}, error={}", taskId, errorMsg);
                            sink.next("处理异常：" + errorMsg);
                            sink.next("[DONE]");
                            sink.complete();
                            return;
                        }

                        // 推送 token
                        sink.next(token);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("SSE 流被中断: taskId={}", taskId);
                    sink.complete();
                    return;
                } catch (Exception e) {
                    log.error("SSE 流读取异常: taskId={}", taskId, e);
                    sink.next("处理异常：" + e.getMessage());
                    sink.next("[DONE]");
                    sink.complete();
                    return;
                }
            }

            log.info("SSE 流被取消: taskId={}", taskId);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
