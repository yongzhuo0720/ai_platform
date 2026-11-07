package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.sql.Timestamp;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 调用审计日志写入。
 * <p>
 * 写日志失败不能影响 AI 请求本身，因此这里吞掉异常并记录 warn。
 *
 * <p><b>为什么写库要放到独立线程。</b>
 * 异步请求被容器取消时，Spring 会先对中继线程 {@code Future.cancel(true)}，
 * 再让容器回收响应。中断若落在 Druid 取连接的 {@code lockInterruptibly} 上，
 * 审计会直接丢失；而 HNSP 的 install 内部还会吞掉异常，调用方连重试都察觉不到。
 * 把写入提交到专用线程后，请求线程的中断标志与 JDBC 无关，审计仍能落库。
 * 单线程同时保证写入顺序，异常也不会扩散到调用方。</p>
 */
@Slf4j
@Service
public class AiCallLogHolder {

    /** 调用方等待落库的上限；超时后写入仍在后台继续，不阻塞 AI 请求收尾 */
    private static final long WRITE_WAIT_MS = 5_000L;

    @Autowired
    AiCallLogBeanMapper<AiCallLogBean> callLogBeanMapper;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ai-audit-writer");
            thread.setDaemon(true);
            return thread;
        }
    });

    public void record(final AiCallLogBean entry) {
        if (entry == null) {
            return;
        }
        entry.setCreatetime(new Timestamp(System.currentTimeMillis()));
        if (entry.getEnable() == null) {
            entry.setEnable(true);
        }
        if (entry.getLastuser() == null) {
            entry.setLastuser(entry.getUserId());
        }
        final AiCallLogBean toWrite = entry;
        try {
            Future<?> future = writer.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        callLogBeanMapper.install(toWrite);
                    } catch (Exception e) {
                        log.warn("[AiGateway] 写审计日志失败, uid={}, endpoint={}: {}",
                                toWrite.getUserId(), toWrite.getEndpoint(), e.getMessage());
                    }
                }
            });
            awaitQuietly(future, toWrite);
        } catch (RejectedExecutionException e) {
            log.warn("[AiGateway] 审计线程已关闭，丢弃本次审计, uid={}, endpoint={}",
                    toWrite.getUserId(), toWrite.getEndpoint());
        }
    }

    private void awaitQuietly(Future<?> future, AiCallLogBean entry) {
        try {
            future.get(WRITE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 容器取消异步请求会中断调用线程；任务已经提交，由审计线程完成写入。
            // 这里不恢复中断标志：调用方紧接着还要结算预扣，带着标志访问 Redis 会失败。
            log.debug("[AiGateway] 等待审计落库被中断，写入已转交审计线程, uid={}, endpoint={}",
                    entry.getUserId(), entry.getEndpoint());
        } catch (TimeoutException e) {
            log.warn("[AiGateway] 审计落库超过 {}ms，转入后台继续写入, uid={}, endpoint={}",
                    WRITE_WAIT_MS, entry.getUserId(), entry.getEndpoint());
        } catch (ExecutionException e) {
            // 写线程内部已兜住异常，这里只做防御
            log.warn("[AiGateway] 审计写入线程异常, uid={}, endpoint={}: {}",
                    entry.getUserId(), entry.getEndpoint(), e.getCause());
        }
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[AiGateway] 审计写入线程已关闭");
    }
}
