package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.bean.AgentToolBean;
import com.aiproject.ai.service.holde.AgentConfigHolder;
import com.aiproject.ai.service.holde.AgentRunHolder;
import com.aiproject.ai.service.holde.AgentToolHolder;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 执行循环的四类终止条件与 tool_calls 回灌语义。 */
@ExtendWith(MockitoExtension.class)
class AgentRunnerTest {

    private static final String UID = "u1";
    private static final String KEY = "agent-key-1";

    @Mock
    AgentConfigHolder configHolder;
    @Mock
    AgentToolHolder toolHolder;
    @Mock
    AgentRunHolder runHolder;
    @Mock
    AgentToolExecutor toolExecutor;
    @Mock
    AgentModelClient modelClient;

    @InjectMocks
    AgentRunner runner;

    private AgentBean agent(int maxIterations, long tokenBudget) {
        AgentBean bean = new AgentBean();
        bean.setIid(7L);
        bean.setAgentKey(KEY);
        bean.setModelIid(1L);
        bean.setSystemPrompt("你是知识库助手");
        bean.setMaxIterations(maxIterations);
        bean.setTokenBudget(tokenBudget);
        return bean;
    }

    private ModelReply answer(String text, long prompt, long completion) {
        ModelReply reply = new ModelReply();
        reply.setContent(text);
        reply.setFinishReason("stop");
        reply.setPromptTokens(prompt);
        reply.setCompletionTokens(completion);
        reply.setTotalTokens(prompt + completion);
        return reply;
    }

    private ModelReply toolCall(String name, String args, long prompt) {
        ModelReply.ToolCall call = new ModelReply.ToolCall();
        call.setId("call-1");
        call.setName(name);
        call.setArguments(args);
        ModelReply reply = answer("", prompt, 5L);
        reply.setFinishReason("tool_calls");
        reply.getToolCalls().add(call);
        return reply;
    }

    private AgentToolBean tool() {
        AgentToolBean tool = new AgentToolBean();
        tool.setAgentIid(7L);
        tool.setName("kb_search");
        tool.setToolType("kb_search");
        tool.setJsonSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}");
        return tool;
    }

    private void stubRun(AgentBean bean, List<AgentToolBean> tools) {
        when(configHolder.findByKey(UID, KEY)).thenReturn(bean);
        when(toolHolder.listForRun(UID, 7L)).thenReturn(tools);
        when(runHolder.detail(anyString(), anyString()))
                .thenReturn(Result.succeed(new AgentRunDetail(), "查询成功"));
    }

    @Test
    void run_shouldSucceedWithoutAnyToolCall() throws Exception {
        stubRun(agent(8, 20000L), new ArrayList<AgentToolBean>());
        when(modelClient.call(any(AgentInvocation.class))).thenReturn(answer("最终回答", 10L, 5L));

        Result<AgentRunDetail> result = runner.run(UID, KEY, "介绍一下报销制度");

        assertEquals("查询成功", result.getResp_msg());
        verify(modelClient, times(1)).call(any(AgentInvocation.class));
        verify(runHolder).finish(eq(UID), anyString(), eq("succeeded"), eq(1),
                eq(10L), eq(5L), eq(0L), anyLong(), eq("最终回答"), isNull());
    }

    @Test
    void run_shouldReplayToolResultBeforeNextModelCall() throws Exception {
        stubRun(agent(8, 20000L), Collections.singletonList(tool()));
        when(modelClient.call(any(AgentInvocation.class)))
                .thenReturn(toolCall("kb_search", "{\"query\":\"报销\"}", 12L))
                .thenReturn(answer("按制度第 3 条办理", 20L, 8L));
        when(toolExecutor.execute(eq(UID), any(AgentToolBean.class), anyString()))
                .thenReturn("制度第 3 条：需要发票");

        runner.run(UID, KEY, "怎么报销");

        ArgumentCaptor<AgentInvocation> invocations =
                ArgumentCaptor.forClass(AgentInvocation.class);
        verify(modelClient, times(2)).call(invocations.capture());
        List<Map<String, Object>> second = invocations.getAllValues().get(1).getMessages();
        boolean hasToolMessage = false;
        boolean hasAssistantToolCalls = false;
        for (Map<String, Object> message : second) {
            if ("tool".equals(message.get("role"))) {
                hasToolMessage = true;
                assertTrue(String.valueOf(message.get("content")).contains("制度第 3 条"));
            }
            if ("assistant".equals(message.get("role")) && message.containsKey("tool_calls")) {
                hasAssistantToolCalls = true;
            }
        }
        assertTrue(hasToolMessage, "第二轮请求必须带上 role=tool 的回灌消息");
        assertTrue(hasAssistantToolCalls, "第二轮请求必须保留上一轮 assistant 的 tool_calls");
        verify(runHolder).finish(eq(UID), anyString(), eq("succeeded"), eq(2),
                eq(32L), eq(13L), eq(0L), anyLong(), eq("按制度第 3 条办理"), isNull());
    }

    @Test
    void run_shouldStopAtMaxIterations() throws Exception {
        stubRun(agent(3, 20000L), Collections.singletonList(tool()));
        // 每轮换一个参数，避免被「重复工具调用」检测提前终止
        java.util.concurrent.atomic.AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();
        when(modelClient.call(any(AgentInvocation.class))).thenAnswer(invocationOnMock ->
                toolCall("kb_search", "{\"query\":\"第" + round.incrementAndGet() + "次\"}", 1L));
        when(toolExecutor.execute(eq(UID), any(AgentToolBean.class), anyString()))
                .thenReturn("结果");

        runner.run(UID, KEY, "循环");

        verify(modelClient, times(3)).call(any(AgentInvocation.class));
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(runHolder).finish(eq(UID), anyString(), eq("failed"), eq(3),
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), error.capture());
        assertTrue(error.getValue().contains("迭代"));
    }

    @Test
    void run_shouldStopWhenTokenBudgetExhausted() throws Exception {
        stubRun(agent(8, 20L), Collections.singletonList(tool()));
        when(modelClient.call(any(AgentInvocation.class)))
                .thenReturn(toolCall("kb_search", "{\"query\":\"报销\"}", 50L));

        runner.run(UID, KEY, "烧 Token");

        verify(modelClient, times(1)).call(any(AgentInvocation.class));
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(runHolder).finish(eq(UID), anyString(), eq("failed"), eq(1),
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), error.capture());
        assertTrue(error.getValue().contains("Token 预算"));
    }

    @Test
    void run_shouldRecordFailureWhenModelCallFails() throws Exception {
        stubRun(agent(8, 20000L), new ArrayList<AgentToolBean>());
        when(modelClient.call(any(AgentInvocation.class)))
                .thenThrow(new AgentCallException("上游不可用"));

        runner.run(UID, KEY, "问题");

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(runHolder).finish(eq(UID), anyString(), eq("failed"), eq(1),
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), error.capture());
        assertTrue(error.getValue().contains("上游不可用"));
    }

    @Test
    void run_shouldStopOnRepeatedToolCalls() throws Exception {
        stubRun(agent(8, 20000L), Collections.singletonList(tool()));
        when(modelClient.call(any(AgentInvocation.class)))
                .thenReturn(toolCall("kb_search", "{\"query\":\"报销\"}", 1L));
        when(toolExecutor.execute(eq(UID), any(AgentToolBean.class), anyString()))
                .thenReturn("结果");

        runner.run(UID, KEY, "重复");

        verify(modelClient, times(2)).call(any(AgentInvocation.class));
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(runHolder).finish(eq(UID), anyString(), eq("failed"), eq(2),
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), error.capture());
        assertTrue(error.getValue().contains("重复工具调用"));
    }

    @Test
    void run_shouldReplayToolErrorInsteadOfAborting() throws Exception {
        stubRun(agent(8, 20000L), Collections.singletonList(tool()));
        when(modelClient.call(any(AgentInvocation.class)))
                .thenReturn(toolCall("kb_search", "{\"query\":\"报销\"}", 1L))
                .thenReturn(answer("换个说法再试", 2L, 1L));
        when(toolExecutor.execute(eq(UID), any(AgentToolBean.class), anyString()))
                .thenThrow(new AgentCallException("工具参数不合法"));

        runner.run(UID, KEY, "问题");

        ArgumentCaptor<AgentInvocation> invocations =
                ArgumentCaptor.forClass(AgentInvocation.class);
        verify(modelClient, times(2)).call(invocations.capture());
        List<Map<String, Object>> second = invocations.getAllValues().get(1).getMessages();
        boolean replayed = false;
        for (Map<String, Object> message : second) {
            if ("tool".equals(message.get("role"))) {
                replayed = String.valueOf(message.get("content")).contains("工具执行失败");
            }
        }
        assertTrue(replayed, "工具失败应作为 tool 结果回灌，让模型有机会纠正");
    }

    @Test
    void run_shouldNotifyOnlyAfterStartAndStepPersistence() throws Exception {
        stubRun(agent(8, 20000L), Collections.emptyList());
        stubSteps();
        when(modelClient.call(any())).thenReturn(answer("回答", 10L, 5L));
        AgentRunner.Listener listener = mock(AgentRunner.Listener.class);

        runner.run(UID, KEY, "问题", listener);

        InOrder order = inOrder(runHolder, listener, modelClient);
        ArgumentCaptor<String> runKey = ArgumentCaptor.forClass(String.class);
        order.verify(runHolder).start(eq(UID), eq(7L), eq("问题"), runKey.capture());
        order.verify(listener).onStart(runKey.getValue());
        order.verify(modelClient).call(any());
        order.verify(runHolder).addStep(eq(UID), eq(runKey.getValue()), eq(1), eq(1), eq("llm"),
                isNull(), isNull(), eq("回答"), eq("success"), eq(10L), eq(5L), eq(0L),
                anyLong(), isNull(), isNull());
        ArgumentCaptor<AgentStepBean> step = ArgumentCaptor.forClass(AgentStepBean.class);
        order.verify(listener).onStep(step.capture());
        assertEquals("llm", step.getValue().getStepType());
        assertEquals(runKey.getValue(), step.getValue().getRunKey());
        order.verify(runHolder).addStep(eq(UID), eq(runKey.getValue()), eq(2), eq(1), eq("finish"),
                isNull(), isNull(), eq("回答"), eq("success"), eq(0L), eq(0L), eq(0L),
                eq(0L), isNull(), isNull());
        order.verify(listener).onStep(step.capture());
        assertEquals("finish", step.getValue().getStepType());
        order.verify(runHolder).finish(eq(UID), eq(runKey.getValue()), eq("succeeded"), eq(1),
                eq(10L), eq(5L), eq(0L), anyLong(), eq("回答"), isNull());
        order.verify(runHolder).detail(UID, runKey.getValue());
    }

    @Test
    void run_shouldFinalizeToolLoadingFailureWithoutCallingModel() {
        when(configHolder.findByKey(UID, KEY)).thenReturn(agent(8, 20000L));
        when(toolHolder.listForRun(UID, 7L)).thenThrow(new IllegalStateException("internal"));
        when(runHolder.detail(anyString(), anyString()))
                .thenReturn(Result.succeed(new AgentRunDetail(), "查询成功"));

        runner.run(UID, KEY, "问题");

        verifyNoInteractions(modelClient, toolExecutor);
        verify(runHolder).finish(eq(UID), anyString(), eq("failed"), eq(0),
                eq(0L), eq(0L), eq(0L), anyLong(), eq(""), eq("执行异常，请稍后重试"));
    }

    @Test
    void run_shouldPropagateStepWriteFailureWithoutNotifyingAndKeepUsage() throws Exception {
        when(configHolder.findByKey(UID, KEY)).thenReturn(agent(8, 20000L));
        when(toolHolder.listForRun(UID, 7L)).thenReturn(Collections.singletonList(tool()));
        ModelReply reply = toolCall("kb_search", "{}", 12L);
        reply.setCostCents(7L);
        when(modelClient.call(any())).thenReturn(reply);
        when(runHolder.addStep(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        AgentRunner.Listener listener = mock(AgentRunner.Listener.class);

        assertThrows(DataAccessException.class, () -> runner.run(UID, KEY, "问题", listener));

        verify(listener, never()).onStep(any());
        verifyNoInteractions(toolExecutor);
        verify(runHolder, never()).detail(any(), any());
        verify(runHolder).finish(eq(UID), anyString(), eq("failed"), eq(1),
                eq(12L), eq(5L), eq(7L), anyLong(), eq(""), eq("运行记录读写失败，请稍后重试"));
    }

    @Test
    void run_shouldStopBeforeFirstModelWhenDisconnectedAtStart() {
        when(configHolder.findByKey(UID, KEY)).thenReturn(agent(8, 20000L));
        when(runHolder.detail(anyString(), anyString()))
                .thenReturn(Result.succeed(new AgentRunDetail(), "查询成功"));
        AgentRunner.Listener listener = mock(AgentRunner.Listener.class);
        when(listener.isDisconnected()).thenReturn(true);

        runner.run(UID, KEY, "问题", listener);

        verifyNoInteractions(toolHolder, toolExecutor, modelClient);
        verify(runHolder).finish(eq(UID), anyString(), eq("failed"), eq(0), eq(0L), eq(0L),
                eq(0L), anyLong(), eq(""), eq("客户端已断开连接，执行已停止"));
    }

    @Test
    void run_shouldIsolateListenersForOverlappingInvocations() throws Exception {
        stubRun(agent(8, 20000L), Collections.emptyList());
        stubSteps();
        when(modelClient.call(any())).thenReturn(answer("回答", 1L, 1L));
        AgentRunner.Listener first = mock(AgentRunner.Listener.class);
        AgentRunner.Listener second = mock(AgentRunner.Listener.class);
        doAnswer(invocation -> {
            runner.run(UID, KEY, "第二次", second);
            return null;
        }).when(first).onStart(anyString());

        runner.run(UID, KEY, "第一次", first);
        runner.run(UID, KEY, "同步调用");

        ArgumentCaptor<String> firstKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secondKey = ArgumentCaptor.forClass(String.class);
        verify(first).onStart(firstKey.capture());
        verify(second).onStart(secondKey.capture());
        assertNotEquals(firstKey.getValue(), secondKey.getValue());
        ArgumentCaptor<AgentStepBean> firstSteps = ArgumentCaptor.forClass(AgentStepBean.class);
        ArgumentCaptor<AgentStepBean> secondSteps = ArgumentCaptor.forClass(AgentStepBean.class);
        verify(first, times(2)).onStep(firstSteps.capture());
        verify(second, times(2)).onStep(secondSteps.capture());
        firstSteps.getAllValues().forEach(step -> assertEquals(firstKey.getValue(), step.getRunKey()));
        secondSteps.getAllValues().forEach(step -> assertEquals(secondKey.getValue(), step.getRunKey()));
    }

    @Test
    void run_shouldRejectAnonymousBlankKeyAndOversizedQuestionWithoutModel() {
        AgentRunner.Listener listener = mock(AgentRunner.Listener.class);
        assertNull(runner.run(null, KEY, "问题", listener).getDatas());
        assertNull(runner.run(UID, " ", "问题", listener).getDatas());
        when(configHolder.findByKey(UID, KEY)).thenReturn(agent(8, 20000L));
        Result<AgentRunDetail> result = runner.run(UID, KEY, String.join("", Collections.nCopies(20001, "问")), listener);
        assertEquals("问题不能超过 20000 个字符", result.getResp_msg());
        verifyNoInteractions(listener, runHolder, toolHolder, toolExecutor, modelClient);
    }

    private void stubSteps() {
        when(runHolder.addStep(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong(), any(), any())).thenAnswer(invocation -> {
            AgentStepBean step = new AgentStepBean();
            step.setRunKey(invocation.getArgument(1));
            step.setStepNo(invocation.getArgument(2));
            step.setStepType(invocation.getArgument(4));
            return step;
        });
    }

    @Test
    void run_shouldRejectUnknownAgentWithoutCreatingRun() {
        when(configHolder.findByKey(UID, KEY)).thenReturn(null);
        assertNull(runner.run(UID, KEY, "问题").getDatas());
        verify(runHolder, never()).start(anyString(), any(), anyString(), anyString());
        verifyNoInteractions(modelClient, toolExecutor);
    }

    @Test
    void run_shouldRejectBlankQuestion() {
        when(configHolder.findByKey(UID, KEY)).thenReturn(agent(8, 20000L));
        assertNull(runner.run(UID, KEY, "  ").getDatas());
        verify(runHolder, never()).start(anyString(), any(), anyString(), anyString());
        verifyNoInteractions(modelClient, toolExecutor);
    }

    @Test
    void run_shouldRejectAgentWithoutModel() {
        AgentBean noModel = agent(8, 20000L);
        noModel.setModelIid(null);
        when(configHolder.findByKey(UID, KEY)).thenReturn(noModel);
        assertNull(runner.run(UID, KEY, "问题").getDatas());
        verify(runHolder, never()).start(anyString(), any(), anyString(), anyString());
        verifyNoInteractions(modelClient, toolExecutor);
    }
}
