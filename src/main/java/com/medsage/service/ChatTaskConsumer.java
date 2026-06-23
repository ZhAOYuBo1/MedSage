package com.medsage.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.medsage.config.RabbitMQConfig;
import com.medsage.model.ChatTaskMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天任务消费者
 *
 * 流程：
 * 1. 从 RabbitMQ 消费任务消息
 * 2. 调用 Agent 流式接口
 * 3. 每个 token 写入 Redis Stream
 * 4. 异步风控检测，命中黑名单则中断
 * 5. 结束时写入 [DONE] 标记
 */
@Service
public class ChatTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatTaskConsumer.class);

    /**
     * token 批次大小 - 攒多少个 token 写一次
     */
    private static final int BATCH_SIZE = 3;

    /**
     * 批次超时 - 最多等多久写一次
     */
    private static final long BATCH_TIMEOUT_MS = 10;

    private final LlmRoutingAgent routerAgent;
    private final RedisStreamService redisStreamService;
    private final RiskControlService riskControlService;

    public ChatTaskConsumer(LlmRoutingAgent routerAgent,
                            RedisStreamService redisStreamService,
                            RiskControlService riskControlService) {
        this.routerAgent = routerAgent;
        this.redisStreamService = redisStreamService;
        this.riskControlService = riskControlService;
    }

    /**
     * 消费聊天任务
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_TASK)
    public void handleTask(ChatTaskMessage message) {
        String taskId = message.taskId();
        log.info("收到聊天任务: taskId={}, conversationId={}", taskId, message.conversationId());

        try {
            // 构建配置
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(message.conversationId())
                    .addMetadata("user_id", message.userId())
                    .build();

            // 调用 Agent 流式接口
            executeStreamTask(taskId, message.message(), config);

        } catch (Exception e) {
            log.error("任务执行失败: taskId={}", taskId, e);
            redisStreamService.writeError(taskId, e.getMessage());
        }
    }

    /**
     * 执行流式任务
     * 将 Agent 的流式输出写入 Redis Stream
     * 异步风控检测，命中黑名单则中断
     */
    private void executeStreamTask(String taskId, String message, RunnableConfig config) {
        log.info("开始执行流式任务: taskId={}", taskId);

        // 使用数组来在 lambda 中修改
        final StringBuilder[] batchBufferRef = {new StringBuilder()};
        final long[] lastFlushTimeRef = {System.currentTimeMillis()};
        final AtomicBoolean blocked = new AtomicBoolean(false);
        final StringBuilder[] accumulatedRef = {new StringBuilder()}; // 累积文本，用于风控检测

        try {
            // 调用 Agent 流式接口，阻塞等待完成
            routerAgent.stream(message, config)
                    .doOnNext(nodeOutput -> {
                        // 如果已命中风控，不再处理
                        if (blocked.get()) {
                            return;
                        }

                        String text = extractTextFromNodeOutput(nodeOutput);
                        if (text != null && !text.isEmpty()) {
                            // 累积文本
                            accumulatedRef[0].append(text);

                            // 异步风控检测（不阻塞 token 写入）
                            String accumulated = accumulatedRef[0].toString();
                            if (riskControlService.isBlocked(accumulated)) {
                                log.warn("风控命中，中断流式输出: taskId={}", taskId);
                                blocked.set(true);
                                redisStreamService.writeBlocked(taskId);
                                return;
                            }

                            // 攒批次写入
                            batchBufferRef[0].append(text);
                            long now = System.currentTimeMillis();
                            if (batchBufferRef[0].length() >= BATCH_SIZE
                                    || (now - lastFlushTimeRef[0]) >= BATCH_TIMEOUT_MS) {
                                redisStreamService.writeToken(taskId, batchBufferRef[0].toString());
                                batchBufferRef[0].setLength(0);
                                lastFlushTimeRef[0] = now;
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        // 如果已命中风控，不写入结束标记
                        if (blocked.get()) {
                            log.info("流式任务被风控中断: taskId={}", taskId);
                            return;
                        }

                        // 刷出剩余的 token
                        if (batchBufferRef[0].length() > 0) {
                            redisStreamService.writeToken(taskId, batchBufferRef[0].toString());
                        }
                        // 写入结束标记
                        redisStreamService.writeDone(taskId);
                        log.info("流式任务完成: taskId={}", taskId);

                        // 设置 Stream 过期时间（5分钟后自动清理）
                        redisStreamService.expireStream(taskId, Duration.ofMinutes(5));
                    })
                    .doOnError(e -> {
                        log.error("流式任务异常: taskId={}", taskId, e);
                        if (!blocked.get()) {
                            redisStreamService.writeError(taskId, e.getMessage());
                        }
                    })
                    .blockLast(); // 阻塞等待流完成

        } catch (Exception e) {
            log.error("流式任务执行失败: taskId={}", taskId, e);
            if (!blocked.get()) {
                redisStreamService.writeError(taskId, e.getMessage());
            }
        }
    }

    /**
     * 从 NodeOutput 提取回复文本
     * 遍历 state 中的 AssistantMessage，找到有内容的文本
     */
    private String extractTextFromNodeOutput(NodeOutput nodeOutput) {
        OverAllState state = nodeOutput.state();
        if (state == null) {
            return null;
        }

        // 遍历所有可能的 outputKey
        String[] outputKeys = {"internal_medicine", "surgery", "pediatrics", "obgyn",
                "psychology", "emergency", "general_doctor"};

        for (String key : outputKeys) {
            Object value = state.value(key).orElse(null);
            if (value instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }

        // 兜底：从 merged_result 提取
        Object mergedResult = state.value("merged_result").orElse(null);
        if (mergedResult instanceof String text && !text.isEmpty()) {
            return text;
        }

        return null;
    }
}
