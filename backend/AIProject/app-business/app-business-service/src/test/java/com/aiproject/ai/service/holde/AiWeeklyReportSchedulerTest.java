package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.schedule.ScheduledTaskGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 周报定时任务的单元测试。
 *
 * <p>@Scheduled 方法抛异常会中断当次调度并打出噪音日志；周报失败已经有补偿路径
 * （下次调度重跑、唯一约束防重），所以这里必须吞掉异常继续调度。</p>
 */
@ExtendWith(MockitoExtension.class)
class AiWeeklyReportSchedulerTest {

    @Mock
    AiWeeklyReportHolder holder;

    /** 无 Redis 的守卫直接执行任务体，等价于单机部署 */
    @Spy
    ScheduledTaskGuard scheduledTaskGuard = new ScheduledTaskGuard();

    @InjectMocks
    AiWeeklyReportScheduler scheduler;

    @Test
    void generateWeekly_shouldDelegateToHolder() {
        scheduler.generateWeekly();

        verify(holder, times(1)).generate(isNull());
    }

    @Test
    void generateWeekly_shouldSwallowFailureAndKeepScheduling() {
        when(holder.generate(isNull())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> scheduler.generateWeekly());

        verify(holder, times(1)).generate(isNull());
    }
}
