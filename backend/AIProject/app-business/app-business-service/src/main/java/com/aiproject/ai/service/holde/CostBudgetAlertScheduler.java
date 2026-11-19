package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.aiproject.ai.service.bean.NotificationBean;
import com.aiproject.ai.service.gateway.CostBudgetEvaluator;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import com.aiproject.ai.service.notification.NotificationTypes;
import com.aiproject.ai.service.schedule.ScheduledTaskGuard;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.SqlFieldTools;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日成本预算告警：每天 02:00 扫描「昨日」AI 调用成本，按用户汇总后与预算比对，
 * 达到阈值发 warning、超预算发 error 站内通知。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>扫昨日而非当日：昨日数据已完整结算，告警口径稳定；语义是"昨日账单"。</li>
 *   <li>eventKey 带日期 + 级别（{@code cost-budget:{uid}:{date}:{level}}），靠通知中心唯一索引幂等，
 *       同一用户同一天的同级告警只发一条，重复调度不会轰炸用户。</li>
 *   <li>多实例互斥：复用 {@link ScheduledTaskGuard}，避免 N 个副本各发一遍。</li>
 *   <li>fail-safe：聚合/通知任何异常只记日志，不影响主调度（@Scheduled 抛异常会跳过当次）。</li>
 * </ul>
 */
@Slf4j
@Component
public class CostBudgetAlertScheduler {

    private static final long LOCK_TTL_SECONDS = 1800L;
    private static final int SCAN_PAGE_SIZE = 500;
    private static final int MAX_PAGES = 200;

    @Autowired
    AiCallLogBeanMapper<AiCallLogBean> callLogBeanMapper;

    @Autowired
    AiUserQuotaHolder quotaHolder;

    @Autowired
    NotificationHolder notificationHolder;

    @Autowired
    CostBudgetEvaluator evaluator;

    @Autowired
    ScheduledTaskGuard scheduledTaskGuard;

    @Scheduled(cron = "0 0 2 * * *")
    public void checkDailyBudget() {
        scheduledTaskGuard.run("cost-budget-alert", LOCK_TTL_SECONDS, this::checkOnce);
    }

    /** 供单测直接调用，绕过调度与 Redis 锁 */
    void checkOnce() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Map<String, Long> costByUser = aggregateCostByUser(yesterday);
        if (costByUser.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Long> entry : costByUser.entrySet()) {
            String userId = entry.getKey();
            long usedMilliCents = entry.getValue();
            try {
                AiUserQuotaBean quota = quotaHolder.find(userId);
                if (quota == null) {
                    continue; // 没有配额记录 = 用默认配置，默认不设成本预算
                }
                CostBudgetEvaluator.Level level = evaluator.evaluate(
                        usedMilliCents, quota.getDailyCostBudgetCents(), quota.getAlertThresholdPct());
                if (level == CostBudgetEvaluator.Level.NONE) {
                    continue;
                }
                publishAlert(userId, yesterday, level, usedMilliCents, quota.getDailyCostBudgetCents());
            } catch (Exception e) {
                log.warn("[成本预算] 用户 {} 告警处理失败: {}", userId, e.getMessage());
            }
        }
    }

    /** 按用户汇总某天的成本（毫分）。只读分页扫描，口径与看板 scanLogs 一致。 */
    Map<String, Long> aggregateCostByUser(LocalDate day) {
        Map<String, Long> result = new HashMap<String, Long>();
        String timeCondition = SqlFieldTools.timeBetween(
                callLogBeanMapper.getTableName(), "createtime",
                day.toString() + " 00:00:00", day.toString() + " 23:59:59");
        for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
            PageRequest page = new PageRequest(pageNum, SCAN_PAGE_SIZE);
            Result<PageResult<AiCallLogBean>> rows = callLogBeanMapper.selectPage(page, new AiCallLogBean(), timeCondition);
            if (rows == null || rows.getDatas() == null) {
                break;
            }
            List<AiCallLogBean> content = rows.getDatas().getContent();
            if (content == null || content.isEmpty()) {
                break;
            }
            for (AiCallLogBean row : content) {
                String uid = row.getUserId();
                if (uid == null || uid.trim().isEmpty()) {
                    continue;
                }
                long cost = row.getCostCents() == null ? 0L : row.getCostCents();
                Long prev = result.get(uid);
                result.put(uid, (prev == null ? 0L : prev) + cost);
            }
            if (content.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        return result;
    }

    private void publishAlert(String userId, LocalDate day, CostBudgetEvaluator.Level level,
                              long usedMilliCents, Long budgetCents) {
        boolean exceeded = level == CostBudgetEvaluator.Level.EXCEEDED;
        String levelName = exceeded ? "超预算" : "接近预算";
        // eventKey 带日期与级别 → 通知中心唯一索引保证幂等，重复调度不会重复发
        String eventKey = "cost-budget:" + userId + ":" + day.toString()
                + ":" + (exceeded ? "exceeded" : "warning");
        NotificationBean notice = new NotificationBean();
        notice.setEventKey(eventKey);
        notice.setCategory(NotificationTypes.CATEGORY_SYSTEM);
        notice.setLevel(exceeded ? NotificationTypes.LEVEL_ERROR : NotificationTypes.LEVEL_WARNING);
        notice.setTitle("AI 成本" + levelName + "提醒（" + day + "）");
        notice.setContent("昨日 AI 调用成本 " + evaluator.toYuan(usedMilliCents) + " 元，"
                + levelName + "（预算 " + budgetCents + " 分）。请检查用量或调整预算。");
        notice.setLink("/admin/ai-gateway");
        boolean sent = notificationHolder.push(userId, notice);
        log.info("[成本预算] 用户 {} {} 告警 sent={}, used={}毫分 budget={}分",
                userId, levelName, sent, usedMilliCents, budgetCents);
    }
}
