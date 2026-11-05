package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.AiCallLogHolder;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiGatewayServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AiGatewayService service;
    private AiUpstreamStub upstream;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new AiUpstreamStub();
        service = new AiGatewayService();
        service.properties = new AiGatewayProperties();
        service.configResolver = mock(AiGatewayConfigResolver.class);
        service.guard = mock(AiGatewayGuard.class);
        service.callLogHolder = mock(AiCallLogHolder.class);
        service.kbRagHolder = mock(KbRagHolder.class);
        service.modelGatewayResolver = mock(ModelGatewayResolver.class);
        service.priceResolver = mock(ModelPriceResolver.class);
        service.idempotencyGuard = mock(AiIdempotencyGuard.class);
        when(service.idempotencyGuard.acquire(anyString(), anyString(), any()))
                .thenReturn(AiIdempotencyGuard.Acquisition.none());
        // 默认不预扣（等同配额不限）；需要验证预扣行为的用例自行覆盖
        when(service.guard.reserve(anyString(), anyString()))
                .thenReturn(AiGatewayGuard.Reservation.none());
    }

    @AfterEach
    void tearDown() {
        upstream.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "knowledgeChat"})
    void historyQueryUsesAuthenticatedUserAndPreservesOtherParameters(String module) {
        MockHttpServletRequest request = request("GET", module, "conversations");
        request.setQueryString("user=victim&limit=20&%75ser=another&last_id=a%2Bb");

        MockHttpServletResponse response = proxy(module, request, "u+1");

        assertEquals(200, response.getStatus());
        assertEquals("limit=20&last_id=a%2Bb&user=u%2B1", upstream.query);
    }

    @Test
    void historyQueryAddsMissingUser() {
        MockHttpServletRequest request = request("GET", "chat", "messages");
        request.setQueryString("conversation_id=abc");

        assertEquals(200, proxy("chat", request, "u1").getStatus());
        assertEquals("conversation_id=abc&user=u1", upstream.query);
    }

    @Test
    void datasetQueryIsNotRewritten() {
        MockHttpServletRequest request = request("GET", "file", "datasets");
        request.setQueryString("page=2");

        assertEquals(200, proxy("file", request, "u1").getStatus());
        assertEquals("page=2", upstream.query);
    }

    @Test
    void jsonBodyReplacesClientUser() throws Exception {
        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hello\",\"user\":\"victim\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, proxy("chat", request, "u1").getStatus());
        assertEquals("u1", mapper.readTree(upstream.requestBody).path("user").asText());
        assertEquals("hello", mapper.readTree(upstream.requestBody).path("query").asText());
    }

    @Test
    void jsonWithUnknownContentLengthStillReceivesTrustedUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/aiGateway/chat/chat-messages") {
            @Override
            public long getContentLengthLong() { return -1; }

            @Override
            public int getContentLength() { return -1; }
        };
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hello\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, proxy("chat", request, "u1").getStatus());
        assertEquals("u1", mapper.readTree(upstream.requestBody).path("user").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{bad json", "[]", "{} {}"})
    void malformedJsonIsRejectedBeforeForwarding(String payload) {
        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent(payload.getBytes(StandardCharsets.UTF_8));

        assertEquals(400, proxy("chat", request, "u1").getStatus());
        assertEquals(0, upstream.calls.get());
    }

    @Test
    void oversizedJsonIsRejectedBeforeForwarding() throws Exception {
        char[] text = new char[2 * 1024 * 1024];
        Arrays.fill(text, 'x');
        ObjectNode payload = mapper.createObjectNode().put("query", new String(text)).put("user", "victim");
        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent(mapper.writeValueAsBytes(payload));

        assertEquals(413, proxy("chat", request, "u1").getStatus());
        assertEquals(0, upstream.calls.get());
    }

    /**
     * 预扣时就发现额度不够：必须在调用上游之前拒绝。
     * 否则「检查通过后并发压满额度」的请求照样打到上游，产生实际费用却永远记不上账。
     */
    @Test
    void blockedReservationRejectsWith429BeforeCallingUpstream() throws Exception {
        when(service.guard.reserve("u1", "chat")).thenReturn(
                AiGatewayGuard.Reservation.blocked("今日 AI 额度已用完（已用 1500 / 1500 tokens）"));
        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hi\"}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = proxy("chat", request, "u1");

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("quota_exceeded"));
        assertEquals(0, upstream.calls.get(), "额度不足时不能调用上游");
        verify(service.guard, never()).settle(any(AiGatewayGuard.Reservation.class), anyLong());
        verify(service.guard, never()).addTokens(anyString(), anyString(), anyLong());
    }

    /**
     * 网关只放行前端实际使用的 Dify 接口。
     * 修复前任何登录用户都能借服务端密钥访问 Dify 的整棵 API 树
     * （工作流、租户设置、模型供应商凭据等），这是权限边界问题而不是体验问题。
     */
    @Test
    void endpointOutsideAllowlistIsRejectedWith403BeforeUpstream() throws Exception {
        MockHttpServletRequest request = request("POST", "chat", "workflows/run");
        request.setContentType("application/json");
        request.setContent("{\"inputs\":{}}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = proxy("chat", request, "u1");

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8)
                .contains("upstream_path_forbidden"));
        assertEquals(0, upstream.calls.get(), "白名单外的路径不能打到上游");

        ArgumentCaptor<AiCallLogBean> captor = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(service.callLogHolder).record(captor.capture());
        assertEquals("failed", captor.getValue().getStatus());
        assertEquals(Integer.valueOf(403), captor.getValue().getHttpStatus());
    }

    @Test
    void encodedTraversalIsRejectedWith400BeforeUpstream() {
        MockHttpServletRequest request = request("GET", "file", "datasets/%2e%2e/admin");

        MockHttpServletResponse response = proxy("file", request, "u1");

        assertEquals(400, response.getStatus());
        assertEquals(0, upstream.calls.get());
    }

    /**
     * 同一个 Idempotency-Key 的重复请求必须在调用上游之前挡住：
     * 上一次可能已经扣过额度、建过数据，放过去就是重复计费/重复写入。
     */
    @Test
    void idempotencyConflictRejectsBeforeUpstreamAndReleasesReservation() throws Exception {
        when(service.configResolver.isSupported("chat")).thenReturn(true);
        when(service.configResolver.resolve("chat")).thenReturn(
                new AiGatewayConfigResolver.ResolvedConfig(upstream.baseUrl(), "test-key"));
        when(service.guard.check("u1", "chat")).thenReturn(AiGatewayGuard.Decision.allow());
        AiGatewayGuard.Reservation reservation =
                AiGatewayGuard.Reservation.active("u1", null, 1500L);
        when(service.guard.reserve("u1", "chat")).thenReturn(reservation);
        when(service.idempotencyGuard.acquire(eq("u1"), eq("chat"), any()))
                .thenReturn(AiIdempotencyGuard.Acquisition.rejected(409,
                        AiIdempotencyGuard.CODE_CONFLICT,
                        "相同请求已处理或正在处理中，请勿重复提交"));
        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hi\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy("chat", "u1", request, response);

        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8)
                .contains(AiIdempotencyGuard.CODE_CONFLICT));
        assertEquals(0, upstream.calls.get(), "重复请求不能再打上游");
        verify(service.guard).settle(reservation, 0L);

        ArgumentCaptor<AiCallLogBean> captor = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(service.callLogHolder).record(captor.capture());
        assertEquals("blocked", captor.getValue().getStatus());
        assertEquals(Integer.valueOf(409), captor.getValue().getHttpStatus());
    }

    /**
     * 治理组件（Redis / 配额表）自己不可用时是 503，不是 429。
     * 前端与看板要能分清「你额度用完了」和「管理员得去看看服务」。
     */
    @Test
    void guardUnavailableRejectsWith503InsteadOf429() throws Exception {
        when(service.guard.reserve("u1", "chat")).thenReturn(
                AiGatewayGuard.Reservation.blocked(AiGatewayGuard.CODE_GUARD_UNAVAILABLE,
                        "AI 网关暂时不可用（限流与配额依赖的服务未就绪），请稍后重试或联系管理员"));
        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hi\"}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = proxy("chat", request, "u1");

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8)
                .contains(AiGatewayGuard.CODE_GUARD_UNAVAILABLE));
        assertEquals(0, upstream.calls.get(), "治理不可用时也不能把请求打到上游");
    }

    /**
     * 503 必须同样覆盖模型直连路径：它的限流拦截点和 Dify 路径是两处代码，
     * 漏一处就会把「治理服务没就绪」报成「你被限流了」，看板也跟着误判。
     */
    @Test
    void guardUnavailableOnModelDirectPathAlsoReturns503() throws Exception {
        when(service.modelGatewayResolver.resolve(7L)).thenReturn(
                new ModelGatewayResolver.ResolvedModel(upstream.baseUrl(), "test-key", "up-model", "demo"));
        when(service.guard.check("u1", ModelGatewayResolver.MODULE_MODELS)).thenReturn(
                AiGatewayGuard.Decision.block(AiGatewayGuard.CODE_GUARD_UNAVAILABLE,
                        "AI 网关暂时不可用（限流与配额依赖的服务未就绪），请稍后重试或联系管理员"));

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/aiGateway/models/7/chat/completions");
        request.setContentType("application/json");
        request.setContent("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy(ModelGatewayResolver.MODULE_MODELS, "u1", request, response);

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8)
                .contains(AiGatewayGuard.CODE_GUARD_UNAVAILABLE));
        assertEquals(0, upstream.calls.get(), "治理不可用时不能把请求打到上游");
    }

    /** 预扣成功后按真实 usage 结算：只调 settle，不能再走 addTokens 重复累计 */
    @Test
    void successfulCallSettlesReservationWithActualUsage() {
        AiGatewayGuard.Reservation reservation =
                AiGatewayGuard.Reservation.active("u1", null, 1500L);
        when(service.guard.reserve("u1", "chat")).thenReturn(reservation);
        upstream.responseContentType = "text/event-stream";
        upstream.responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                + "data: {\"usage\":{\"total_tokens\":321}}\n\ndata: [DONE]\n\n";
        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hi\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, proxy("chat", request, "u1").getStatus());

        verify(service.guard).settle(reservation, 321L);
        verify(service.guard, never()).addTokens(anyString(), anyString(), anyLong());
    }

    /** 上游失败（含 200 里塞 error 的模型）不计费：预扣全额释放，用户没拿到结果就不占额度 */
    @Test
    void failedUpstreamReleasesReservation() {
        AiGatewayGuard.Reservation reservation =
                AiGatewayGuard.Reservation.active("u1", null, 1500L);
        when(service.guard.reserve("u1", "chat")).thenReturn(reservation);
        upstream.responseBody = "{\"error\":{\"message\":\"upstream boom\",\"code\":\"server_error\"}}";
        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hi\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, proxy("chat", request, "u1").getStatus());

        verify(service.guard).settle(reservation, 0L);
        verify(service.guard, never()).addTokens(anyString(), anyString(), anyLong());
    }

    /** 直连模型：请求体不合法时不预扣，避免用户为一次根本没发出去的请求占额度 */
    @Test
    void invalidModelRequestDoesNotReserveQuota() {
        when(service.modelGatewayResolver.resolve(7L)).thenReturn(
                new ModelGatewayResolver.ResolvedModel(upstream.baseUrl(), "test-key", "up-model", "demo"));
        when(service.guard.check("u1", ModelGatewayResolver.MODULE_MODELS))
                .thenReturn(AiGatewayGuard.Decision.allow());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/aiGateway/models/7/chat/completions");
        request.setContentType("application/json");
        request.setContent("{bad json".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.proxy(ModelGatewayResolver.MODULE_MODELS, "u1", request, response);

        assertEquals(400, response.getStatus());
        assertEquals(0, upstream.calls.get());
        verify(service.guard, never()).reserve(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "knowledgeChat"})
    void multipartUploadReplacesUserAndRetainsFile(String module) {
        MockHttpServletRequest request = request("POST", module, "files/upload");
        request.setContentType("multipart/form-data; boundary=browser");
        request.addPart(new MockPart("file", "note.txt", "file-content".getBytes(StandardCharsets.UTF_8)));
        request.addPart(new MockPart("user", "victim".getBytes(StandardCharsets.UTF_8)));
        request.addPart(new MockPart("user", "another".getBytes(StandardCharsets.UTF_8)));

        assertEquals(200, proxy(module, request, "u1").getStatus());
        String body = new String(upstream.requestBody, StandardCharsets.UTF_8);
        assertTrue(body.contains("filename=\"note.txt\""));
        assertTrue(body.contains("\r\n\r\nfile-content\r\n"));
        assertEquals(1, body.split("name=\"user\"", -1).length - 1);
        assertTrue(body.contains("name=\"user\"\r\n\r\nu1\r\n"));
    }

    @Test
    void knowledgeContextPreservesRerankPriorityOverRecallScore() throws Exception {
        KbSearchItem relevant = item("relevant-first", 0.01);
        KbSearchItem distractor = item("distractor-second", 0.05);
        KbSearchResult result = new KbSearchResult();
        result.setItems(Arrays.asList(relevant, distractor));
        when(service.kbRagHolder.search("u1", "question", "kb", 4)).thenReturn(Result.succeed(result));
        ObjectNode body = (ObjectNode) mapper.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"question\"}]}");

        List<KbSearchItem> injected = ReflectionTestUtils.invokeMethod(
                service, "applyKnowledgeContext", body, "u1", "kb", 4);

        assertEquals(2, injected.size());
        assertSame(relevant, injected.get(0));
        String prompt = body.path("messages").get(0).path("content").asText();
        assertTrue(prompt.indexOf("relevant-first") < prompt.indexOf("distractor-second"));
    }

    /**
     * 前端只展示网关真正注入的片段：来源事件必须出现在上游 SSE 之前，
     * 且包含全部注入片段；kb_name 等增强字段不能透传给上游。
     */
    @Test
    void kbSourcesEventPrecedesUpstreamSseAndStaysHiddenFromUpstream() throws Exception {
        KbSearchItem relevant = item("relevant-first", 0.01);
        KbSearchItem distractor = item("distractor-second", 0.05);
        KbSearchResult result = new KbSearchResult();
        result.setItems(Arrays.asList(relevant, distractor));
        when(service.kbRagHolder.search("u1", "question", "kb", 4)).thenReturn(Result.succeed(result));
        when(service.modelGatewayResolver.resolve(7L)).thenReturn(
                new ModelGatewayResolver.ResolvedModel(upstream.baseUrl(), "test-key", "up-model", "demo"));
        when(service.guard.check("u1", ModelGatewayResolver.MODULE_MODELS))
                .thenReturn(AiGatewayGuard.Decision.allow());

        upstream.responseContentType = "text/event-stream";
        upstream.responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n";

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/aiGateway/models/7/chat/completions");
        request.setContentType("application/json");
        request.setContent(("{\"stream\":true,\"kb_name\":\"kb\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"question\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy(ModelGatewayResolver.MODULE_MODELS, "u1", request, response);

        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(content.startsWith("event: kb_sources\ndata: {"), content);
        assertTrue(content.contains("\"docName\":\"source\""));
        assertTrue(content.indexOf("relevant-first") < content.indexOf("distractor-second"));
        assertTrue(content.contains("data: {\"choices\""));
        assertEquals("2", response.getHeader("X-KB-Hits"));

        String forwarded = new String(upstream.requestBody, StandardCharsets.UTF_8);
        assertFalse(forwarded.contains("kb_name"));
        assertFalse(forwarded.contains("kb_top_k"));
    }

    /**
     * 浏览器点「停止」关闭连接后：
     * 1. 不能把异常抛给容器（proxy 正常返回）；
     * 2. 审计记为 aborted，httpStatus 记上游真实状态而非笼统的 502；
     * 3. 必须关闭上游连接——否则上游继续生成、继续计费。
     */
    @Test
    void clientAbortMidStreamClosesUpstreamAndRecordsAborted() throws Exception {
        upstream.slowSse = true;
        when(service.configResolver.isSupported("chat")).thenReturn(true);
        when(service.configResolver.resolve("chat")).thenReturn(
                new AiGatewayConfigResolver.ResolvedConfig(upstream.baseUrl(), "test-key"));
        when(service.guard.check("u1", "chat")).thenReturn(AiGatewayGuard.Decision.allow());
        AiGatewayGuard.Reservation reservation =
                AiGatewayGuard.Reservation.active("u1", null, 1500L);
        when(service.guard.reserve("u1", "chat")).thenReturn(reservation);

        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hi\",\"user\":\"u1\"}".getBytes(StandardCharsets.UTF_8));

        service.proxy("chat", "u1", request, new AbortingResponse());

        ArgumentCaptor<AiCallLogBean> captor = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(service.callLogHolder).record(captor.capture());
        AiCallLogBean entry = captor.getValue();
        assertEquals("aborted", entry.getStatus());
        assertEquals("客户端已断开连接", entry.getErrorMessage());
        assertEquals("u1", entry.getUserId());
        assertEquals(Integer.valueOf(200), entry.getHttpStatus(),
                "中断发生在已收到上游响应之后，审计应记真实上游状态，而不是误报 502");

        assertTrue(upstream.clientGone.await(5, TimeUnit.SECONDS),
                "浏览器断开后网关必须关闭上游连接，否则上游会继续生成并计费");
        assertTrue(upstream.streamedChunks.get() < AiUpstreamStub.SLOW_SSE_ITERATIONS,
                "上游应在客户端断开后停止生成");
        verify(service.guard).settle(reservation, 0L);
    }

    /**
     * 「看到半截回答再点停止」不能免费。
     * <p>
     * 上一个测试覆盖的是「一个字节都没送出去就断开」，那种确实该全额释放；
     * 这里浏览器已经收到内容——上游真实生成过、也产生费用，而 SSE 的 usage 只在最后一帧
     * （Dify 的 message_end）里给，中断时拿不到准确值，所以按预扣值结算。
     * 修复前这里是 settle(0)，用户可以靠反复「快说完时点停止」无限白拿。
     */
    @Test
    void abortAfterPartialOutputIsBilledAtReservedAmount() throws Exception {
        upstream.slowSse = true;
        when(service.configResolver.isSupported("chat")).thenReturn(true);
        when(service.configResolver.resolve("chat")).thenReturn(
                new AiGatewayConfigResolver.ResolvedConfig(upstream.baseUrl(), "test-key"));
        when(service.guard.check("u1", "chat")).thenReturn(AiGatewayGuard.Decision.allow());
        AiGatewayGuard.Reservation reservation =
                AiGatewayGuard.Reservation.active("u1", null, 1500L);
        when(service.guard.reserve("u1", "chat")).thenReturn(reservation);

        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hi\",\"user\":\"u1\"}".getBytes(StandardCharsets.UTF_8));

        service.proxy("chat", "u1", request, new PartialAbortResponse(2048));

        verify(service.guard).settle(reservation, 1500L);
        assertTrue(upstream.streamedChunks.get() > 0, "上游应已实际产出内容");

        ArgumentCaptor<AiCallLogBean> captor = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(service.callLogHolder).record(captor.capture());
        assertEquals("aborted", captor.getValue().getStatus());
        assertEquals(Long.valueOf(1500L), captor.getValue().getTotalTokens(),
                "按预扣值计费后，审计里的用量必须等于真正扣掉的额度，否则看板显示空值而额度已被消耗");
    }

    /**
     * 非客户端原因的中途失败（上游连接重置、读超时、容器网络抖动）同样不能全额退款：
     * 只要上游已经受理并送出过正文，模型就真实生成过内容。
     * 这里用「浏览器端写入失败但不带 connection reset 语义」模拟，
     * 锁住 catch 里 aborted=false 分支也会按预扣值计费。
     */
    @Test
    void midStreamFailureAfterPartialOutputIsBilledAtReserve() throws Exception {
        upstream.failSseMidStream = true;
        when(service.configResolver.isSupported("chat")).thenReturn(true);
        when(service.configResolver.resolve("chat")).thenReturn(
                new AiGatewayConfigResolver.ResolvedConfig(upstream.baseUrl(), "test-key"));
        when(service.guard.check("u1", "chat")).thenReturn(AiGatewayGuard.Decision.allow());
        AiGatewayGuard.Reservation reservation =
                AiGatewayGuard.Reservation.active("u1", null, 1500L);
        when(service.guard.reserve("u1", "chat")).thenReturn(reservation);

        MockHttpServletRequest request = request("POST", "chat", "chat-messages");
        request.setContentType("application/json");
        request.setContent("{\"query\":\"hi\",\"user\":\"u1\"}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.proxy("chat", "u1", request, response);

        verify(service.guard).settle(reservation, 1500L);
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("partial"),
                "上游已送出的正文应转发给浏览器");

        ArgumentCaptor<AiCallLogBean> captor = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(service.callLogHolder).record(captor.capture());
        assertEquals("failed", captor.getValue().getStatus());
        assertEquals(Integer.valueOf(200), captor.getValue().getHttpStatus(),
                "审计记录上游真实响应状态：上游已受理并开始输出，只是流没有正常结束");
        assertTrue(captor.getValue().getErrorMessage().contains("提前结束"));
        assertEquals(Long.valueOf(1500L), captor.getValue().getTotalTokens());
    }

    /** 先正常送出 allowBytes 个字节，之后才模拟浏览器断开 */
    private static class PartialAbortResponse extends MockHttpServletResponse {
        private final long allowBytes;
        private long written;
        private boolean committed;

        PartialAbortResponse(long allowBytes) {
            this.allowBytes = allowBytes;
        }

        private final ServletOutputStream stream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }

            @Override
            public void write(int b) throws IOException {
                committed = true;
                if (written >= allowBytes) {
                    throw new IOException("Connection reset by peer");
                }
                written++;
            }
        };

        @Override
        public ServletOutputStream getOutputStream() {
            return stream;
        }

        @Override
        public boolean isCommitted() {
            return committed;
        }
    }

    /** 响应体第一次写入就抛连接重置，等价于浏览器已经关掉连接 */
    private static class AbortingResponse extends MockHttpServletResponse {
        private boolean committed;
        private final ServletOutputStream stream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }

            @Override
            public void write(int b) throws IOException {
                committed = true;
                throw new IOException("Connection reset by peer");
            }
        };

        @Override
        public ServletOutputStream getOutputStream() {
            return stream;
        }

        @Override
        public boolean isCommitted() {
            return committed;
        }
    }

    private KbSearchItem item(String content, double score) {
        KbSearchItem item = new KbSearchItem();
        item.setContent(content);
        item.setScore(score);
        item.setDocName("source");
        return item;
    }

    private MockHttpServletRequest request(String method, String module, String endpoint) {
        return new MockHttpServletRequest(method, "/api/aiGateway/" + module + "/" + endpoint);
    }

    private MockHttpServletResponse proxy(String module, MockHttpServletRequest request, String uid) {
        when(service.configResolver.isSupported(module)).thenReturn(true);
        when(service.configResolver.resolve(module)).thenReturn(
                new AiGatewayConfigResolver.ResolvedConfig(upstream.baseUrl(), "test-key"));
        when(service.guard.check(uid, module)).thenReturn(AiGatewayGuard.Decision.allow());
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.proxy(module, uid, request, response);
        return response;
    }
}
