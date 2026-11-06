package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.agent.AgentCallException;
import com.aiproject.ai.service.agent.AgentInvocation;
import com.aiproject.ai.service.agent.AgentModelClient;
import com.aiproject.ai.service.agent.AgentRunner;
import com.aiproject.ai.service.agent.AgentToolExecutor;
import com.aiproject.ai.service.agent.ModelReply;
import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.agent.dto.AgentRunRequest;
import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.AgentRunBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.bean.AgentToolBean;
import com.aiproject.ai.service.holde.AgentConfigHolder;
import com.aiproject.ai.service.holde.AgentRunHolder;
import com.aiproject.ai.service.holde.AgentToolHolder;
import com.aiproject.ai.service.mapper.AgentRunBeanMapper;
import com.aiproject.ai.service.mapper.AgentStepBeanMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.croe.web.multirequestbody.MultiRequestBodyArgumentResolver;
import com.heneng.hnsp.common.inter.LoginUserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentBeanControllerTest {
    private static final String UID = "u1";
    private static final String KEY = "agent-1";
    private final ObjectMapper json = new ObjectMapper();
    private AgentBeanController controller;
    private AgentRunner runner;
    private AgentRunHolder runHolder;
    private AgentConfigHolder configHolder;
    private AgentToolHolder toolHolder;
    private AgentModelClient modelClient;
    private AgentToolExecutor toolExecutor;
    private AgentRunBeanMapper<AgentRunBean> runMapper;
    private AgentStepBeanMapper<AgentStepBean> stepMapper;
    private AgentBean agent;
    private AgentRunBean storedRun;
    private List<AgentStepBean> storedSteps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        configHolder = mock(AgentConfigHolder.class);
        toolHolder = mock(AgentToolHolder.class);
        modelClient = mock(AgentModelClient.class);
        toolExecutor = mock(AgentToolExecutor.class);
        runMapper = mock(AgentRunBeanMapper.class);
        stepMapper = mock(AgentStepBeanMapper.class);
        runHolder = new AgentRunHolder();
        ReflectionTestUtils.setField(runHolder, "runMapper", runMapper);
        ReflectionTestUtils.setField(runHolder, "stepMapper", stepMapper);
        runner = new AgentRunner();
        ReflectionTestUtils.setField(runner, "configHolder", configHolder);
        ReflectionTestUtils.setField(runner, "toolHolder", toolHolder);
        ReflectionTestUtils.setField(runner, "runHolder", runHolder);
        ReflectionTestUtils.setField(runner, "modelClient", modelClient);
        ReflectionTestUtils.setField(runner, "toolExecutor", toolExecutor);
        controller = new AgentBeanController();
        controller.agentRunner = runner;
        agent = new AgentBean();
        agent.setIid(7L);
        agent.setAgentKey(KEY);
        agent.setModelIid(1L);
        agent.setLastuser(UID);
        when(configHolder.findByKey(UID, KEY)).thenReturn(agent);
        when(toolHolder.listForRun(UID, 7L)).thenReturn(Collections.emptyList());
        when(modelClient.call(any())).thenReturn(answer());
        storedSteps = new ArrayList<>();
        when(runMapper.install(any(AgentRunBean.class))).thenAnswer(invocation -> {
            storedRun = invocation.getArgument(0);
            return 1;
        });
        when(stepMapper.install(any(AgentStepBean.class))).thenAnswer(invocation -> {
            AgentStepBean step = invocation.getArgument(0);
            step.setIid((long) storedSteps.size() + 1);
            storedSteps.add(step);
            return 1;
        });
        when(runMapper.update(any(AgentRunBean.class), any(AgentRunBean.class))).thenAnswer(invocation -> {
            AgentRunBean patch = invocation.getArgument(0);
            AgentRunBean where = invocation.getArgument(1);
            assertEquals(UID, where.getLastuser());
            assertEquals(storedRun.getRunKey(), where.getRunKey());
            patch.setRunKey(where.getRunKey());
            patch.setLastuser(where.getLastuser());
            patch.setAgentIid(storedRun.getAgentIid());
            patch.setStartedAt(storedRun.getStartedAt());
            patch.setQuestion(storedRun.getQuestion());
            storedRun = patch;
            return 1;
        });
        when(runMapper.select(any(), any(AgentRunBean.class)))
                .thenAnswer(invocation -> Collections.singletonList(storedRun));
        when(stepMapper.select(any(), any(AgentStepBean.class)))
                .thenAnswer(invocation -> new ArrayList<>(storedSteps));
    }

    @Test
    void stream_shouldFlushPersistedStartStepsAndDirectDetailInOrder() throws Exception {
        TrackingResponse response = new TrackingResponse(0);
        response.onFrame = frame -> {
            JsonNode data = data(frame);
            switch (event(frame)) {
                case "agent_start":
                    assertEquals("running", storedRun.getStatus());
                    assertEquals(storedRun.getRunKey(), data.path("runKey").asText());
                    assertEquals(1, data.size());
                    break;
                case "agent_step":
                    assertEquals(json.valueToTree(storedSteps.get(storedSteps.size() - 1)).toString(), data.toString());
                    break;
                case "agent_done":
                    assertEquals("succeeded", storedRun.getStatus());
                    assertNotNull(storedRun.getFinishedAt());
                    assertEquals(json.valueToTree(storedRun).toString(), data.path("run").toString());
                    assertEquals(json.valueToTree(storedSteps).toString(), data.path("steps").toString());
                    break;
                default:
                    fail(frame);
            }
        };

        controller.streamRun(UID, request(), response);

        assertEquals("UTF-8", response.getCharacterEncoding());
        assertEquals("text/event-stream;charset=UTF-8", response.getContentType());
        assertEquals("no-cache", response.getHeader("Cache-Control"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertEquals(Arrays.asList("agent_start", "agent_step", "agent_step", "agent_done"), events(response));
        assertEquals(4, response.flushCount);
        assertTrue(response.getContentAsString().contains("最终回答\\n第二行"));
        assertFalse(response.getContentAsString().contains("resp_code"));
        assertEquals(Long.valueOf(15), storedRun.getTotalTokens());
        assertEquals(Long.valueOf(3), storedRun.getCostCents());
    }

    @Test
    void stream_shouldSendDoneForFailedExecution() throws Exception {
        when(modelClient.call(any())).thenThrow(new AgentCallException("上游不可用"));
        TrackingResponse response = new TrackingResponse(0);

        controller.streamRun(UID, request(), response);

        assertEquals(Arrays.asList("agent_start", "agent_step", "agent_done"), events(response));
        JsonNode detail = data(response.frames.get(2));
        assertEquals("failed", detail.path("run").path("status").asText());
        assertEquals("error", detail.path("steps").get(0).path("stepType").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "body", "key", "owner", "question", "long", "model"})
    void stream_shouldRejectInvalidOrUnownedRequestsWithOnlyError(String invalid) {
        AgentRunRequest request = request();
        String uid = UID;
        switch (invalid) {
            case "anonymous": uid = null; break;
            case "body": request = null; break;
            case "key": request.setAgentKey(" "); break;
            case "owner": request.setAgentKey("other-user-agent"); break;
            case "question": request.setQuestion(" "); break;
            case "long": request.setQuestion(String.join("", Collections.nCopies(20001, "问"))); break;
            case "model": agent.setModelIid(null); break;
            default: fail(invalid);
        }
        TrackingResponse response = new TrackingResponse(0);

        controller.streamRun(uid, request, response);

        assertEquals(Collections.singletonList("agent_error"), events(response));
        assertFalse(data(response.frames.get(0)).path("message").asText().isEmpty());
        verifyNoInteractions(modelClient, toolExecutor, runMapper, stepMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void stream_shouldNotStartWhenRunInsertDidNotAffectExactlyOneRow(int rows) {
        when(runMapper.install(any(AgentRunBean.class))).thenReturn(rows);
        TrackingResponse response = new TrackingResponse(0);

        controller.streamRun(UID, request(), response);

        assertEquals(Collections.singletonList("agent_error"), events(response));
        verifyNoInteractions(modelClient, toolExecutor, stepMapper);
        verify(runMapper, never()).update(any(AgentRunBean.class), any(AgentRunBean.class));
    }

    @Test
    void stream_shouldExplainUniqueConflictWithoutLeakingDatabaseMessage() {
        when(runMapper.install(any(AgentRunBean.class))).thenThrow(new DuplicateKeyException("private-db-message"));
        TrackingResponse response = new TrackingResponse(0);

        controller.streamRun(UID, request(), response);

        assertEquals(Collections.singletonList("agent_error"), events(response));
        assertEquals("运行记录发生并发冲突，请重试", data(response.frames.get(0)).path("message").asText());
        verifyNoInteractions(modelClient, toolExecutor);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void stream_shouldNotEmitUnpersistedStepOrDoneWhenStepWriteFails(int failedStep) {
        AtomicInteger writes = new AtomicInteger();
        when(stepMapper.install(any(AgentStepBean.class))).thenAnswer(invocation -> {
            if (writes.incrementAndGet() == failedStep) {
                return 0;
            }
            storedSteps.add(invocation.getArgument(0));
            return 1;
        });
        TrackingResponse response = new TrackingResponse(0);

        controller.streamRun(UID, request(), response);

        assertEquals(failedStep - 1, storedSteps.size());
        assertEquals(failedStep - 1, Collections.frequency(events(response), "agent_step"));
        assertFalse(events(response).contains("agent_done"));
        assertEquals("agent_error", event(response.frames.get(response.frames.size() - 1)));
        assertEquals("failed", storedRun.getStatus());
        assertEquals(Long.valueOf(15), storedRun.getTotalTokens());
        assertEquals(Long.valueOf(3), storedRun.getCostCents());
        verify(runMapper, never()).select(any(), any(AgentRunBean.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void stream_shouldNotEmitDoneWhenFinishUpdateFails(int rows) {
        when(runMapper.update(any(AgentRunBean.class), any(AgentRunBean.class))).thenReturn(rows);
        TrackingResponse response = new TrackingResponse(0);

        controller.streamRun(UID, request(), response);

        assertEquals(Arrays.asList("agent_start", "agent_step", "agent_step", "agent_error"), events(response));
        verify(runMapper, never()).select(any(), any(AgentRunBean.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed-result", "missing", "exception", "unfinished"})
    void stream_shouldNotEmitDoneForUnavailableOrInvalidDetail(String failure) {
        AgentRunHolder holder = spy(runHolder);
        ReflectionTestUtils.setField(runner, "runHolder", holder);
        AgentRunDetail invalidDetail = new AgentRunDetail();
        invalidDetail.setRun(new AgentRunBean());
        switch (failure) {
            case "failed-result":
                doReturn(Result.failed(invalidDetail, "private-db-message")).when(holder).detail(any(), any());
                break;
            case "missing":
                when(runMapper.select(any(), any(AgentRunBean.class))).thenReturn(Collections.emptyList());
                break;
            case "exception":
                doThrow(new DataAccessResourceFailureException("private-db-message")).when(holder).detail(any(), any());
                break;
            case "unfinished":
                doReturn(Result.succeed(invalidDetail, "查询成功")).when(holder).detail(any(), any());
                break;
            default: fail(failure);
        }
        TrackingResponse response = new TrackingResponse(0);

        controller.streamRun(UID, request(), response);

        assertEquals(Arrays.asList("agent_start", "agent_step", "agent_step", "agent_error"), events(response));
        assertFalse(response.frames.toString().contains("private-db-message"));
    }

    @Test
    void stream_shouldFinalizeToolLoadingExceptionWithoutLeakingIt() {
        when(toolHolder.listForRun(UID, 7L)).thenThrow(new IllegalStateException("private-tool-message"));
        TrackingResponse response = new TrackingResponse(0);

        controller.streamRun(UID, request(), response);

        assertEquals(Arrays.asList("agent_start", "agent_done"), events(response));
        assertEquals("failed", storedRun.getStatus());
        assertFalse(response.frames.toString().contains("private-tool-message"));
        verifyNoInteractions(modelClient, toolExecutor);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void stream_shouldStopSubsequentModelAndToolCallsAfterFlushDisconnectAndKeepUsage(int failOnFlush) throws Exception {
        AgentToolBean tool = new AgentToolBean();
        tool.setName("clock");
        tool.setToolType("builtin");
        tool.setJsonSchema("{}");
        when(toolHolder.listForRun(UID, 7L)).thenReturn(Collections.singletonList(tool));
        ModelReply reply = answer();
        for (int i = 0; i < 2; i++) {
            ModelReply.ToolCall call = new ModelReply.ToolCall();
            call.setId("call-" + i);
            call.setName("clock");
            call.setArguments("{}");
            reply.getToolCalls().add(call);
        }
        when(modelClient.call(any())).thenReturn(reply);
        when(toolExecutor.execute(any(), any(), any())).thenReturn("时间");
        TrackingResponse response = new TrackingResponse(failOnFlush);

        controller.streamRun(UID, request(), response);

        verify(modelClient, times(failOnFlush == 1 ? 0 : 1)).call(any());
        verify(toolExecutor, times(Math.max(0, failOnFlush - 2))).execute(any(), any(), any());
        assertEquals("failed", storedRun.getStatus());
        assertEquals("客户端已断开连接，执行已停止", storedRun.getErrorMessage());
        assertEquals(Long.valueOf(failOnFlush == 1 ? 0 : 15), storedRun.getTotalTokens());
        assertEquals(Long.valueOf(failOnFlush == 1 ? 0 : 3), storedRun.getCostCents());
        assertEquals(failOnFlush - 1, storedSteps.size());
        assertEquals(failOnFlush, response.flushCount);
        assertFalse(events(response).contains("agent_done"));
        assertFalse(events(response).contains("agent_error"));
    }

    @Test
    void stream_shouldFinalizeWhenOutputStreamWriteCannotStart() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("disconnected"));

        controller.streamRun(UID, request(), response);

        verifyNoInteractions(modelClient, toolExecutor);
        verify(response, times(1)).getOutputStream();
        assertEquals("failed", storedRun.getStatus());
        assertNotNull(storedRun.getFinishedAt());
    }

    @Test
    void stream_shouldAcceptExistingHnspDataEnvelopeThroughMvc() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
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
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                return UID;
            }
        });
        resolvers.add(new MultiRequestBodyArgumentResolver());
        resolvers.addAll(adapter.getArgumentResolvers());
        adapter.setArgumentResolvers(resolvers);

        MockHttpServletResponse response = mvc.perform(post("/api/agentBean/streamRun")
                .contentType("application/json;charset=UTF-8")
                .content("{\"data\":{\"agentKey\":\"agent-1\",\"question\":\"中文问题\"}}"))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertTrue(response.getContentAsString().contains("event: agent_done"));
        assertEquals("中文问题", storedRun.getQuestion());
        ArgumentCaptor<AgentInvocation> invocation = ArgumentCaptor.forClass(AgentInvocation.class);
        verify(modelClient).call(invocation.capture());
        assertEquals(UID, invocation.getValue().getUid());
    }

    @Test
    void run_shouldKeepSynchronousDetailResponse() throws Exception {
        Result<AgentRunDetail> result = controller.run(UID, request());

        assertEquals("succeeded", result.getDatas().getRun().getStatus());
        assertEquals(2, result.getDatas().getSteps().size());
        verify(modelClient).call(any());
    }

    private AgentRunRequest request() {
        AgentRunRequest request = new AgentRunRequest();
        request.setAgentKey(KEY);
        request.setQuestion("中文问题");
        return request;
    }

    private ModelReply answer() {
        ModelReply reply = new ModelReply();
        reply.setContent("最终回答\n第二行");
        reply.setPromptTokens(10L);
        reply.setCompletionTokens(5L);
        reply.setTotalTokens(15L);
        reply.setCostCents(3L);
        return reply;
    }

    private List<String> events(TrackingResponse response) {
        List<String> events = new ArrayList<>();
        response.frames.forEach(frame -> events.add(event(frame)));
        return events;
    }

    private String event(String frame) {
        assertTrue(frame.endsWith("\n\n"));
        return frame.substring("event: ".length(), frame.indexOf('\n'));
    }

    private JsonNode data(String frame) {
        try {
            return json.readTree(frame.substring(frame.indexOf("\ndata: ") + "\ndata: ".length()).trim());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static class TrackingResponse extends HttpServletResponseWrapper {
        private final int failOnFlush;
        private final List<String> frames = new ArrayList<>();
        private int flushCount;
        private int offset;
        private Consumer<String> onFrame;

        private TrackingResponse(int failOnFlush) {
            super(new MockHttpServletResponse());
            this.failOnFlush = failOnFlush;
        }

        private String getContentAsString() throws IOException {
            return ((MockHttpServletResponse) getResponse()).getContentAsString();
        }

        @Override
        public void flushBuffer() throws IOException {
            flushCount++;
            if (flushCount == failOnFlush) {
                throw new IOException("disconnected");
            }
            String content = getContentAsString();
            String frame = content.substring(offset);
            offset = content.length();
            frames.add(frame);
            if (onFrame != null) {
                onFrame.accept(frame);
            }
            super.flushBuffer();
        }
    }
}
