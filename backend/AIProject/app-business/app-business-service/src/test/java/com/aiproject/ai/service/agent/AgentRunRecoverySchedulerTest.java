package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.holde.AgentRunHolder;
import com.aiproject.ai.service.schedule.ScheduledTaskGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunRecoverySchedulerTest {

    private static final long NOW = 10_000_000L;

    @Mock
    AgentRunHolder runHolder;

    /** 无 Redis 的守卫直接执行任务体，等价于单机部署 */
    @Spy
    ScheduledTaskGuard scheduledTaskGuard = new ScheduledTaskGuard();

    @InjectMocks
    AgentRunRecoveryScheduler scheduler;

    @Test
    void recoverStaleRuns_shouldScanRunsOlderThanThirtyMinutes() {
        scheduler.clock = () -> NOW;
        when(runHolder.recoverStale(eq(NOW - 30 * 60 * 1000L), eq(NOW), anyString()))
                .thenReturn(0);

        scheduler.recoverStaleRuns();

        verify(runHolder).recoverStale(NOW - 30 * 60 * 1000L, NOW, "运行超时未完成（超过 30 分钟），已自动回收");
    }
}
