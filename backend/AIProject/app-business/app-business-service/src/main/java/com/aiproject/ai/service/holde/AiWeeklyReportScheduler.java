package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.schedule.ScheduledTaskGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周报定时任务：每周一 02:00 生上一自然周的 AI 调用周报。
 *
 * <p>生成失败只记日志、不影响下一次调度（@Scheduled 抛异常会跳过当次并继续）。</p>
 */
@Slf4j
@Component
public class AiWeeklyReportScheduler {

    /** 周报全量聚合可能跑几分钟，给足 TTL 又不至于挂死后永久占锁 */
    private static final long LOCK_TTL_SECONDS = 1800L;

    @Autowired
    AiWeeklyReportHolder holder;

    @Autowired
    ScheduledTaskGuard scheduledTaskGuard;

    @Scheduled(cron = "0 0 2 ? * MON")
    public void generateWeekly() {
        // 多实例下只让一个实例生成；周报本身有唯一约束，Redis 故障时重复执行也无害
        scheduledTaskGuard.run("weekly-report", LOCK_TTL_SECONDS, this::generateWeeklyOnce);
    }

    void generateWeeklyOnce() {
        try {
            holder.generate(null);
        } catch (Exception e) {
            log.warn("[周报] 定时生成失败: {}", e.getMessage());
        }
    }
}
