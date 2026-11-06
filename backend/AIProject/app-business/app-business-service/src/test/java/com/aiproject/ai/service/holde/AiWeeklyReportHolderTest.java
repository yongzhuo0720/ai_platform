package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.AiWeeklyReportBean;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import com.aiproject.ai.service.mapper.AiWeeklyReportBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 周报写入路径：同周重复生成必须「更新」而不是「先删后插」，
 * 并发首次生成由唯一索引兜底后转为更新赢家行。
 */
@ExtendWith(MockitoExtension.class)
class AiWeeklyReportHolderTest {

    @Mock
    AiCallLogBeanMapper<AiCallLogBean> callLogBeanMapper;

    @Mock
    AiWeeklyReportBeanMapper<AiWeeklyReportBean> reportMapper;

    @InjectMocks
    AiWeeklyReportHolder holder;

    @Test
    void regenerateSameWeekUpdatesExistingRowInsteadOfDeletingIt() {
        stubWeekRows();
        AiWeeklyReportBean existing = existing(99L);
        when(reportMapper.select(isNull(), any(AiWeeklyReportBean.class)))
                .thenReturn(Collections.singletonList(existing));
        when(reportMapper.update(any(AiWeeklyReportBean.class), any(AiWeeklyReportBean.class)))
                .thenReturn(1);

        Result<AiWeeklyReportBean> result = holder.generate("2026-10-28");

        assertTrue(ok(result), result.getResp_msg());
        verify(reportMapper, never()).delete(any(AiWeeklyReportBean.class));
        verify(reportMapper, never()).install(any(AiWeeklyReportBean.class));

        ArgumentCaptor<AiWeeklyReportBean> payload =
                ArgumentCaptor.forClass(AiWeeklyReportBean.class);
        ArgumentCaptor<AiWeeklyReportBean> condition =
                ArgumentCaptor.forClass(AiWeeklyReportBean.class);
        verify(reportMapper).update(payload.capture(), condition.capture());

        assertEquals(Long.valueOf(99), condition.getValue().getIid(),
                "条件更新必须落在原快照行上，不能按周区间批量改");
        assertEquals("2026-10-26", payload.getValue().getWeekStart());
        assertEquals("2026-11-01", payload.getValue().getWeekEnd());
        assertEquals(Long.valueOf(3), payload.getValue().getTotalCalls());
        assertEquals(Long.valueOf(2), payload.getValue().getSuccessCalls());
        assertEquals(Long.valueOf(1), payload.getValue().getFailedCalls());
        assertEquals(Long.valueOf(350), payload.getValue().getTotalTokens());
        assertEquals(Long.valueOf(35), payload.getValue().getCostCents());
        assertEquals(Long.valueOf(200), payload.getValue().getAvgLatencyMs());
        assertEquals(Long.valueOf(50), payload.getValue().getAvgFirstTokenMs());
        assertEquals("u1", payload.getValue().getTopUser());
        assertEquals("m1", payload.getValue().getTopModel());
        assertEquals("system", payload.getValue().getLastuser());
    }

    @Test
    void firstGenerationInsertsSingleSnapshot() {
        stubWeekRows();
        when(reportMapper.select(isNull(), any(AiWeeklyReportBean.class)))
                .thenReturn(Collections.<AiWeeklyReportBean>emptyList());
        when(reportMapper.install(any(AiWeeklyReportBean.class))).thenReturn(1);

        Result<AiWeeklyReportBean> result = holder.generate("2026-10-26");

        assertTrue(ok(result), result.getResp_msg());
        verify(reportMapper, never()).update(any(AiWeeklyReportBean.class), any(AiWeeklyReportBean.class));
        ArgumentCaptor<AiWeeklyReportBean> captor =
                ArgumentCaptor.forClass(AiWeeklyReportBean.class);
        verify(reportMapper).install(captor.capture());
        assertEquals("2026-10-26", captor.getValue().getWeekStart());
        assertEquals("2026-11-01", captor.getValue().getWeekEnd());
        assertEquals(Long.valueOf(3), captor.getValue().getTotalCalls());
    }

    /**
     * 手动触发撞上定时任务：两边都没查到快照，唯一索引只放行一条，
     * 后到者必须重新读取赢家行并更新，不能把重复键异常当成生成失败。
     */
    @Test
    void concurrentInsertConflictFallsBackToUpdatingWinner() {
        stubWeekRows();
        AiWeeklyReportBean winner = existing(7L);
        when(reportMapper.select(isNull(), any(AiWeeklyReportBean.class)))
                .thenReturn(Collections.<AiWeeklyReportBean>emptyList())
                .thenReturn(Collections.singletonList(winner));
        when(reportMapper.install(any(AiWeeklyReportBean.class)))
                .thenThrow(new RuntimeException("duplicate key value violates unique constraint"));
        when(reportMapper.update(any(AiWeeklyReportBean.class), any(AiWeeklyReportBean.class)))
                .thenReturn(1);

        Result<AiWeeklyReportBean> result = holder.generate("2026-10-26");

        assertTrue(ok(result), result.getResp_msg());
        ArgumentCaptor<AiWeeklyReportBean> condition =
                ArgumentCaptor.forClass(AiWeeklyReportBean.class);
        verify(reportMapper).update(any(AiWeeklyReportBean.class), condition.capture());
        assertEquals(Long.valueOf(7), condition.getValue().getIid());
    }

    @Test
    void failedUpdateDoesNotClaimSuccess() {
        stubWeekRows();
        when(reportMapper.select(isNull(), any(AiWeeklyReportBean.class)))
                .thenReturn(Collections.singletonList(existing(99L)));
        when(reportMapper.update(any(AiWeeklyReportBean.class), any(AiWeeklyReportBean.class)))
                .thenReturn(0);

        Result<AiWeeklyReportBean> result = holder.generate("2026-10-26");

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg() != null && result.getResp_msg().contains("保存失败"));
        verify(reportMapper, never()).install(any(AiWeeklyReportBean.class));
        verify(reportMapper, never()).delete(any(AiWeeklyReportBean.class));
    }

    @Test
    void invalidDateIsRejectedBeforeAnyQuery() {
        Result<AiWeeklyReportBean> result = holder.generate("2026/10/26");

        assertNull(result.getDatas());
        verifyNoInteractions(callLogBeanMapper, reportMapper);
    }

    /**
     * 旧实现停在 while (pageNum <= 100)：第 100 页之后的调用记录被静默丢弃，
     * 周报看起来正常、数字却少了一截。现在必须一直翻到不满一页为止。
     */
    @Test
    void aggregatesBeyondLegacyFiftyThousandRowCap() {
        List<AiCallLogBean> fullPage = rows(500);
        List<AiCallLogBean> tailPage = rows(3);
        AtomicInteger pageCalls = new AtomicInteger();
        when(callLogBeanMapper.selectPage(any(PageRequest.class),
                any(AiCallLogBean.class), anyString()))
                .thenAnswer(invocation -> Result.succeed(
                        page(pageCalls.incrementAndGet() <= 100 ? fullPage : tailPage, 0),
                        "查询成功"));
        when(reportMapper.select(isNull(), any(AiWeeklyReportBean.class)))
                .thenReturn(Collections.<AiWeeklyReportBean>emptyList());
        when(reportMapper.install(any(AiWeeklyReportBean.class))).thenReturn(1);

        Result<AiWeeklyReportBean> result = holder.generate("2026-10-26");

        assertTrue(ok(result), result.getResp_msg());
        ArgumentCaptor<AiWeeklyReportBean> captor =
                ArgumentCaptor.forClass(AiWeeklyReportBean.class);
        verify(reportMapper).install(captor.capture());
        assertEquals(Long.valueOf(100L * 500 + 3), captor.getValue().getTotalCalls(),
                "第 101 页的 3 条记录也必须计入");
        assertEquals(101, pageCalls.get());
    }

    /** 超过安全上限时返回失败，且绝不落一份「只统计了一部分」的快照 */
    @Test
    void refusesToPersistWhenWeekExceedsSafetyScanLimit() {
        when(callLogBeanMapper.selectPage(any(PageRequest.class),
                any(AiCallLogBean.class), anyString()))
                .thenReturn(Result.succeed(page(rows(500), 0), "查询成功"));

        Result<AiWeeklyReportBean> result = holder.generate("2026-10-26");

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg() != null && result.getResp_msg().contains("统计上限"));
        verify(callLogBeanMapper, times(AiWeeklyReportHolder.MAX_PAGES))
                .selectPage(any(PageRequest.class), any(AiCallLogBean.class), anyString());
        verify(reportMapper, never()).select(isNull(), any(AiWeeklyReportBean.class));
        verify(reportMapper, never()).install(any(AiWeeklyReportBean.class));
        verify(reportMapper, never()).update(any(AiWeeklyReportBean.class), any(AiWeeklyReportBean.class));
    }

    /** 翻页中途读失败同样不能落库：部分页的合计不是整周数据 */
    @Test
    void failsInsteadOfPersistingPartialSnapshotWhenPagingFailsMidway() {
        when(callLogBeanMapper.selectPage(any(PageRequest.class),
                any(AiCallLogBean.class), anyString()))
                .thenReturn(Result.succeed(page(rows(500), 0), "查询成功"))
                .thenReturn(Result.failed(null, "数据库连接中断"));

        Result<AiWeeklyReportBean> result = holder.generate("2026-10-26");

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg() != null && result.getResp_msg().contains("读取调用日志失败"));
        verify(reportMapper, never()).install(any(AiWeeklyReportBean.class));
        verify(reportMapper, never()).update(any(AiWeeklyReportBean.class), any(AiWeeklyReportBean.class));
    }

    private static AiWeeklyReportBean existing(Long iid) {
        AiWeeklyReportBean bean = new AiWeeklyReportBean();
        bean.setIid(iid);
        bean.setWeekStart("2026-10-19");
        bean.setWeekEnd("2026-10-25");
        bean.setTotalCalls(1L);
        return bean;
    }

    /** 一周：2 次成功 + 1 次失败；token/成本/耗时都取平均值口径 */
    private void stubWeekRows() {
        AiCallLogBean ok1 = row("success", "u1", "m1", 100L, 40L, 100L, 10L);
        AiCallLogBean ok2 = row("success", "u1", "m1", 200L, 60L, 300L, 20L);
        AiCallLogBean blocked = row("blocked", "u2", "m2", 50L, null, 200L, 5L);

        PageResult<AiCallLogBean> page = new PageResult<AiCallLogBean>();
        page.setContent(java.util.Arrays.asList(ok1, ok2, blocked));
        page.setTotalSize(3);
        when(callLogBeanMapper.selectPage(any(PageRequest.class),
                any(AiCallLogBean.class), anyString()))
                .thenReturn(Result.succeed(page, "查询成功"));
    }

    private static AiCallLogBean row(String status, String userId, String modelName,
                                     Long tokens, Long firstTokenMs, Long latencyMs,
                                     Long costCents) {
        AiCallLogBean bean = new AiCallLogBean();
        bean.setStatus(status);
        bean.setUserId(userId);
        bean.setModelName(modelName);
        bean.setTotalTokens(tokens);
        bean.setFirstTokenMs(firstTokenMs);
        bean.setLatencyMs(latencyMs);
        bean.setCostCents(costCents);
        return bean;
    }

    private static List<AiCallLogBean> rows(int count) {
        List<AiCallLogBean> list = new ArrayList<AiCallLogBean>(count);
        for (int i = 0; i < count; i++) {
            list.add(row("success", "u1", "m1", null, null, null, null));
        }
        return list;
    }

    private static PageResult<AiCallLogBean> page(List<AiCallLogBean> content, int total) {
        PageResult<AiCallLogBean> page = new PageResult<AiCallLogBean>();
        page.setContent(content);
        page.setTotalSize(total);
        return page;
    }

    private static boolean ok(Result<?> result) {
        return result != null && Integer.valueOf(0).equals(result.getResp_code());
    }
}
