package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.bean.AgentRunBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.mapper.AgentRunBeanMapper;
import com.aiproject.ai.service.mapper.AgentStepBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 运行记录的只读边界：归属隔离、跨用户拒绝、步骤按 stepNo 排序。 */
@ExtendWith(MockitoExtension.class)
class AgentRunHolderTest {

    private static final String UID = "u1";

    @Mock
    AgentRunBeanMapper<AgentRunBean> runMapper;

    @Mock
    AgentStepBeanMapper<AgentStepBean> stepMapper;

    @InjectMocks
    AgentRunHolder holder;

    private AgentStepBean step(int no) {
        AgentStepBean s = new AgentStepBean();
        s.setStepNo(no);
        s.setStepType("tool");
        return s;
    }

    @Test
    void list_shouldScopeByOwner() {
        when(runMapper.select(any(PageRequest.class), any(AgentRunBean.class)))
                .thenReturn(new ArrayList<AgentRunBean>());
        holder.list(UID, null);
        ArgumentCaptor<AgentRunBean> where = ArgumentCaptor.forClass(AgentRunBean.class);
        verify(runMapper).select(any(PageRequest.class), where.capture());
        assertEquals(UID, where.getValue().getLastuser());
        assertNull(where.getValue().getAgentIid());
    }

    @Test
    void list_shouldApplyOptionalAgentFilter() {
        when(runMapper.select(any(PageRequest.class), any(AgentRunBean.class)))
                .thenReturn(new ArrayList<AgentRunBean>());
        holder.list(UID, 7L);
        ArgumentCaptor<AgentRunBean> where = ArgumentCaptor.forClass(AgentRunBean.class);
        verify(runMapper).select(any(PageRequest.class), where.capture());
        assertEquals(Long.valueOf(7L), where.getValue().getAgentIid());
    }

    @Test
    void detail_shouldRejectBlankRunKey() {
        Result<AgentRunDetail> result = holder.detail(UID, "  ");
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("运行标识"));
        verify(runMapper, never()).select(any(), any(AgentRunBean.class));
    }

    @Test
    void detail_shouldRejectOtherUsersRun() {
        when(runMapper.select(any(), any(AgentRunBean.class)))
                .thenReturn(Collections.<AgentRunBean>emptyList());
        Result<AgentRunDetail> result = holder.detail(UID, "run-other-user");
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("不属于当前用户"));
        verify(stepMapper, never()).select(any(), any(AgentStepBean.class));
    }

    @Test
    void detail_shouldSortStepsByStepNoAndScopeStepsByOwner() {
        AgentRunBean run = new AgentRunBean();
        run.setRunKey("run-1");
        when(runMapper.select(any(), any(AgentRunBean.class)))
                .thenReturn(Collections.singletonList(run));
        List<AgentStepBean> steps = new ArrayList<AgentStepBean>(
                Arrays.asList(step(3), step(1), step(2)));
        when(stepMapper.select(any(), any(AgentStepBean.class))).thenReturn(steps);

        Result<AgentRunDetail> result = holder.detail(UID, "run-1");

        assertNotNull(result.getDatas());
        assertEquals("run-1", result.getDatas().getRun().getRunKey());
        assertEquals(3, result.getDatas().getSteps().size());
        assertEquals(Integer.valueOf(1), result.getDatas().getSteps().get(0).getStepNo());
        assertEquals(Integer.valueOf(2), result.getDatas().getSteps().get(1).getStepNo());
        assertEquals(Integer.valueOf(3), result.getDatas().getSteps().get(2).getStepNo());

        ArgumentCaptor<AgentStepBean> where = ArgumentCaptor.forClass(AgentStepBean.class);
        verify(stepMapper).select(any(), where.capture());
        assertEquals(UID, where.getValue().getLastuser());
        assertEquals("run-1", where.getValue().getRunKey());
    }

    @Test
    void detail_shouldTolerateNullStepList() {
        when(runMapper.select(any(), any(AgentRunBean.class)))
                .thenReturn(Collections.singletonList(new AgentRunBean()));
        when(stepMapper.select(any(), any(AgentStepBean.class))).thenReturn(null);
        Result<AgentRunDetail> result = holder.detail(UID, "run-1");
        assertNotNull(result.getDatas());
        assertTrue(result.getDatas().getSteps().isEmpty());
    }

    @Test
    void start_shouldPersistOwnedRunningRecord() {
        when(runMapper.install(any(AgentRunBean.class))).thenReturn(1);

        holder.start(UID, 7L, "问题", "run-1");

        ArgumentCaptor<AgentRunBean> saved = ArgumentCaptor.forClass(AgentRunBean.class);
        verify(runMapper).install(saved.capture());
        assertEquals(UID, saved.getValue().getLastuser());
        assertEquals("run-1", saved.getValue().getRunKey());
        assertEquals("running", saved.getValue().getStatus());
        assertEquals("问题", saved.getValue().getQuestion());
        assertEquals(Long.valueOf(0), saved.getValue().getTotalTokens());
        assertNotNull(saved.getValue().getStartedAt());
    }

    @Test
    void addStep_shouldReturnThePersistedBeanIncludingGeneratedId() {
        when(stepMapper.install(any(AgentStepBean.class))).thenAnswer(invocation -> {
            AgentStepBean saved = invocation.getArgument(0);
            saved.setIid(19L);
            return 1;
        });

        AgentStepBean saved = addStep();

        ArgumentCaptor<AgentStepBean> inserted = ArgumentCaptor.forClass(AgentStepBean.class);
        verify(stepMapper).install(inserted.capture());
        assertSame(inserted.getValue(), saved);
        assertEquals(Long.valueOf(19), saved.getIid());
        assertEquals("run-1", saved.getRunKey());
        assertEquals(UID, saved.getLastuser());
        assertEquals(Integer.valueOf(1), saved.getStepNo());
        assertEquals("llm", saved.getStepType());
        assertEquals("回答", saved.getToolResult());
        assertEquals("request-1", saved.getRequestId());
    }

    @Test
    void finish_shouldPersistUsageAndScopeUpdateByOwnerAndRunKey() {
        when(runMapper.update(any(AgentRunBean.class), any(AgentRunBean.class))).thenReturn(1);

        finish();

        ArgumentCaptor<AgentRunBean> patch = ArgumentCaptor.forClass(AgentRunBean.class);
        ArgumentCaptor<AgentRunBean> where = ArgumentCaptor.forClass(AgentRunBean.class);
        verify(runMapper).update(patch.capture(), where.capture());
        assertEquals(UID, where.getValue().getLastuser());
        assertEquals("run-1", where.getValue().getRunKey());
        assertEquals("failed", patch.getValue().getStatus());
        assertEquals(Long.valueOf(15), patch.getValue().getTotalTokens());
        assertEquals(Long.valueOf(3), patch.getValue().getCostCents());
        assertEquals("已断开", patch.getValue().getErrorMessage());
        assertNotNull(patch.getValue().getFinishedAt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void start_shouldRejectUnexpectedAffectedRows(int rows) {
        when(runMapper.install(any(AgentRunBean.class))).thenReturn(rows);
        assertThrows(DataAccessException.class, () -> holder.start(UID, 7L, "问题", "run-1"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void addStep_shouldRejectUnexpectedAffectedRows(int rows) {
        when(stepMapper.install(any(AgentStepBean.class))).thenReturn(rows);
        assertThrows(DataAccessException.class, this::addStep);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, -1})
    void finish_shouldRejectUnexpectedAffectedRows(int rows) {
        when(runMapper.update(any(AgentRunBean.class), any(AgentRunBean.class))).thenReturn(rows);
        assertThrows(DataAccessException.class, this::finish);
    }

    @Test
    void writes_shouldPropagateUniqueConflicts() {
        DuplicateKeyException conflict = new DuplicateKeyException("conflict");
        when(runMapper.install(any(AgentRunBean.class))).thenThrow(conflict);
        when(stepMapper.install(any(AgentStepBean.class))).thenThrow(conflict);
        when(runMapper.update(any(AgentRunBean.class), any(AgentRunBean.class))).thenThrow(conflict);

        assertSame(conflict, assertThrows(DuplicateKeyException.class,
                () -> holder.start(UID, 7L, "问题", "run-1")));
        assertSame(conflict, assertThrows(DuplicateKeyException.class, this::addStep));
        assertSame(conflict, assertThrows(DuplicateKeyException.class, this::finish));
    }

    @Test
    void recoverStale_shouldOnlyTouchTimedOutRunsAndUseStatusCas() {
        long now = 10_000_000L;
        long cutoff = now - 30 * 60 * 1000L;
        AgentRunBean stale = run("run-old", "running", cutoff - 1);
        AgentRunBean fresh = run("run-fresh", "running", cutoff);
        when(runMapper.select(any(PageRequest.class), any(AgentRunBean.class)))
                .thenAnswer(invocation -> {
                    AgentRunBean where = invocation.getArgument(1);
                    return "running".equals(where.getStatus())
                            ? Arrays.asList(stale, fresh)
                            : new ArrayList<AgentRunBean>();
                });
        when(runMapper.update(any(AgentRunBean.class), any(AgentRunBean.class))).thenReturn(1);

        assertEquals(1, holder.recoverStale(cutoff, now, "运行已回收"));

        ArgumentCaptor<AgentRunBean> patch = ArgumentCaptor.forClass(AgentRunBean.class);
        ArgumentCaptor<AgentRunBean> where = ArgumentCaptor.forClass(AgentRunBean.class);
        verify(runMapper).update(patch.capture(), where.capture());
        assertEquals("failed", patch.getValue().getStatus());
        assertEquals("运行已回收", patch.getValue().getErrorMessage());
        assertEquals(Long.valueOf(now), patch.getValue().getFinishedAt());
        assertEquals(Long.valueOf(now - stale.getStartedAt()), patch.getValue().getDurationMs());
        assertEquals("run-old", where.getValue().getRunKey(), "只回收超时的那条");
        assertEquals(UID, where.getValue().getLastuser());
        assertEquals("running", where.getValue().getStatus(), "必须按原状态 CAS，避免覆盖正常收尾");
    }

    @Test
    void recoverStale_shouldNotClaimSuccessWhenCasLosesRace() {
        long now = 10_000_000L;
        long cutoff = now - 30 * 60 * 1000L;
        when(runMapper.select(any(PageRequest.class), any(AgentRunBean.class)))
                .thenAnswer(invocation -> {
                    AgentRunBean where = invocation.getArgument(1);
                    return "running".equals(where.getStatus())
                            ? Collections.singletonList(run("run-old", "running", cutoff - 1))
                            : new ArrayList<AgentRunBean>();
                });
        // 执行循环在扫描之后刚好完成并写了 succeeded：CAS 更新 0 行
        when(runMapper.update(any(AgentRunBean.class), any(AgentRunBean.class))).thenReturn(0);

        assertEquals(0, holder.recoverStale(cutoff, now, "运行已回收"));
    }

    @Test
    void recoverStale_shouldSkipRunsWithoutStartTimeOrKey() {
        long now = 10_000_000L;
        long cutoff = now - 30 * 60 * 1000L;
        AgentRunBean noStart = run("run-no-start", "running", null);
        AgentRunBean noKey = run(null, "running", cutoff - 1);
        when(runMapper.select(any(PageRequest.class), any(AgentRunBean.class)))
                .thenAnswer(invocation -> {
                    AgentRunBean where = invocation.getArgument(1);
                    return "running".equals(where.getStatus())
                            ? Arrays.asList(noStart, noKey)
                            : new ArrayList<AgentRunBean>();
                });

        assertEquals(0, holder.recoverStale(cutoff, now, "运行已回收"));
        verify(runMapper, never()).update(any(AgentRunBean.class), any(AgentRunBean.class));
    }

    private AgentRunBean run(String runKey, String status, Long startedAt) {
        AgentRunBean run = new AgentRunBean();
        run.setRunKey(runKey);
        run.setLastuser(UID);
        run.setStatus(status);
        run.setStartedAt(startedAt);
        return run;
    }

    private AgentStepBean addStep() {
        return holder.addStep(UID, "run-1", 1, 1, "llm", null, null, "回答", "success",
                10L, 5L, 3L, 20L, null, "request-1");
    }

    private void finish() {
        holder.finish(UID, "run-1", "failed", 1, 10L, 5L, 3L, 20L, "", "已断开");
    }

    @Test
    void anonymous_shouldBeRejected() {
        assertNull(holder.list(null, null).getDatas());
        assertNull(holder.detail(null, "run-1").getDatas());
        verify(runMapper, never()).select(any(), any(AgentRunBean.class));
    }
}
