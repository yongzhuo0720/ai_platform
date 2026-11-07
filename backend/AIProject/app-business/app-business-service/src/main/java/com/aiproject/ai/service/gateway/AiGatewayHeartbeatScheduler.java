package com.aiproject.ai.service.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 心跳的调度线程池。
 *
 * <p>为什么心跳不能由中继线程「读超时醒来后顺手写」：中继线程一旦阻塞在
 * {@code InputStream.read()} 上，既无法定时醒来写心跳，也就发现不了浏览器已经关闭。
 * 上游发完响应头就彻底静默时，额度要等预扣回收、上游连接要等读超时才释放。</p>
 *
 * <p>因此心跳改由这里的独立线程按固定间隔写：写失败即说明页面已关，由中继侧关闭上游
 * 唤醒阻塞的读。线程数固定且为守护线程，不随请求量增长。</p>
 */
@Slf4j
@Component
public class AiGatewayHeartbeatScheduler {

    /** 心跳只写 8 字节，两个线程足够；写慢客户端时也不会把所有心跳串死在一根线程上 */
    private static final int THREADS = 2;

    private final ScheduledThreadPoolExecutor executor;

    public AiGatewayHeartbeatScheduler() {
        executor = new ScheduledThreadPoolExecutor(THREADS, new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "ai-gateway-heartbeat-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /**
     * 按固定间隔重复执行心跳。
     *
     * @param task 心跳动作；一次执行耗时超过间隔也不会堆积（固定延迟而非固定频率）
     * @param intervalMs 间隔毫秒
     */
    public ScheduledFuture<?> schedule(Runnable task, long intervalMs) {
        return executor.scheduleWithFixedDelay(task, intervalMs, intervalMs,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        log.info("[AiGateway] 流式心跳调度器已关闭");
    }
}