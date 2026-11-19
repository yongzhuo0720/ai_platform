package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.schedule.ScheduledTaskGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 孤儿分块只读对账任务（2026-11-19 升级 P2-8）。
 *
 * <p>每天 03:00 扫描"存在 chunk 但所属 doc 已不存在"的孤儿分块。
 * 这些分块仍会被检索命中，返回来源不明的旧内容。</p>
 *
 * <p><b>只告警、不删除</b>：删除数据属于破坏性操作，须人工评估后另行实现。
 * 本任务仅打 warn 日志，让运维发现数据不一致。</p>
 *
 * <p>多实例下用 {@link ScheduledTaskGuard} 互斥，避免 N 个副本重复扫描告警。</p>
 */
@Slf4j
@Component
public class KbOrphanChunkAuditScheduler {

    private static final long LOCK_TTL_SECONDS = 1800L;

    @Autowired
    KbRagHolder kbRagHolder;

    @Autowired
    ScheduledTaskGuard scheduledTaskGuard;

    @Scheduled(cron = "0 0 3 * * *")
    public void auditOrphans() {
        scheduledTaskGuard.run("kb-orphan-chunk-audit", LOCK_TTL_SECONDS, this::auditOnce);
    }

    /** 供单测直接调用，绕过调度与 Redis 锁 */
    void auditOnce() {
        try {
            KbRagHolder.OrphanChunkStats stats = kbRagHolder.auditOrphanChunks();
            if (stats.orphanChunkCount > 0) {
                // 只告警不删除：列出部分 docIid 方便定位，但截断避免日志过长
                log.warn("[KbRag][孤儿分块告警] 发现 {} 个孤儿分块，涉及 {} 个已删除文档。 "
                                + "这些分块仍可能被检索命中。示例 docIid={} 。"
                                + "（本任务只告警不删除，请人工评估是否清理）",
                        stats.orphanChunkCount, stats.orphanDocCount,
                        stats.orphanDocIids == null ? "[]" : stats.orphanDocIids);
            } else {
                log.debug("[KbRag][孤儿分块对账] 未发现孤儿分块");
            }
        } catch (Exception e) {
            // 对账失败只记日志，不影响下一次调度
            log.warn("[KbRag][孤儿分块对账] 扫描失败: {}", e.getMessage());
        }
    }
}
