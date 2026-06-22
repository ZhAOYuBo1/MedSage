package com.medsage.controller;

import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.medsage.config.RabbitMQConfig;
import com.medsage.model.ChatResponse;
import com.medsage.model.ChatTaskMessage;
import com.medsage.service.ChatService;
import com.medsage.service.StreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 聊天接口
 *
 * 两个接口都带超时兜底：
 * - /chat：同步接口，超时返回默认回复
 * - /stream：流式接口（MQ + Redis Stream 模式）
 */
@Tag(name = "聊天接口", description = "医疗问答对话接口")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final int TIMEOUT_SECONDS = 30;

    private final ChatService chatService;
    private final LlmRoutingAgent routerAgent;
    private final ExecutorService executor;
    private final RabbitTemplate rabbitTemplate;
    private final StreamService streamService;

    public ChatController(ChatService chatService,
                          LlmRoutingAgent routerAgent,
                          ExecutorService chatExecutor,
                          RabbitTemplate rabbitTemplate,
                          StreamService streamService) {
        this.chatService = chatService;
        this.routerAgent = routerAgent;
        this.executor = chatExecutor;
        this.rabbitTemplate = rabbitTemplate;
        this.streamService = streamService;
    }

    /**
     * 同步聊天接口
     * - 超时30秒返回默认回复
     */
    @Operation(summary = "智能聊天", description = "自动识别意图并路由到对应的Agent，超时30秒返回默认回复")
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();
        String userId = request.userId() != null ? request.userId() : "anonymous";

        // 异步执行，超时兜底
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(
                () -> chatService.chat(conversationId, request.message(), userId),
                executor
        );

        Object result = future.completeOnTimeout(
                ChatResponse.error("抱歉，处理超时，请稍后重试"),
                TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        ).join();

        return buildResponse(conversationId, result);
    }

    /**
     * 流式聊天接口（SSE）- MQ + Redis Stream 模式
     *
     * 流程：
     * 1. 生成 taskId，发送任务到 RabbitMQ
     * 2. 建立 SSE 连接，从 Redis Stream 读取 token
     * 3. MQ 消费者执行 Agent，写入 Redis Stream
     * 4. SSE 服务端读取并推送给前端
     *
     * 优势：
     * - 解耦：Agent 执行和 SSE 推送分离
     * - 可靠：Redis Stream 持久化，消息不会丢
     * - 可扩展：MQ 消费者可以水平扩展
     * - 超时兜底：死信队列处理超时任务
     */
    @Operation(summary = "流式聊天", description = "SSE流式输出，MQ + Redis Stream 模式")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        String conversationId = request.conversationId() != null
                ? request.conversationId()
                : UUID.randomUUID().toString();
        String userId = request.userId() != null ? request.userId() : "anonymous";

        // 生成任务ID
        String taskId = UUID.randomUUID().toString();

        // 发送任务到 RabbitMQ
        ChatTaskMessage taskMessage = new ChatTaskMessage(
                taskId, conversationId, request.message(), userId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.ROUTING_KEY,
                taskMessage);

        // 返回 SSE 流，从 Redis Stream 读取
        return streamService.createStream(taskId);
    }

    /**
     * 构建响应
     */
    private ResponseEntity<?> buildResponse(String conversationId, Object result) {
        if (result instanceof String question) {
            return ResponseEntity.ok(Map.of(
                    "conversationId", conversationId,
                    "clarifyQuestion", question,
                    "needsClarification", true
            ));
        }

        if (result instanceof ChatResponse chatResponse) {
            return ResponseEntity.ok(Map.of(
                    "conversationId", conversationId,
                    "response", chatResponse.response(),
                    "intent", chatResponse.intent(),
                    "slots", chatResponse.slots(),
                    "needsClarification", false
            ));
        }

        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "response", result.toString(),
                "needsClarification", false
        ));
    }

    /**
     * 请求体
     */
    public record ChatRequest(
            @Parameter(description = "用户消息") String message,
            @Parameter(description = "会话ID，不传则自动生成") String conversationId,
            @Parameter(description = "用户ID，用于长期记忆") String userId
    ) {}
}
