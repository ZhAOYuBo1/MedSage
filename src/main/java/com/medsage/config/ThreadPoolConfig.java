package com.medsage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

/**
 * 线程池配置
 * 使用虚拟线程提升并发能力
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * 聊天线程池 - 虚拟线程
     *
     * 优势：
     * - 并发能力从 10 提升到 1000+
     * - I/O 阻塞时自动卸载，不占用平台线程
     * - 适合调用 LLM API 等 I/O 密集型任务
     */
    @Bean
    public ExecutorService chatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
