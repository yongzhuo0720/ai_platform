package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.agent.dto.AgentResumeRequest;
import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.bean.AgentRunBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.gateway.AiGatewayAsyncDispatcher;
import com.aiproject.ai.service.gateway.AsyncResponseGate;
import com.aiproject.ai.service.holde.AgentRunHolder;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 断线续播：只补发缺失步骤、只读、不重新执行模型。 */
@ExtendWith(MockitoExtension.class)
class AgentRunBeanControllerTest {

    private static final String UID = "u1";
    private static final String KEY = "run-1";

    @Mock
    AgentRunHolder holder;

    @InjectMocks
    AgentRunBeanController controller;

    private AgentStepBean step(int no) {
        AgentStepBean s = new AgentStepBean();
        s.setRunKey(KEY);
        s.setStepNo(no);
        s.setIteration(1);
        s.setStepType("llm");
        s.setStatus("success");
        s.setLastuser(UID);
        return s;
    }

    private AgentRunDetail detail(String status, AgentStepBean... steps) {
        AgentRunBean run = new AgentRunBean();
        run.setRunKey(KEY);
        run.setStatus(status);
        run.setLastuser(UID);
        AgentRunDetail d = new AgentRunDetail();
        d.setRun(run);
        d.setSteps(steps.length == 0 ? Collections.<AgentStepBean>emptyList() : Arrays.asList(steps));
        return d;
    }

    private AgentResumeRequest request(String key, Long lastId) {
        AgentResumeRequest r = new AgentResumeRequest();
        r.setRunKey(key);
        r.setLastEventId(lastId);
        return r;
    }

    private int count(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    @Test
    void resume_shouldOnlyReplayStepsAfterLastEventIdAndFinish() throws Exception {
        when(holder.detail(UID, KEY))
                .thenReturn(Result.succeed(detail("succeeded", step(1), step(2)), "查询成功"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.streamResume(UID, request(KEY, 1L), null, resp);

        String body = resp.getContentAsString();
        assertEquals(1, count(body, "event: agent_step"), "只补发 stepNo > 1 的一步");
        assertTrue(body.contains("id: 2"));
        assertFalse(body.contains("id: 1"));
        assertTrue(body.contains("event: agent_done"));
        assertFalse(body.contains("event: agent_start"), "续播不重发 start，避免前端重复初始化");
    }

    @Test
    void resume_shouldAcceptLastEventIdHeaderWhenBodyOmitsIt() throws Exception {
        when(holder.detail(UID, KEY))
                .thenReturn(Result.succeed(detail("succeeded", step(1), step(2)), "查询成功"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.streamResume(UID, request(KEY, null), "1", resp);

        String body = resp.getContentAsString();
        assertEquals(1, count(body, "event: agent_step"));
        assertTrue(body.contains("id: 2"));
    }

    @Test
    void resume_shouldRejectCrossUserRunWithoutLeakingDetails() throws Exception {
        when(holder.detail(UID, KEY)).thenReturn(Result.failed(null, "运行记录不存在或不属于当前用户"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.streamResume(UID, request(KEY, 0L), null, resp);

        String body = resp.getContentAsString();
        assertTrue(body.contains("event: agent_error"));
        assertTrue(body.contains("运行记录不存在或不属于当前用户"));
        assertFalse(body.contains("event: agent_step"));
    }

    @Test
    void resume_shouldRejectMissingRunKeyAndInvalidEventId() throws Exception {
        MockHttpServletResponse missing = new MockHttpServletResponse();
        controller.streamResume(UID, request("  ", 0L), null, missing);
        assertTrue(missing.getContentAsString().contains("缺少运行标识"));

        MockHttpServletResponse invalid = new MockHttpServletResponse();
        controller.streamResume(UID, request(KEY, -1L), null, invalid);
        assertTrue(invalid.getContentAsString().contains("事件序号不合法"));
    }

    @Test
    void resume_shouldDelegateToAsyncDispatcherWhenAvailable() throws Exception {
        AiGatewayAsyncDispatcher dispatcher = mock(AiGatewayAsyncDispatcher.class);
        controller.asyncDispatcher = dispatcher;
        when(dispatcher.dispatch(eq("agent/streamResume"), any(), any()))
                .thenAnswer(invocation -> {
                    AiGatewayAsyncDispatcher.AsyncRelay relay = invocation.getArgument(2);
                    relay.run(AsyncResponseGate.create());
                    return new WebAsyncTask<Void>(() -> null);
                });
        when(holder.detail(UID, KEY))
                .thenReturn(Result.succeed(detail("succeeded", step(1), step(2)), "查询成功"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        WebAsyncTask<Void> task = controller.streamResume(UID, request(KEY, 1L), null,
                new MockHttpServletRequest(), resp);

        assertNotNull(task, "生产环境应把 30 秒轮询交给中继线程池");
        assertTrue(resp.getContentAsString().contains("event: agent_done"));
    }

    @Test
    void resume_shouldStopPollingImmediatelyWhenClientDisconnects() throws Exception {
        AiGatewayAsyncDispatcher dispatcher = mock(AiGatewayAsyncDispatcher.class);
        controller.asyncDispatcher = dispatcher;
        final AsyncResponseGate gate = AsyncResponseGate.create();
        when(dispatcher.dispatch(eq("agent/streamResume"), any(), any()))
                .thenAnswer(invocation -> {
                    AiGatewayAsyncDispatcher.AsyncRelay relay = invocation.getArgument(2);
                    relay.run(gate);
                    return new WebAsyncTask<Void>(() -> null);
                });
        when(holder.detail(UID, KEY)).thenAnswer(invocation -> {
            gate.cancel();
            return Result.succeed(detail("running", step(1)), "查询成功");
        });
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.streamResume(UID, request(KEY, 0L), null,
                new MockHttpServletRequest(), resp);

        verify(holder, times(1)).detail(UID, KEY);
    }

    @Test
    void resume_shouldPollWhileRunningThenFinish() throws Exception {
        when(holder.detail(UID, KEY))
                .thenReturn(Result.succeed(detail("running", step(1)), "查询成功"))
                .thenReturn(Result.succeed(detail("succeeded", step(1), step(2)), "查询成功"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.streamResume(UID, request(KEY, 1L), null, resp);

        String body = resp.getContentAsString();
        assertTrue(body.contains("event: agent_done"));
        assertEquals(1, count(body, "event: agent_step"));
        verify(holder, times(2)).detail(UID, KEY);
    }
}
