package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.agent.AgentRunner;
import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.agent.dto.AgentRunRequest;
import com.aiproject.ai.service.bean.AgentRunBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.gateway.AiGatewayAsyncDispatcher;
import com.aiproject.ai.service.gateway.AiGatewayProperties;
import com.aiproject.ai.service.gateway.AsyncResponseGate;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 流式运行的异步路径。
 *
 * <p>重点不是「有没有返回 WebAsyncTask」，而是容器回收响应后
 * 中继线程不能再写 response、AgentRunner 能及时停机（不再烧 Token）。</p>
 */
class AgentBeanAsyncTest {

    private static final String UID = "u1";

    private AgentRunner runner;
    private AgentBeanController controller;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        runner = mock(AgentRunner.class);
        controller = new AgentBeanController();
        controller.agentRunner = runner;
        response = new MockHttpServletResponse();
    }

    private AgentRunRequest request() {
        AgentRunRequest req = new AgentRunRequest();
        req.setAgentKey("agent-1");
        req.setQuestion("问题");
        return req;
    }

    private AgentRunDetail detail(String status) {
        AgentRunBean run = new AgentRunBean();
        run.setRunKey("run-async");
        run.setStatus(status);
        AgentRunDetail d = new AgentRunDetail();
        d.setRun(run);
        d.setSteps(Collections.<AgentStepBean>emptyList());
        return d;
    }

    private void stubSuccessfulRun() {
        when(runner.run(eq(UID), any(), any(), any(AgentRunner.Listener.class)))
                .thenAnswer(invocation -> {
                    AgentRunner.Listener listener = invocation.getArgument(3);
                    listener.onStart("run-async");
                    return Result.succeed(detail("succeeded"), "查询成功");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void runAsync_shouldReturnDetailThroughBoundedExecutor() throws Exception {
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        controller.agentExecutor = executor;
        when(runner.run(eq(UID), any(), any()))
                .thenReturn(Result.succeed(detail("succeeded"), "查询成功"));

        WebAsyncTask<Result<AgentRunDetail>> task = controller.runAsync(UID, request());

        assertNotNull(task);
        // getCallable() 返回 Callable<?>，用 Object 中转后再还原泛型
        Object raw = task.getCallable().call();
        Result<AgentRunDetail> result = (Result<AgentRunDetail>) raw;
        assertEquals(0, result.getResp_code());
        assertEquals("run-async", result.getDatas().getRun().getRunKey());
    }

    @Test
    @SuppressWarnings("unchecked")
    void runAsync_shouldStillWorkWhenExecutorIsNotInjected() throws Exception {
        when(runner.run(eq(UID), any(), any()))
                .thenReturn(Result.succeed(detail("failed"), "查询成功"));

        WebAsyncTask<Result<AgentRunDetail>> task = controller.runAsync(UID, request());

        assertNotNull(task, "测试环境没注入线程池时由 MVC 默认执行器兜底");
        Object raw = task.getCallable().call();
        Result<AgentRunDetail> result = (Result<AgentRunDetail>) raw;
        assertEquals("failed", result.getDatas().getRun().getStatus());
    }

    @Test
    void realDispatcher_shouldCompleteThroughSpringAsyncDispatch() throws Exception {
        // 用真实分发器（而非 mock）走一遍 Spring MVC 的 WebAsyncTask 生命周期。
        // executor 用「提交即执行」的实现，让 MockMvc 的 asyncDispatch 能确定性拿到结果。
        AiGatewayAsyncDispatcher real = new AiGatewayAsyncDispatcher();
        ReflectionTestUtils.setField(real, "properties", new AiGatewayProperties());
        Executor inline = Runnable::run;
        ReflectionTestUtils.setField(real, "executor", new ConcurrentTaskExecutor(inline));
        controller.asyncDispatcher = real;
        stubSuccessfulRun();

        org.springframework.test.web.servlet.MockMvc mvc = standaloneSetup(controller).build();
        RequestMappingHandlerAdapter adapter = mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerAdapter.class);
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(LoginUserId.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return UID;
            }
        });
        resolvers.addAll(adapter.getArgumentResolvers());
        adapter.setArgumentResolvers(resolvers);

        MvcResult started = mvc.perform(post("/api/agentBean/streamRun")).andReturn();
        assertTrue(started.getRequest().isAsyncStarted(), "生产环境应进入 Servlet 异步");
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());

        String body = started.getResponse().getContentAsString();
        assertTrue(body.contains("event: agent_start"));
        assertTrue(body.contains("event: agent_done"));
    }

    @Test
    void withoutDispatcher_shouldFallBackToSynchronousExecution() throws Exception {
        stubSuccessfulRun();

        WebAsyncTask<Void> task = controller.streamRun(UID, request(),
                new MockHttpServletRequest(), response);

        assertNull(task, "分发器不可用时返回 null，表示已同步处理完");
        assertTrue(response.getContentAsString().contains("event: agent_start"));
        assertTrue(response.getContentAsString().contains("event: agent_done"));
    }

    @Test
    void withDispatcher_shouldDelegateRelayAndReturnAsyncTask() throws Exception {
        AiGatewayAsyncDispatcher dispatcher = mock(AiGatewayAsyncDispatcher.class);
        controller.asyncDispatcher = dispatcher;
        when(dispatcher.dispatch(eq("agent/streamRun"), any(MockHttpServletRequest.class),
                eq(AgentBeanController.AGENT_ASYNC_TIMEOUT_MS),
                any(AiGatewayAsyncDispatcher.AsyncRelay.class)))
                .thenAnswer(invocation -> {
                    AiGatewayAsyncDispatcher.AsyncRelay relay = invocation.getArgument(3);
                    relay.run(AsyncResponseGate.create());
                    return new WebAsyncTask<Void>(() -> null);
                });
        stubSuccessfulRun();

        WebAsyncTask<Void> task = controller.streamRun(UID, request(),
                new MockHttpServletRequest(), response);

        assertNotNull(task, "生产环境应交给中继线程池执行");
        verify(dispatcher).dispatch(eq("agent/streamRun"), any(),
                eq(AgentBeanController.AGENT_ASYNC_TIMEOUT_MS), any());
        assertTrue(response.getContentAsString().contains("event: agent_start"));
    }

    @Test
    void asyncRelay_shouldGiveRunnerACancelAwareListener() throws Exception {
        AiGatewayAsyncDispatcher dispatcher = mock(AiGatewayAsyncDispatcher.class);
        controller.asyncDispatcher = dispatcher;
        final AsyncResponseGate gate = AsyncResponseGate.create();
        when(dispatcher.dispatch(eq("agent/streamRun"), any(MockHttpServletRequest.class),
                eq(AgentBeanController.AGENT_ASYNC_TIMEOUT_MS),
                any(AiGatewayAsyncDispatcher.AsyncRelay.class)))
                .thenAnswer(invocation -> {
                    AiGatewayAsyncDispatcher.AsyncRelay relay = invocation.getArgument(3);
                    relay.run(gate);
                    return new WebAsyncTask<Void>(() -> null);
                });
        stubSuccessfulRun();

        controller.streamRun(UID, request(), new MockHttpServletRequest(), response);

        ArgumentCaptor<AgentRunner.Listener> listener = ArgumentCaptor.forClass(AgentRunner.Listener.class);
        verify(runner).run(eq(UID), any(), any(), listener.capture());
        assertFalse(listener.getValue().isDisconnected());
        gate.cancel();
        assertTrue(listener.getValue().isDisconnected(),
                "客户端断开后 AgentRunner 的 checkConnected 应立即终止循环，不再调用模型");
    }

    @Test
    void cancelledGate_shouldRejectAnyFurtherFrameWrites() throws Exception {
        AsyncResponseGate gate = AsyncResponseGate.create();
        AgentBeanController.SseListener listener =
                new AgentBeanController.SseListener(response, gate);

        listener.onStart("run-async");
        assertTrue(response.getContentAsString().contains("event: agent_start"));

        gate.cancel();
        assertTrue(listener.isDisconnected());
        AgentStepBean step = new AgentStepBean();
        step.setRunKey("run-async");
        step.setStepNo(1);
        listener.onStep(step);
        listener.error("容器已回收响应");

        String body = response.getContentAsString();
        assertFalse(body.contains("event: agent_step"), "取消后不能写已回收的 response");
        assertFalse(body.contains("event: agent_error"));
        assertTrue(body.contains("id: 0"), "取消前已写出的帧保持不变");
    }

    @Test
    void synchronousFallbackGate_shouldNeverCancel() throws Exception {
        AgentBeanController.SseListener listener =
                new AgentBeanController.SseListener(response, AsyncResponseGate.NONE);
        listener.onStart("run-async");
        assertFalse(listener.isDisconnected());
        AgentStepBean step = new AgentStepBean();
        step.setRunKey("run-async");
        step.setStepNo(1);
        listener.onStep(step);
        assertTrue(response.getContentAsString().contains("event: agent_step"));
    }
}
