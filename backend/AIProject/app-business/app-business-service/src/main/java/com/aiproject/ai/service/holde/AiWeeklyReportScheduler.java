package com.aiproject.ai.service.holde;

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

    @Autowired
    AiWeeklyReportHolder holder;

    @Scheduled(cron = "0 0 2 ? * MON")
    public void generateWeekly() {
        try {
            holder.generate(null);
        } catch (Exception e) {
            log.warn("[周报] 定时生成失败: {}", e.getMessage());
        }
    }
}
