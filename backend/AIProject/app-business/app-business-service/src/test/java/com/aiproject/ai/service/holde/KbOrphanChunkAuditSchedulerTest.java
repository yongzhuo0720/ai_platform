package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.schedule.ScheduledTaskGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/** 孤儿分块对账定时任务：验证委托对账、异常吞噬、多实例锁。 */
@ExtendWith(MockitoExtension.class)
class KbOrphanChunkAuditSchedulerTest {

    @Mock
    KbRagHolder kbRagHolder;

    @Spy
    ScheduledTaskGuard scheduledTaskGuard = new ScheduledTaskGuard();

    @InjectMocks
    KbOrphanChunkAuditScheduler scheduler;

    private KbRagHolder.OrphanChunkStats stats(long orphanChunks, long orphanDocs) {
        KbRagHolder.OrphanChunkStats s = new KbRagHolder.OrphanChunkStats();
        s.orphanChunkCount = orphanChunks;
        s.orphanDocCount = orphanDocs;
        s.orphanDocIids = Collections.singleton(99L);
        return s;
    }

    @Test
    void auditOnce_shouldDelegateToHolder() {
        when(kbRagHolder.auditOrphanChunks()).thenReturn(stats(0, 0));
        scheduler.auditOnce();
        verify(kbRagHolder, times(1)).auditOrphanChunks();
    }

    @Test
    void auditOnce_shouldLogOrphansButNotThrow() {
        when(kbRagHolder.auditOrphanChunks()).thenReturn(stats(3, 1));
        assertDoesNotThrow(() -> scheduler.auditOnce());
    }

    @Test
    void auditOnce_shouldSwallowFailureAndKeepScheduling() {
        when(kbRagHolder.auditOrphanChunks()).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> scheduler.auditOnce(),
                "对账失败只记日志，不能中断后续调度");
    }

    @Test
    void audit_shouldGoThroughMultiInstanceGuard() {
        when(kbRagHolder.auditOrphanChunks()).thenReturn(stats(0, 0));
        scheduler.auditOrphans(); // 走 ScheduledTaskGuard
        verify(kbRagHolder, times(1)).auditOrphanChunks();
    }
}
