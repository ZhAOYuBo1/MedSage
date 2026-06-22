package com.medsage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

/**
 * 线程池配置
 * 用于控制 Agent 并发调用
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * 聊天线程池
     * - 核心5线程，最大10线程
     * - 队列100，满时由调用线程执行
     */
    @Bean
    public ExecutorService chatExecutor() {
        return new ThreadPoolExecutor(
                5,
                10,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "chat-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
