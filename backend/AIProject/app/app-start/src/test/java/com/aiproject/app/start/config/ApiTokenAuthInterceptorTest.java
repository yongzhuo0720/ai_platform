package com.aiproject.app.start.config;

import com.aiproject.ai.service.bean.AiApiTokenBean;
import com.aiproject.ai.service.holde.AiApiTokenHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /v1/**} 的令牌门禁。
 *
 * <p>这条前缀不在 {@code LoginAuthInterceptor} 的受保护清单里，所以它是外部调用方
 * 唯一的把门代码：任何「以为 Controller 会判空」的假设都不成立。
 * 这里验的是结构底线——拿不出有效令牌就进不到 Controller，
 * 而错误必须是真实 401 + OpenAI 错误形状（SDK 按状态码判断成败，
 * 给它 HTTP 200 会被当成调用成功）。</p>
 */
class ApiTokenAuthInterceptorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "sk-0123456789abcdef0123456789abcdef01234567";

    private AiApiTokenHolder holder;
    private ApiTokenAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        holder = mock(AiApiTokenHolder.class);
        interceptor = new ApiTokenAuthInterceptor(provider(holder));
    }

    @Test
    void optionsPreflightPassesWithoutCheckingAnyToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/v1/chat/completions");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(holder, never()).verify(anyString());
    }

    @Test
    void missingHeaderIsRejectedWithReal401AndOpenAiShape() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request(null), response, new Object()));

        assertEquals(401, response.getStatus());
        JsonNode error = body(response).path("error");
        assertEquals("authentication_error", error.path("type").asText());
        assertEquals("invalid_api_key", error.path("code").asText());
        assertTrue(error.path("request_id").asText().length() > 0);
        assertNotNull(response.getHeader("X-Request-Id"));
        verify(holder, never()).verify(anyString());
    }

    /** 只有 Bearer 这一种方案：Basic / 裸密钥 / 空值都拒绝，且不去查库 */
    @ParameterizedTest
    @ValueSource(strings = {"Basic QWxhMTIz", "Bearer", "Bearer   ",
            "bearerless", "token sk-abc", ""})
    void malformedAuthorizationIsRejectedWithoutTouchingTheStore(String header) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request(header), response, new Object()));

        assertEquals(401, response.getStatus());
        assertEquals("authentication_error", body(response).path("error").path("type").asText());
        verify(holder, never()).verify(anyString());
    }

    @Test
    void validTokenIsPassedOnWithIdentityInRequestAttributes() throws Exception {
        AiApiTokenBean token = new AiApiTokenBean();
        token.setIid(1L);
        token.setUserId("u1");
        when(holder.verify(SECRET)).thenReturn(token);

        MockHttpServletRequest request = request("Bearer " + SECRET);
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(token, request.getAttribute(AiApiTokenHolder.ATTR_TOKEN));
        assertEquals("u1", request.getAttribute(AiApiTokenHolder.ATTR_UID));
    }

    /** 方案名大小写不敏感（RFC 7235），密钥本身只裁外层空白 */
    @Test
    void schemeMatchingIsCaseInsensitiveAndTokenIsTrimmed() throws Exception {
        when(holder.verify(SECRET)).thenReturn(new AiApiTokenBean());

        assertTrue(interceptor.preHandle(request("  BEARER  " + SECRET + "  "),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void unknownOrRevokedTokenIsRejected() throws Exception {
        when(holder.verify(anyString())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request("Bearer " + SECRET), response, new Object()));

        assertEquals(401, response.getStatus());
        assertEquals("invalid_api_key", body(response).path("error").path("code").asText());
    }

    /** 校验代码抛异常时按未认证处理：鉴权 fail-open 等于网关大门敞开 */
    @Test
    void storeFailureFailsClosed() throws Exception {
        when(holder.verify(anyString())).thenThrow(new IllegalStateException("db down"));
        MockHttpServletRequest request = request("Bearer " + SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertEquals(401, response.getStatus());
        assertNull(request.getAttribute(AiApiTokenHolder.ATTR_UID),
                "拒绝的请求不能留下身份属性");
    }

    @Test
    void missingHolderIsRejectedAsServiceUnavailableNotPassThrough() throws Exception {
        ApiTokenAuthInterceptor broken = new ApiTokenAuthInterceptor(provider(null));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(broken.preHandle(request("Bearer " + SECRET), response, new Object()));

        assertEquals(503, response.getStatus());
        assertEquals("api_error", body(response).path("error").path("type").asText());
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return MAPPER.readTree(response.getContentAsString());
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject() {
                if (instance == null) {
                    throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("");
                }
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }
}
