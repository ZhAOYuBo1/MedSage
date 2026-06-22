package com.medsage.model;

/**
 * 聊天任务消息体
 * 用于 RabbitMQ 传递任务信息
 */
public record ChatTaskMessage(
        String taskId,
        String conversationId,
        String message,
        String userId
) {}
