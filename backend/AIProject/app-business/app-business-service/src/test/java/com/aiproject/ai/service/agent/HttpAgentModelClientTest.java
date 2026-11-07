package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.gateway.AiGatewayGuard;
import com.aiproject.ai.service.gateway.AiGatewayProperties;
import com.aiproject.ai.service.gateway.GatewayCircuitBreaker;
import com.aiproject.ai.service.gateway.GatewayConcurrencyGuard;
import com.aiproject.ai.service.gateway.ModelGatewayResolver;
import com.aiproject.ai.service.gateway.ModelPriceResolver;
import com.aiproject.ai.service.holde.AiCallLogHolder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型客户端的真实 HTTP 解析与治理接线。
 *
 * <p>用本机临时 HttpServer 模拟上游，避免依赖任何真实模型服务；
 * 重点断言的是「预扣是否按真实 usage 结算」「审计是否带上 runKey/stepNo」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HttpAgentModelClientTest {

    private static final String UID = "u1";

    @Mock
    ModelGatewayResolver modelGatewayResolver;
    @Mock
    AiGatewayGuard guard;
    @Mock
    ModelPriceResolver priceResolver;
    @Mock
    AiCallLogHolder callLogHolder;

    @InjectMocks
    HttpAgentModelClient client;

    // 并发许可与 Admissions 是 final 类，用真实组件而不是 mock：
    // 顺带验证「许可确实归还」「失败确实让熔断打开」这类行为
    private AiGatewayProperties properties;
    private GatewayConcurrencyGuard concurrencyGuard;
    private GatewayCircuitBreaker circuitBreaker;

    private HttpServer server;
    private volatile String responseBody = "{}";
    private volatile int responseStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        when(guard.check(anyString(), anyString())).thenReturn(AiGatewayGuard.Decision.allow());
        when(guard.reserve(anyString(), anyString())).thenReturn(AiGatewayGuard.Reservation.none());
        when(priceResolver.resolve(anyString())).thenReturn(null);

        properties = new AiGatewayProperties();
        properties.setConnectTimeoutMs(10000);
        properties.setReadTimeoutMs(120000);
        properties.setMaxConcurrentPerModule(4);
        concurrencyGuard = new GatewayConcurrencyGuard(properties);
        circuitBreaker = new GatewayCircuitBreaker();
        ReflectionTestUtils.setField(circuitBreaker, "properties", properties);
        client.properties = properties;
        client.concurrencyGuard = concurrencyGuard;
        client.circuitBreaker = circuitBreaker;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AgentInvocation invocation() {
        AgentBean agent = new AgentBean();
        agent.setModelIid(1L);
        AgentInvocation inv = new AgentInvocation();
        inv.setUid(UID);
        inv.setRunKey("run-1");
        inv.setStepNo(1);
        inv.setAgent(agent);
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("role", "user");
        user.put("content", "hi");
        inv.setMessages(Collections.singletonList(user));
        return inv;
    }

    private void stubModel() {
        when(modelGatewayResolver.resolve(eq(1L))).thenReturn(
                new ModelGatewayResolver.ResolvedModel(baseUrl(), "test-key", "model-x", "测试模型"));
    }

    @Test
    void call_shouldParseToolCallsAndSettleByRealUsage() throws Exception {
        stubModel();
        // 限 1 个并发许可：如果调用结束后没归还，下面的探测会拿不到
        properties.setMaxConcurrentPerModule(1);
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":["
                + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"kb_search\","
                + "\"arguments\":\"{\\\"query\\\":\\\"报销\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";

        ModelReply reply = client.call(invocation());

        assertEquals(1, reply.getToolCalls().size());
        assertEquals("kb_search", reply.getToolCalls().get(0).getName());
        assertEquals(15L, reply.getTotalTokens());
        assertEquals(0L, reply.getCostCents());
        verify(guard).settle(any(AiGatewayGuard.Reservation.class), eq(15L));
        // 未预扣时 settlement 是空操作，必须补记真实用量，否则 Agent 消耗不计入额度
        verify(guard).addTokens(UID, "models", 15L);
        assertEquals("CLOSED", circuitBreaker.stateOf("models|" + baseUrl()),
                "200 是上游可达的明确证据，不应影响熔断");
        GatewayConcurrencyGuard.Lease probe = concurrencyGuard.tryAcquire("models");
        assertNotNull(probe, "调用结束后并发许可必须归还");
        probe.release();

        ArgumentCaptor<AiCallLogBean> log = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(callLogHolder).record(log.capture());
        assertEquals("success", log.getValue().getStatus());
        assertEquals("run-1", log.getValue().getRunKey());
        assertEquals(Integer.valueOf(1), log.getValue().getStepNo());
        assertEquals(Long.valueOf(15L), log.getValue().getTotalTokens());
    }

    @Test
    void call_shouldReleaseReservationAndLogFailureOnUpstreamError() {
        stubModel();
        // 阈值调到 1，用状态机确认 5xx 真的被记为失败
        properties.setCircuitFailureThreshold(1);
        properties.setCircuitOpenMs(30000);
        responseStatus = 500;

        AgentCallException e = assertThrows(AgentCallException.class,
                () -> client.call(invocation()));

        assertTrue(e.getMessage().contains("500"));
        verify(guard).settle(any(AiGatewayGuard.Reservation.class), eq(0L));
        assertEquals("OPEN", circuitBreaker.stateOf("models|" + baseUrl()),
                "5xx 必须计入熔断失败");
        ArgumentCaptor<AiCallLogBean> log = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(callLogHolder).record(log.capture());
        assertEquals("failed", log.getValue().getStatus());
        assertEquals(Integer.valueOf(500), log.getValue().getHttpStatus(),
                "审计里要留上游真实状态码，不能固定写 0");
        assertTrue(!e.getMessage().contains("internal"),
                "上游错误体不得拼进返回给浏览器的 message");
    }

    @Test
    void call_shouldRejectOversizedResponseAndReleaseReservation() {
        stubModel();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2 * 1024 * 1024 + 16; i++) {
            sb.append('x');
        }
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"" + sb + "\"}}]}";

        assertThrows(AgentCallException.class, () -> client.call(invocation()));

        verify(guard).settle(any(AiGatewayGuard.Reservation.class), eq(0L));
        ArgumentCaptor<AiCallLogBean> log = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(callLogHolder).record(log.capture());
        assertEquals("failed", log.getValue().getStatus());
    }

    @Test
    void call_shouldRejectWhenGuardBlocksBeforeAnyHttpCall() {
        stubModel();
        when(guard.check(anyString(), anyString()))
                .thenReturn(AiGatewayGuard.Decision.block("quota_exceeded", "今日额度已用完"));

        AgentCallException e = assertThrows(AgentCallException.class,
                () -> client.call(invocation()));

        assertTrue(e.getMessage().contains("额度已用完"));
        verify(guard, never()).settle(any(AiGatewayGuard.Reservation.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void call_shouldRejectWhenConcurrencyIsExhaustedAndReleaseReservation() {
        stubModel();
        properties.setMaxConcurrentPerModule(1);
        GatewayConcurrencyGuard.Lease hold = concurrencyGuard.tryAcquire("models");
        assertNotNull(hold, "测试前提：占满 models 模块的唯一许可");

        AgentCallException e = assertThrows(AgentCallException.class,
                () -> client.call(invocation()));

        assertTrue(e.getMessage().contains("请求过多"));
        verify(guard).settle(any(AiGatewayGuard.Reservation.class), eq(0L));
        assertEquals("CLOSED", circuitBreaker.stateOf("models|" + baseUrl()),
                "并发拒绝发生在熔断之前，不应产生上游健康样本");
        verify(callLogHolder, never()).record(any(AiCallLogBean.class));
        hold.release();
    }

    @Test
    void call_shouldRejectWhenCircuitIsOpenAndReturnLease() {
        stubModel();
        properties.setMaxConcurrentPerModule(1);
        properties.setCircuitFailureThreshold(1);
        properties.setCircuitOpenMs(60000);
        circuitBreaker.recordFailure("models|" + baseUrl());
        assertEquals("OPEN", circuitBreaker.stateOf("models|" + baseUrl()));

        AgentCallException e = assertThrows(AgentCallException.class,
                () -> client.call(invocation()));

        assertTrue(e.getMessage().contains("60 秒后重试"));
        verify(guard).settle(any(AiGatewayGuard.Reservation.class), eq(0L));
        verify(callLogHolder, never()).record(any(AiCallLogBean.class));
        GatewayConcurrencyGuard.Lease probe = concurrencyGuard.tryAcquire("models");
        assertNotNull(probe, "熔断拒绝时必须把并发许可还回去");
        probe.release();
    }

    @Test
    void call_shouldTreatClientErrorAsNeutralForCircuit() {
        stubModel();
        properties.setCircuitFailureThreshold(2);
        properties.setCircuitOpenMs(60000);
        String key = "models|" + baseUrl();
        circuitBreaker.recordFailure(key); // 已有 1 次失败

        responseStatus = 400;
        assertThrows(AgentCallException.class, () -> client.call(invocation()));
        assertEquals("CLOSED", circuitBreaker.stateOf(key));

        // 若 400 被误判为成功，失败计数会被清零，这次 500 只到 1/2，不会熔断
        responseStatus = 500;
        assertThrows(AgentCallException.class, () -> client.call(invocation()));
        assertEquals("OPEN", circuitBreaker.stateOf(key),
                "400 必须中性：既不累计失败，也不能重置已有失败计数");
    }

    @Test
    void call_shouldNotAddTokensWhenReservationIsActive() throws Exception {
        stubModel();
        AiGatewayGuard.Reservation active =
                org.mockito.Mockito.mock(AiGatewayGuard.Reservation.class);
        when(active.isActive()).thenReturn(true);
        when(active.isBlocked()).thenReturn(false);
        when(guard.reserve(anyString(), anyString())).thenReturn(active);
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";

        client.call(invocation());

        verify(guard).settle(eq(active), eq(15L));
        verify(guard, never()).addTokens(anyString(), anyString(), anyLong());
    }

    @Test
    void call_shouldRejectWhenModelIsNotConfigured() {
        when(modelGatewayResolver.resolve(eq(1L))).thenReturn(null);

        AgentCallException e = assertThrows(AgentCallException.class,
                () -> client.call(invocation()));

        assertTrue(e.getMessage().contains("尚未配置"));
        verify(guard, never()).check(anyString(), anyString());
    }
}
