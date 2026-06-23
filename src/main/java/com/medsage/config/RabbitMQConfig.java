package com.medsage.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.Executors;

/**
 * RabbitMQ 配置
 *
 * 队列结构：
 * - medsage.chat.task: 主队列，接收聊天任务
 * - medsage.chat.task.dlq: 死信队列，处理超时任务
 *
 * 使用虚拟线程提升并发能力
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "medsage.exchange";
    public static final String QUEUE_TASK = "medsage.chat.task";
    public static final String QUEUE_DLQ = "medsage.chat.task.dlq";
    public static final String ROUTING_KEY = "chat.task";
    public static final String DLQ_ROUTING_KEY = "chat.task.dlq";

    /**
     * 交换机
     */
    @Bean
    public DirectExchange chatExchange() {
        return new DirectExchange(EXCHANGE);
    }

    /**
     * 主队列 - 带死信配置
     * 消息超时后自动转入死信队列
     */
    @Bean
    public Queue taskQueue() {
        return QueueBuilder.durable(QUEUE_TASK)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .withArgument("x-message-ttl", 60000) // 60秒超时
                .build();
    }

    /**
     * 死信队列
     */
    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    /**
     * 绑定主队列
     */
    @Bean
    public Binding taskBinding(Queue taskQueue, DirectExchange chatExchange) {
        return BindingBuilder.bind(taskQueue).to(chatExchange).with(ROUTING_KEY);
    }

    /**
     * 绑定死信队列
     */
    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange chatExchange) {
        return BindingBuilder.bind(dlqQueue).to(chatExchange).with(DLQ_ROUTING_KEY);
    }

    /**
     * 消息转换器 - JSON 格式
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate 配置
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    /**
     * 消费者工厂 - 使用虚拟线程
     *
     * 优势：
     * - 消费者执行 I/O 阻塞（调用 LLM API）时自动卸载
     * - 平台线程可以处理其他消费者
     * - 并发能力大幅提升
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setPrefetchCount(1); // 每次只预取1条，避免流式任务被阻塞
        factory.setConcurrentConsumers(5); // 并发消费者数
        factory.setMaxConcurrentConsumers(50); // 虚拟线程可以支持更多并发

        // 使用虚拟线程任务执行器
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor();
        taskExecutor.setVirtualThreads(true);
        taskExecutor.setThreadNamePrefix("rabbit-consumer-");
        factory.setTaskExecutor(taskExecutor);

        return factory;
    }
}
