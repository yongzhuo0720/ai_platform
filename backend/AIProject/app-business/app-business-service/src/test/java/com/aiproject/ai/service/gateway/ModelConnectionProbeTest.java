package com.aiproject.ai.service.gateway;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.gateway.dto.ModelConnectionTestResult;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型连接测试的真实 HTTP 探测。
 *
 * <p>用本地 HttpServer 而不是 mock 掉网络层：连接测试的价值就在于
 * 「真的发出请求、真的看到状态码」，把 HTTP 也 mock 掉等于只测了 if/else。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModelConnectionProbeTest {

    private static final String ADMIN = "admin";
    private static final String SECRET = "sk-secret-for-test";
    private static final Long IID = 7L;

    @Mock
    ModelsConfigBeanHolder modelsConfigBeanHolder;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @InjectMocks
    ModelConnectionProbe probe;

    private StubServer stub;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubServer();
        lenient().when(myUserBaseDbHolder.isAdmin(ADMIN)).thenReturn(true);
        lenient().when(modelsConfigBeanHolder.findByIid(IID)).thenReturn(config(stub.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void modelsEndpoint_shouldReportOkWhenModelListed() {
        stub.modelsStatus = 200;
        stub.modelsBody = "{\"data\":[{\"id\":\"gpt-a\"},{\"id\":\"other\"}]}";

        Result<ModelConnectionTestResult> result = probe.test(ADMIN, request());

        assertEquals(0, result.getResp_code());
        ModelConnectionTestResult data = result.getDatas();
        assertEquals(ModelConnectionTestResult.STATUS_OK, data.getStatus());
        assertTrue(data.isModelAvailable());
        assertEquals(Integer.valueOf(200), data.getHttpStatus());
        assertEquals(Long.valueOf(IID), data.getIid());
        assertTrue(data.getLatencyMs() >= 0);
        assertTrue(data.getCheckedAt() > 0);
    }

    @Test
    void modelsEndpoint_shouldDistinguishMissingModelFromAuthFailure() {
        stub.modelsStatus = 200;
        stub.modelsBody = "{\"data\":[{\"id\":\"another-model\"}]}";

        ModelConnectionTestResult missing = probe.test(ADMIN, request()).getDatas();
        assertEquals(ModelConnectionTestResult.STATUS_MODEL_MISSING, missing.getStatus());
        assertFalse(missing.isModelAvailable());
        assertTrue(missing.getMessage().contains("gpt-a"));

        stub.modelsStatus = 401;
        ModelConnectionTestResult unauthorized = probe.test(ADMIN, request()).getDatas();
        assertEquals(ModelConnectionTestResult.STATUS_AUTH_FAILED, unauthorized.getStatus());
        assertEquals(Integer.valueOf(401), unauthorized.getHttpStatus());
    }

    @Test
    void modelsEndpoint404_shouldFallBackToMinimalChatRequest() {
        stub.modelsStatus = 404;
        stub.chatStatus = 200;
        stub.chatBody = "{\"choices\":[]}";

        ModelConnectionTestResult data = probe.test(ADMIN, request()).getDatas();

        assertEquals(ModelConnectionTestResult.STATUS_OK, data.getStatus());
        assertTrue(data.isModelAvailable());
        assertEquals(1, stub.chatHits.get());
        assertTrue(data.getMessage().contains("对话接口"));
    }

    @Test
    void unreachableHost_shouldReturnUnreachableWithoutLeakingAddress() throws Exception {
        int closedPort;
        ServerSocket socket = new ServerSocket(0);
        closedPort = socket.getLocalPort();
        socket.close();
        when(modelsConfigBeanHolder.findByIid(IID))
                .thenReturn(config("http://127.0.0.1:" + closedPort));

        ModelConnectionTestResult data = probe.test(ADMIN, request()).getDatas();

        assertEquals(ModelConnectionTestResult.STATUS_UNREACHABLE, data.getStatus());
        assertNull(data.getHttpStatus());
        assertFalse(data.getMessage().contains("127.0.0.1"));
        assertFalse(data.getMessage().contains(SECRET));
    }

    /** 返回给浏览器的任何字段都不能出现密钥或服务地址 */
    @Test
    void result_shouldNeverContainSecretOrBaseUrl() throws Exception {
        stub.modelsStatus = 200;
        stub.modelsBody = "{\"data\":[{\"id\":\"gpt-a\"}]}";

        ModelConnectionTestResult data = probe.test(ADMIN, request()).getDatas();
        String json = new ObjectMapper().writeValueAsString(data);

        assertFalse(json.contains(SECRET));
        assertFalse(json.contains(stub.baseUrl()));
    }

    @Test
    void nonAdmin_shouldBeRejectedBeforeTouchingConfigOrNetwork() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        Result<ModelConnectionTestResult> result = probe.test("u1", request());

        assertFalse(Integer.valueOf(0).equals(result.getResp_code()));
        assertNull(result.getDatas());
        verify(modelsConfigBeanHolder, never()).findByIid(IID);
        assertEquals(0, stub.modelsHits.get());
    }

    @Test
    void missingConfig_shouldReturnReadableError() {
        when(modelsConfigBeanHolder.findByIid(IID)).thenReturn(null);

        Result<ModelConnectionTestResult> result = probe.test(ADMIN, request());

        assertFalse(Integer.valueOf(0).equals(result.getResp_code()));
        assertNotNull(result.getResp_msg());
        assertEquals(0, stub.modelsHits.get());
    }

    @Test
    void embeddingConfig_shouldProbeEmbeddingsEndpoint() {
        when(modelsConfigBeanHolder.findByIid(IID)).thenReturn(embeddingConfig(stub.baseUrl()));
        stub.embeddingsStatus = 200;
        stub.embeddingsBody = "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}";

        ModelConnectionTestResult data = probe.test(ADMIN, request()).getDatas();

        assertEquals(ModelConnectionTestResult.STATUS_OK, data.getStatus());
        assertTrue(data.isModelAvailable());
        assertEquals("bge-m3-test", data.getUpstreamModel());
        assertTrue(data.getMessage().contains("向量接口"));
        assertEquals(1, stub.embeddingsHits.get());
        assertEquals(0, stub.modelsHits.get());
    }

    @Test
    void embeddingConfig_shouldReportAuthFailureWithoutLeakingSecret() {
        when(modelsConfigBeanHolder.findByIid(IID)).thenReturn(embeddingConfig(stub.baseUrl()));
        stub.embeddingsStatus = 401;

        ModelConnectionTestResult data = probe.test(ADMIN, request()).getDatas();

        assertEquals(ModelConnectionTestResult.STATUS_AUTH_FAILED, data.getStatus());
        assertFalse(data.isModelAvailable());
        assertFalse(data.getMessage().contains(SECRET));
    }

    @Test
    void rerankConfig_shouldProbeRerankEndpoint() {
        when(modelsConfigBeanHolder.findByIid(IID)).thenReturn(rerankConfig(stub.baseUrl()));
        stub.rerankStatus = 200;
        stub.rerankBody = "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}";

        ModelConnectionTestResult data = probe.test(ADMIN, request()).getDatas();

        assertEquals(ModelConnectionTestResult.STATUS_OK, data.getStatus());
        assertTrue(data.isModelAvailable());
        assertEquals("rerank-test", data.getUpstreamModel());
        assertTrue(data.getMessage().contains("重排接口"));
        assertEquals(1, stub.rerankHits.get());
    }

    @Test
    void rerankConfig_shouldSurfaceRejectedModel() {
        when(modelsConfigBeanHolder.findByIid(IID)).thenReturn(rerankConfig(stub.baseUrl()));
        stub.rerankStatus = 400;

        ModelConnectionTestResult data = probe.test(ADMIN, request()).getDatas();

        assertEquals(ModelConnectionTestResult.STATUS_HTTP_ERROR, data.getStatus());
        assertEquals(Integer.valueOf(400), data.getHttpStatus());
        assertTrue(data.getMessage().contains("核对上游模型名"));
    }

    @Test
    void unsupportedProtocol_shouldReturnReadableMessage() {
        ModelsConfigBean config = config(stub.baseUrl());
        config.setProtocol("some-other-protocol");
        when(modelsConfigBeanHolder.findByIid(IID)).thenReturn(config);

        Result<ModelConnectionTestResult> result = probe.test(ADMIN, request());

        assertFalse(Integer.valueOf(0).equals(result.getResp_code()));
        assertTrue(result.getResp_msg().contains("暂不支持连接测试"));
        assertEquals(0, stub.modelsHits.get());
    }

    private ModelsConfigBean request() {
        ModelsConfigBean bean = new ModelsConfigBean();
        bean.setIid(IID);
        return bean;
    }

    private ModelsConfigBean config(String baseUrl) {
        ModelsConfigBean bean = new ModelsConfigBean();
        bean.setIid(IID);
        bean.setName("测试模型");
        bean.setProtocol("openai");
        bean.setBaseUrl(baseUrl);
        bean.setApiKey(SECRET);
        bean.setUpstreamModel("gpt-a");
        bean.setEnable(true);
        return bean;
    }

    private ModelsConfigBean embeddingConfig(String baseUrl) {
        ModelsConfigBean bean = config(baseUrl);
        bean.setName("测试向量模型");
        bean.setUpstreamModel(null);
        bean.setEmbedModel("bge-m3-test");
        return bean;
    }

    private ModelsConfigBean rerankConfig(String baseUrl) {
        ModelsConfigBean bean = config(baseUrl);
        bean.setName("测试重排模型");
        bean.setProtocol("rerank");
        bean.setUpstreamModel("rerank-test");
        return bean;
    }

    /** 只实现连接测试用到的两个端点；状态码与响应体由每个用例设置 */
    private static class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger modelsHits = new AtomicInteger();
        private final AtomicInteger chatHits = new AtomicInteger();
        private final AtomicInteger embeddingsHits = new AtomicInteger();
        private final AtomicInteger rerankHits = new AtomicInteger();
        private volatile int modelsStatus = 200;
        private volatile String modelsBody = "{\"data\":[]}";
        private volatile int chatStatus = 200;
        private volatile String chatBody = "{}";
        private volatile int embeddingsStatus = 200;
        private volatile String embeddingsBody = "{\"data\":[]}";
        private volatile int rerankStatus = 200;
        private volatile String rerankBody = "{\"results\":[]}";

        private StubServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/models", exchange -> {
                modelsHits.incrementAndGet();
                respond(exchange, modelsStatus, modelsBody);
            });
            server.createContext("/chat/completions", exchange -> {
                chatHits.incrementAndGet();
                respond(exchange, chatStatus, chatBody);
            });
            server.createContext("/embeddings", exchange -> {
                embeddingsHits.incrementAndGet();
                respond(exchange, embeddingsStatus, embeddingsBody);
            });
            server.createContext("/rerank", exchange -> {
                rerankHits.incrementAndGet();
                respond(exchange, rerankStatus, rerankBody);
            });
            server.start();
        }

        private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } finally {
                exchange.close();
            }
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
