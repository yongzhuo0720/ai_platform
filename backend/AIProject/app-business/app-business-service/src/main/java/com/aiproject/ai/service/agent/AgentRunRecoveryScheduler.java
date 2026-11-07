package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.holde.AgentRunHolder;
import com.aiproject.ai.service.schedule.ScheduledTaskGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 回收僵死的 Agent 运行记录。
 *
 * <p>AgentRunner 的总时长上限是 2 分钟，正常情况下不会有一条 running 记录
 * 存活超过 30 分钟。超过这个时间还停在 queued/running，只可能是进程重启、
 * 线程被强制终止或执行循环没能走到 finally——不回收就会永久占住
 * idx_agentrun_active，该用户在该 Agent 上再也发不出新一轮请求。</p>
 *
 * <p>多实例下用 Redis 锁只让一个实例扫描；锁 TTL 小于 5 分钟调度间隔，
 * 保证下一轮一定能重新抢锁。回收本身按状态 CAS，Redis 故障时重复扫描也无害。</p>
 */
@Slf4j
@Component
public class AgentRunRecoveryScheduler {

    private static final long STALE_RUN_MS = 30 * 60 * 1000L;
    private static final long LOCK_TTL_SECONDS = 240L;
    private static final String RECOVERY_MESSAGE = "运行超时未完成（超过 30 分钟），已自动回收";

    @Autowired
    AgentRunHolder runHolder;

    @Autowired
    ScheduledTaskGuard scheduledTaskGuard;

    /** 单测可替换的时钟 */
    LongSupplier clock = System::currentTimeMillis;

    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 3 * 60 * 1000)
    public void recoverStaleRuns() {
        scheduledTaskGuard.run("agent-run-stale-recovery", LOCK_TTL_SECONDS, () -> {
            long now = clock.getAsLong();
            int recovered = runHolder.recoverStale(now - STALE_RUN_MS, now, RECOVERY_MESSAGE);
            if (recovered > 0) {
                log.warn("[Agent] 本轮回收 {} 条僵死运行记录", recovered);
            }
        });
    }
}
