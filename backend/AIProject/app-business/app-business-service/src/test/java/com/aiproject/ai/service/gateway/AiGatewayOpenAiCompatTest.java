package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.AiApiTokenBean;
import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.AiApiTokenHolder;
import com.aiproject.ai.service.holde.AiCallLogHolder;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAI 兼容端点（{@code /v1/**}）的网关语义。
 *
 * <p>守三件事：
 * <ul>
 *   <li>没有令牌身份就不能调用——外部协议入口最容易忘记判身份；</li>
 *   <li>模型由请求体决定，所以必须过「按名字解析 + 令牌白名单」两道，
 *       否则一个密钥就能调平台上配置的所有模型；</li>
 *   <li>错误响应必须是 OpenAI 形状，且不能污染站内网关的信封格式。</li>
 * </ul>
 */
class AiGatewayOpenAiCompatTest {

    private static final String CHAT_PATH = "/v1/chat/completions";

    private static final String VALID_BODY =
            "{\"model\":\"demo\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

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
        service.concurrencyGuard = new GatewayConcurrencyGuard(service.properties);
        when(service.idempotencyGuard.acquire(anyString(), anyString(), any()))
                .thenReturn(AiIdempotencyGuard.Acquisition.none());
        // 白名单是纯逻辑，用真身才能顺带验到「iid / 平台名 / 上游名任一命中」
        service.apiTokenHolder = new AiApiTokenHolder();
        when(service.guard.check(anyString(), anyString())).thenReturn(AiGatewayGuard.Decision.allow());
        when(service.guard.reserve(anyString(), anyString()))
                .thenReturn(AiGatewayGuard.Reservation.none());
    }

    @AfterEach
    void tearDown() {
        upstream.close();
    }

    /** 令牌通过后：模型由请求体解析、转发前换成上游模型名、响应原样透传 */
    @Test
    void routesByBodyModelAndRewritesUpstreamModelName() throws Exception {
        directModel("demo", "up-model");
        upstream.responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

        MockHttpServletResponse response = chat(chatRequest(VALID_BODY, token(null)));

        assertEquals(200, response.getStatus());
        JsonNode forwarded = mapper.readTree(upstream.requestBody);
        assertEquals("up-model", forwarded.path("model").asText());
        assertEquals("hi", forwarded.path("messages").get(0).path("content").asText());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("\"ok\""));
    }

    /** v1 调用与站内对话同一套账：module=models，端点是 v1/chat/completions */
    @Test
    void recordsAuditUnderModelsModuleWithV1Endpoint() {
        directModel("demo", "up-model");

        chat(chatRequest(VALID_BODY, token(null)));

        ArgumentCaptor<AiCallLogBean> captor = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(service.callLogHolder).record(captor.capture());
        AiCallLogBean entry = captor.getValue();
        assertEquals("u1", entry.getUserId());
        assertEquals(ModelGatewayResolver.MODULE_MODELS, entry.getModule());
        // 审计端点不带前导斜杠，与站内直连的 {iid}/chat/completions 同一套写法
        assertEquals("v1/chat/completions", entry.getEndpoint());
        assertEquals("success", entry.getStatus());
    }

    /** 拦截器没写身份（被绕过或未注册）时绝不能匿名调模型 */
    @Test
    void requestWithoutTokenIdentityIsRejectedWithOpenAiAuthenticationError() throws Exception {
        directModel("demo", "up-model");

        MockHttpServletResponse response = chat(chatRequest(VALID_BODY, null));

        assertEquals(401, response.getStatus());
        assertEquals(0, upstream.calls.get());
        JsonNode error = errorOf(response);
        assertEquals("authentication_error", error.path("type").asText());
        assertEquals("invalid_api_key", error.path("code").asText());
    }

    @Test
    void unknownModelIsRejectedWith404BeforeCallingUpstream() throws Exception {
        when(service.modelGatewayResolver.resolveByName(anyString())).thenReturn(null);

        MockHttpServletResponse response = chat(chatRequest(VALID_BODY, token(null)));

        assertEquals(404, response.getStatus());
        assertEquals(0, upstream.calls.get());
        JsonNode error = errorOf(response);
        assertEquals("model_not_found", error.path("code").asText());
        assertEquals("invalid_request_error", error.path("type").asText());
        verify(service.guard, never()).reserve(anyString(), anyString());
    }

    @Test
    void missingModelFieldIsRejectedWith400() throws Exception {
        directModel("demo", "up-model");

        MockHttpServletResponse response = chat(chatRequest(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", token(null)));

        assertEquals(400, response.getStatus());
        assertEquals(0, upstream.calls.get());
        assertEquals("model_required", errorOf(response).path("code").asText());
    }

    @Test
    void emptyMessagesIsRejectedWith400AndDoesNotConsumeQuota() throws Exception {
        directModel("demo", "up-model");

        MockHttpServletResponse response = chat(chatRequest(
                "{\"model\":\"demo\",\"messages\":[]}", token(null)));

        assertEquals(400, response.getStatus());
        assertEquals("invalid_request", errorOf(response).path("code").asText());
        verify(service.guard, never()).reserve(anyString(), anyString());
    }

    /** 白名单没命中就是 403，而且不能提前解析出模型名之外的信息给猜测者 */
    @Test
    void allowListMissIsRejectedWith403PermissionError() throws Exception {
        directModel("demo", "up-model");

        MockHttpServletResponse response = chat(chatRequest(VALID_BODY, token("other-model")));

        assertEquals(403, response.getStatus());
        assertEquals(0, upstream.calls.get());
        JsonNode error = errorOf(response);
        assertEquals("model_not_allowed", error.path("code").asText());
        assertEquals("permission_error", error.path("type").asText());
    }

    /** 白名单里写 iid、平台名或上游模型名都算命中：/v1/models 列的是平台名，客户端可能改用 iid */
    @Test
    void allowListMatchesIidPlatformNameAndUpstreamName() throws Exception {
        directModel("demo", "up-model");
        for (String allowed : new String[]{"7", "DEMO", "up-model"}) {
            upstream.calls.set(0);
            MockHttpServletResponse response = chat(chatRequest(VALID_BODY, token(allowed)));
            assertEquals(200, response.getStatus(), "白名单应命中: " + allowed + " -> " + bodyOf(response));
        }
    }

    /** 限流走 OpenAI 的 rate_limit_exceeded 类型，SDK 才会退避重试而不是当参数错误 */
    @Test
    void rateLimitBlockMapsToRateLimitErrorType() throws Exception {
        directModel("demo", "up-model");
        when(service.guard.check("u1", ModelGatewayResolver.MODULE_MODELS)).thenReturn(
                AiGatewayGuard.Decision.block("rate_limited", "请求太频繁，请稍后重试"));

        MockHttpServletResponse response = chat(chatRequest(VALID_BODY, token(null)));

        assertEquals(429, response.getStatus());
        JsonNode error = errorOf(response);
        assertEquals("rate_limit_exceeded", error.path("type").asText());
        assertEquals("rate_limited", error.path("code").asText());
    }

    /** 请求体超限在 v1 上也是 413，并且给 OpenAI 形状 */
    @Test
    void oversizeBodyIs413WithOpenAiShape() throws Exception {
        directModel("demo", "up-model");
        char[] text = new char[2 * 1024 * 1024 + 1024];
        Arrays.fill(text, 'x');
        String body = "{\"model\":\"demo\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + new String(text) + "\"}]}";

        MockHttpServletResponse response = chat(chatRequest(body, token(null)));

        assertEquals(413, response.getStatus());
        assertEquals(0, upstream.calls.get());
        assertEquals("payload_too_large", errorOf(response).path("code").asText());
    }

    /** 流式：转发上游前注入 include_usage，否则拿不到 usage 就无法计费 */
    @Test
    void streamRequestRelaysSseAndAsksUpstreamForUsage() throws Exception {
        directModel("demo", "up-model");
        upstream.responseContentType = "text/event-stream";
        upstream.responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n"
                + "data: {\"usage\":{\"total_tokens\":42}}\n\ndata: [DONE]\n\n";

        MockHttpServletResponse response = chat(chatRequest(
                "{\"model\":\"demo\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                token(null)));

        assertEquals(200, response.getStatus());
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(content.contains("data: [DONE]"), content);
        JsonNode forwarded = mapper.readTree(upstream.requestBody);
        assertTrue(forwarded.path("stream_options").path("include_usage").asBoolean());
        ArgumentCaptor<AiCallLogBean> captor = ArgumentCaptor.forClass(AiCallLogBean.class);
        verify(service.callLogHolder).record(captor.capture());
        assertEquals(Long.valueOf(42L), captor.getValue().getTotalTokens());
    }

    /**
     * 形状开关必须只在一次请求内生效。
     * <p>
     * Tomcat 线程是复用的：如果 v1 请求把 ThreadLocal 留在身上，
     * 下一个站内请求的错误就会变成 {@code {"error":...}}，前端按 {@code code} 分支的
     * 逻辑直接失明。所以这里在同一条链路上先跑 v1、再跑站内，验两边形状互不污染。
     */
    @Test
    void errorShapeDoesNotLeakIntoPlatformPath() throws Exception {
        directModel("demo", "up-model");
        chat(chatRequest("{\"model\":\"nope\"}", token(null)));

        MockHttpServletRequest platformRequest = new MockHttpServletRequest(
                "POST", "/api/aiGateway/models/7/chat/completions");
        platformRequest.setContentType("application/json");
        platformRequest.setContent("{bad json".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse platformResponse = new MockHttpServletResponse();
        service.proxy(ModelGatewayResolver.MODULE_MODELS, "u1", platformRequest, platformResponse);

        assertEquals(400, platformResponse.getStatus());
        JsonNode root = mapper.readTree(platformResponse.getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.has("error"), "站内网关应仍是 {code,message,status} 信封: " + root);
        assertEquals("invalid_request", root.path("code").asText());
    }

    /** GET /v1/models：列表里的 id 必须能直接拿去调 /v1/chat/completions */
    @Test
    void modelsListIsFilteredByTokenAllowList() throws Exception {
        when(service.modelGatewayResolver.directConfigs()).thenReturn(
                Arrays.asList(config(7L, "demo", "up-model"), config(8L, "other", "up-other")));

        JsonNode all = jsonOf(models(token(null)));
        assertEquals("list", all.path("object").asText());
        assertEquals(2, all.path("data").size());
        assertEquals("demo", all.path("data").get(0).path("id").asText());
        assertEquals("model", all.path("data").get(0).path("object").asText());
        assertEquals("ai-platform", all.path("data").get(0).path("owned_by").asText());
        assertTrue(all.path("data").get(0).path("created").asLong() > 0);

        JsonNode filtered = jsonOf(models(token("other")));
        assertEquals(1, filtered.path("data").size());
        assertEquals("other", filtered.path("data").get(0).path("id").asText());

        // 白名单写 iid 也要能列出对应模型，否则「按列表调用」会撞上 403
        JsonNode byIid = jsonOf(models(token("7")));
        assertEquals(1, byIid.path("data").size());
        assertEquals("demo", byIid.path("data").get(0).path("id").asText());
    }

    @Test
    void modelsListWithoutTokenIsRejectedWith401() throws Exception {
        MockHttpServletResponse response = models(null);

        assertEquals(401, response.getStatus());
        assertEquals("authentication_error", errorOf(response).path("type").asText());
    }

    /**
     * 上传超限（#16）：声明长度超过 multipart 上限时，
     * 必须是 413 payload_too_large，而不是等 relayMultipart 抛异常后误报 502 上游错误。
     */
    @Test
    void oversizeUploadIs413NotUpstreamError() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/aiGateway/file/files/upload") {
            @Override
            public long getContentLengthLong() {
                return 130L * 1024 * 1024;
            }
        };
        request.setContentType("multipart/form-data; boundary=browser");
        when(service.configResolver.isSupported("file")).thenReturn(true);
        when(service.configResolver.resolve("file")).thenReturn(
                new AiGatewayConfigResolver.ResolvedConfig(upstream.baseUrl(), "test-key"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy("file", "u1", request, response);

        assertEquals(413, response.getStatus());
        assertEquals(0, upstream.calls.get(), "超限的上传不该建连打到上游");
        JsonNode root = jsonOf(response);
        assertEquals("payload_too_large", root.path("code").asText());
        assertTrue(root.path("message").asText().contains("120MB"), root.path("message").asText());
    }

    private JsonNode errorOf(MockHttpServletResponse response) throws Exception {
        return jsonOf(response).path("error");
    }

    private JsonNode jsonOf(MockHttpServletResponse response) {
        String raw = bodyOf(response);
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON: " + raw, e);
        }
    }

    /** MockHttpServletResponse 取响应体带受检异常，测试里统一收敛成断言信息 */
    private static String bodyOf(MockHttpServletResponse response) {
        try {
            return response.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读不到响应体: " + e, e);
        }
    }

    private MockHttpServletResponse models(AiApiTokenBean token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        if (token != null) {
            request.setAttribute(AiApiTokenHolder.ATTR_TOKEN, token);
            request.setAttribute(AiApiTokenHolder.ATTR_UID, token.getUserId());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.writeOpenAiModels(request, response);
        return response;
    }

    private MockHttpServletResponse chat(MockHttpServletRequest request) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.proxyOpenAiChat(request, response);
        return response;
    }

    private void directModel(String name, String upstreamModel) {
        when(service.modelGatewayResolver.resolveByName(anyString())).thenReturn(
                new ModelGatewayResolver.ResolvedModel(7L, upstream.baseUrl(), "test-key",
                        upstreamModel, name));
        // 站内直连按 URL 里的 iid 取模型，错误形状不串味的用例要走那条链路
        when(service.modelGatewayResolver.resolve(7L)).thenReturn(
                new ModelGatewayResolver.ResolvedModel(7L, upstream.baseUrl(), "test-key",
                        upstreamModel, name));
    }

    private static AiApiTokenBean token(String allowModels) {
        AiApiTokenBean bean = new AiApiTokenBean();
        bean.setIid(1L);
        bean.setName("ci-token");
        bean.setUserId("u1");
        bean.setAllowModels(allowModels);
        bean.setEnable(Boolean.TRUE);
        return bean;
    }

    private static ModelsConfigBean config(long iid, String name, String upstreamModel) {
        ModelsConfigBean bean = new ModelsConfigBean();
        bean.setIid(iid);
        bean.setName(name);
        bean.setUpstreamModel(upstreamModel);
        bean.setProtocol(ModelGatewayResolver.PROTOCOL_OPENAI);
        bean.setEnable(Boolean.TRUE);
        bean.setCreatetime(new Timestamp(System.currentTimeMillis()));
        return bean;
    }

    private MockHttpServletRequest chatRequest(String body, AiApiTokenBean token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", CHAT_PATH);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (token != null) {
            request.setAttribute(AiApiTokenHolder.ATTR_TOKEN, token);
            request.setAttribute(AiApiTokenHolder.ATTR_UID, token.getUserId());
        }
        return request;
    }
}
