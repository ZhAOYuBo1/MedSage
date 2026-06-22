package com.medsage.service;

import com.medsage.config.RabbitMQConfig;
import com.medsage.model.ChatTaskMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

/**
 * 死信队列消费者
 *
 * 处理超时任务：
 * 1. 消息在主队列中超过 TTL（60秒）未被消费
 * 2. 自动转入死信队列
 * 3. 这里写入默认结果到 Redis Stream
 */
@Service
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    private final RedisStreamService redisStreamService;

    public DeadLetterConsumer(RedisStreamService redisStreamService) {
        this.redisStreamService = redisStreamService;
    }

    /**
     * 消费死信队列消息
     * 写入超时默认结果
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_DLQ)
    public void handleDeadLetter(ChatTaskMessage message) {
        String taskId = message.taskId();
        log.warn("任务超时，进入死信队列: taskId={}, conversationId={}", taskId, message.conversationId());

        try {
            // 写入超时默认结果
            String timeoutMessage = "抱歉，处理超时，请稍后重试。";
            redisStreamService.writeToken(taskId, timeoutMessage);
            redisStreamService.writeDone(taskId);

            // 设置 Stream 过期时间
            redisStreamService.expireStream(taskId, java.time.Duration.ofMinutes(5));

            log.info("已写入超时默认结果: taskId={}", taskId);
        } catch (Exception e) {
            log.error("处理死信消息失败: taskId={}", taskId, e);
        }
    }
}
