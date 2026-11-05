package com.aiproject.app.start.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * RAG 评估的专用线程池。
 *
 * <p>评估是「大量串行 embedding + 检索」的 IO 型任务，不能占用 Web 请求线程。
 * 队列有界（20），队伍满时直接拒绝而不是无限堆积：一次评估最多 200 题，
 * 无界队列会让用户以为任务已排队，实际几十分钟后才开始跑。</p>
 *
 * <p>并发上限 2：同一用户同时最多 2 个活动运行（Holder 已校验），
 * 多个用户共享 2 个执行线程可以避免同时打爆向量服务。</p>
 */
@Configuration
public class RagEvalExecutorConfig {

    @Bean(name = "ragEvalExecutor")
    public ThreadPoolTaskExecutor ragEvalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("rag-eval-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
