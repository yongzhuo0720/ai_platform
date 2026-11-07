package com.aiproject.app.start.config;

import com.aiproject.ai.service.gateway.AiGatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 网关转发线程池。
 *
 * <p>网关的 IO（等模型吐字、等 Dify 上传）全部是阻塞型等待，放在 Tomcat
 * 请求线程里会直接挤占登录、后台等无关键口。默认 8~48 个中继线程、
 * 有界队列 128：超出的请求由调用线程执行（等价于改造前的同步转发），
 * 既不丢请求，也不会无限堆积。</p>
 *
 * <p>线程池只创建实际用到的线程；空闲线程不设回收，避免每次对话都新建线程。</p>
 */
@Configuration
public class AiGatewayExecutorConfig {

    @Bean(name = "aiGatewayExecutor")
    public ThreadPoolTaskExecutor aiGatewayExecutor(AiGatewayProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, properties.getRelayCoreThreads()));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(),
                properties.getRelayMaxThreads()));
        executor.setQueueCapacity(Math.max(0, properties.getRelayQueueCapacity()));
        executor.setThreadNamePrefix("ai-gateway-");
        // 队列满时在调用线程（Tomcat 请求线程）执行：退化为同步转发，
        // 而不是让用户看到一个因线程池满产生的 500。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
