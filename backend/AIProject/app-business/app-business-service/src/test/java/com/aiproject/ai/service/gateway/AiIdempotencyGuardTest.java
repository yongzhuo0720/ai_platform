package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写请求幂等：客户端超时重试不能变成重复计费 / 重复创建。
 * 这里锁住「不带键行为不变、重复键 409、Redis 不可用 503、成功保留 / 失败释放」。
 */
class AiIdempotencyGuardTest {

    private AiIdempotencyGuard guard;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guard = new AiIdempotencyGuard();
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        guard.stringRedisTemplate = redis;
    }

    @Test
    void getRequestWithoutHeaderIsNotSubjectToIdempotency() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/aiGateway/chat/conversations");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertFalse(acquisition.isRejected());
        assertNull(acquisition.getLease());
        verify(redis, never()).opsForValue();
    }

    @Test
    void postWithoutHeaderKeepsLegacyBehavior() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/aiGateway/chat/chat-messages");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertFalse(acquisition.isRejected());
        assertNull(acquisition.getLease());
        verify(redis, never()).opsForValue();
    }

    @Test
    void malformedKeyIsRejectedWith400() {
        MockHttpServletRequest request = postWithKey("short");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertTrue(acquisition.isRejected());
        assertEquals(400, acquisition.getHttpStatus());
        assertEquals(AiIdempotencyGuard.CODE_INVALID, acquisition.getCode());
        verify(redis, never()).opsForValue();
    }

    @Test
    void keyWithPathCharactersIsRejected() {
        MockHttpServletRequest request = postWithKey("../../etc/passwd");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertTrue(acquisition.isRejected());
        assertEquals(AiIdempotencyGuard.CODE_INVALID, acquisition.getCode());
    }

    @Test
    void anonymousRequestCannotClaimAKey() {
        MockHttpServletRequest request = postWithKey("request-0001");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("", "chat", request);

        assertTrue(acquisition.isRejected());
        assertEquals(401, acquisition.getHttpStatus());
    }

    @Test
    void redisUnavailableRejectsWith503InsteadOfSilentlyAllowing() {
        guard.stringRedisTemplate = null;
        MockHttpServletRequest request = postWithKey("request-0001");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertTrue(acquisition.isRejected());
        assertEquals(503, acquisition.getHttpStatus());
        assertEquals(AiIdempotencyGuard.CODE_UNAVAILABLE, acquisition.getCode());
    }

    @Test
    void firstRequestTakesLeaseAndKeepsKeyOnSuccess() {
        when(values.setIfAbsent(eq("ai:gateway:idem:u1:chat:request-0001"), eq("1"),
                eq(10L), eq(TimeUnit.MINUTES))).thenReturn(true);
        MockHttpServletRequest request = postWithKey("request-0001");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertFalse(acquisition.isRejected());
        assertNotNull(acquisition.getLease());
        assertEquals("ai:gateway:idem:u1:chat:request-0001", acquisition.getLease().getRedisKey());

        guard.complete(acquisition.getLease());
        verify(redis).expire("ai:gateway:idem:u1:chat:request-0001", 10, TimeUnit.MINUTES);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void duplicateKeyIsRejectedWith409() {
        when(values.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.MINUTES)))
                .thenReturn(false);
        MockHttpServletRequest request = postWithKey("request-0001");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertTrue(acquisition.isRejected());
        assertEquals(409, acquisition.getHttpStatus());
        assertEquals(AiIdempotencyGuard.CODE_CONFLICT, acquisition.getCode());
        assertNull(acquisition.getLease());
    }

    @Test
    void failedRequestReleasesKeySoClientCanRetry() {
        when(values.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);
        AiIdempotencyGuard.Acquisition acquisition =
                guard.acquire("u1", "chat", postWithKey("request-0001"));

        guard.release(acquisition.getLease());

        verify(redis).delete("ai:gateway:idem:u1:chat:request-0001");
        verify(redis, never()).expire(anyString(), eq(10L), eq(TimeUnit.MINUTES));
    }

    @Test
    void redisErrorOnAcquireRejectsWith503() {
        when(values.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.MINUTES)))
                .thenThrow(new RuntimeException("redis down"));
        MockHttpServletRequest request = postWithKey("request-0001");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertTrue(acquisition.isRejected());
        assertEquals(503, acquisition.getHttpStatus());
        assertEquals(AiIdempotencyGuard.CODE_UNAVAILABLE, acquisition.getCode());
    }

    @Test
    void keysAreScopedByUserAndModule() {
        when(values.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);

        guard.acquire("u1", "chat", postWithKey("request-0001"));
        guard.acquire("u2", "file", postWithKey("request-0001"));

        verify(values).setIfAbsent("ai:gateway:idem:u1:chat:request-0001", "1",
                10L, TimeUnit.MINUTES);
        verify(values).setIfAbsent("ai:gateway:idem:u2:file:request-0001", "1",
                10L, TimeUnit.MINUTES);
    }

    @Test
    void fallbackHeaderIsAccepted() {
        when(values.setIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/aiGateway/chat/chat-messages");
        request.addHeader(AiIdempotencyGuard.FALLBACK_HEADER, "request-0002");

        AiIdempotencyGuard.Acquisition acquisition = guard.acquire("u1", "chat", request);

        assertFalse(acquisition.isRejected());
        assertNotNull(acquisition.getLease());
    }

    private MockHttpServletRequest postWithKey(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/aiGateway/chat/chat-messages");
        request.addHeader(AiIdempotencyGuard.HEADER, key);
        return request;
    }
}
