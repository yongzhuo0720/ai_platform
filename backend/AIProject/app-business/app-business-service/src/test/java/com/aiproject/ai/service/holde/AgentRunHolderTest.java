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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void anonymous_shouldBeRejected() {
        assertNull(holder.list(null, null).getDatas());
        assertNull(holder.detail(null, "run-1").getDatas());
        verify(runMapper, never()).select(any(), any(AgentRunBean.class));
    }
}
