package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.aiproject.ai.service.bean.NotificationBean;
import com.aiproject.ai.service.gateway.CostBudgetEvaluator;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 成本预算告警调度层测试：聚合口径、告警触发、eventKey 幂等、fail-safe。 */
@ExtendWith(MockitoExtension.class)
class CostBudgetAlertSchedulerTest {

    @Mock
    AiCallLogBeanMapper<AiCallLogBean> callLogBeanMapper;

    @Mock
    AiUserQuotaHolder quotaHolder;

    @Mock
    NotificationHolder notificationHolder;

    @Spy
    CostBudgetEvaluator evaluator = new CostBudgetEvaluator();

    @InjectMocks
    CostBudgetAlertScheduler scheduler;

    private AiCallLogBean log(String uid, long costMilli) {
        AiCallLogBean row = new AiCallLogBean();
        row.setUserId(uid);
        row.setCostCents(costMilli);
        return row;
    }

    private AiUserQuotaBean quota(long budgetCents, int thresholdPct) {
        AiUserQuotaBean q = new AiUserQuotaBean();
        q.setDailyCostBudgetCents(budgetCents);
        q.setAlertThresholdPct(thresholdPct);
        return q;
    }

    @SuppressWarnings("unchecked")
    private void stubLogs(AiCallLogBean... rows) {
        PageResult<AiCallLogBean> page = new PageResult<AiCallLogBean>();
        page.setContent(Arrays.asList(rows));
        when(callLogBeanMapper.selectPage(any(), any(AiCallLogBean.class), anyString()))
                .thenReturn(Result.succeed(page, "ok"));
    }

    @Test
    void aggregateCostByUser_groupsByUserInMilliCents() {
        stubLogs(log("u1", 30_000L), log("u1", 20_000L), log("u2", 5_000L), log(null, 999L));

        Map<String, Long> cost = scheduler.aggregateCostByUser(LocalDate.now().minusDays(1));

        assertEquals(Long.valueOf(50_000L), cost.get("u1"), "同一用户成本累加");
        assertEquals(Long.valueOf(5_000L), cost.get("u2"));
        assertFalse(cost.containsKey(null), "无 uid 的日志不计入");
    }

    @Test
    void shouldSendWarningWithIdempotentEventKeyAndYuanInContent() {
        stubLogs(log("u1", 80_000L)); // 80 分
        when(quotaHolder.find("u1")).thenReturn(quota(100L, 80));
        when(notificationHolder.push(eq("u1"), any(NotificationBean.class))).thenReturn(true);

        scheduler.checkOnce();

        ArgumentCaptor<NotificationBean> captor = ArgumentCaptor.forClass(NotificationBean.class);
        verify(notificationHolder, times(1)).push(eq("u1"), captor.capture());
        NotificationBean notice = captor.getValue();
        assertTrue(notice.getEventKey().startsWith("cost-budget:u1:"), "eventKey 带用户与日期，保证幂等");
        assertTrue(notice.getEventKey().endsWith(":warning"));
        assertTrue(notice.getContent().contains("0.80"), "内容含可读的元金额 0.80");
    }

    @Test
    void shouldSendErrorWhenExceedBudget() {
        stubLogs(log("u1", 120_000L)); // 120 分，超 100 分预算
        when(quotaHolder.find("u1")).thenReturn(quota(100L, 80));
        when(notificationHolder.push(eq("u1"), any(NotificationBean.class))).thenReturn(true);

        scheduler.checkOnce();

        ArgumentCaptor<NotificationBean> captor = ArgumentCaptor.forClass(NotificationBean.class);
        verify(notificationHolder).push(eq("u1"), captor.capture());
        assertTrue(captor.getValue().getEventKey().endsWith(":exceeded"));
    }

    @Test
    void noBudgetConfig_noAlert() {
        stubLogs(log("u1", 500_000L));
        when(quotaHolder.find("u1")).thenReturn(quota(0L, 80)); // 0 = 不限

        scheduler.checkOnce();

        verify(notificationHolder, never()).push(anyString(), any(NotificationBean.class));
    }

    @Test
    void belowThreshold_noAlert() {
        stubLogs(log("u1", 50_000L)); // 50 分，低于 80 分阈值
        when(quotaHolder.find("u1")).thenReturn(quota(100L, 80));

        scheduler.checkOnce();

        verify(notificationHolder, never()).push(anyString(), any(NotificationBean.class));
    }

    @Test
    void oneUserFailure_doesNotBlockOtherUsers() {
        stubLogs(log("u1", 90_000L), log("u2", 90_000L));
        when(quotaHolder.find("u1")).thenReturn(quota(100L, 80));
        when(quotaHolder.find("u2")).thenThrow(new RuntimeException("db down"));
        when(notificationHolder.push(eq("u1"), any(NotificationBean.class))).thenReturn(true);

        assertDoesNotThrow(() -> scheduler.checkOnce());

        verify(notificationHolder, times(1)).push(eq("u1"), any(NotificationBean.class));
    }

    @Test
    void emptyLogs_noQueryNoAlert() {
        stubLogs(); // 空日志

        scheduler.checkOnce();

        verifyNoInteractions(quotaHolder, notificationHolder);
    }
}
