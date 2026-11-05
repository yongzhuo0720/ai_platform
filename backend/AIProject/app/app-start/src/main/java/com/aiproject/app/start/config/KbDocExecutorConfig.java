package com.aiproject.app.start.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 文档处理专用线程池。
 *
 * <p>文档入库是「分批 embedding + 大量数据库写入」的 IO 型任务，不能占用 Web 线程。
 * 队列有界（20），满了直接拒绝并让用户重试：无界队列会让上传请求看起来已受理，
 * 实际要等几十分钟才开始处理。</p>
 *
 * <p>并发上限 2：与评估任务共用同一向量服务，避免同时打爆上游；
 * 单用户同时最多 3 篇（Holder 校验），多用户共享 2 个执行线程。</p>
 */
@Configuration
public class KbDocExecutorConfig {

    @Bean(name = "kbDocExecutor")
    public ThreadPoolTaskExecutor kbDocExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("kb-doc-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
