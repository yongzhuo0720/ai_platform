package com.aiproject.ai.service.gateway;

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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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

        Integer hits = ReflectionTestUtils.invokeMethod(service, "applyKnowledgeContext", body, "u1", "kb", 4);

        assertEquals(Integer.valueOf(2), hits);
        String prompt = body.path("messages").get(0).path("content").asText();
        assertTrue(prompt.indexOf("relevant-first") < prompt.indexOf("distractor-second"));
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
