package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.config.SsoProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SSO state 签名 / 验签 / 一次性 nonce 的单元测试。
 *
 * <p>演进（对应升级问题 P2-3）：</p>
 * <ul>
 *   <li>最初 state = 裸 uid，可任意伪造 → HMAC 签名防伪造；</li>
 *   <li>{uid}.{HMAC(uid)} 确定性、永不过期，截获可无限重放 →
 *       2026-11-19 加时间戳（10min 窗口）+ 随机 nonce（Redis 一次性消费）。</li>
 * </ul>
 * 本测试覆盖：签名防伪造、防篡改、时间窗口、nonce 唯一性、格式恶意输入、Redis fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class SsoStateSigningTest {

    @Mock
    SsoProperties ssoProperties;

    @Mock
    StringRedisTemplate stringRedisTemplate;

    @Mock
    ValueOperations<String, String> valueOperations;

    @InjectMocks
    SsoTokenBeanController controller;

    private void givenSecret() {
        when(ssoProperties.getClientSecret()).thenReturn("test-client-secret");
    }

    /** 让 buildSignedState 里的 Redis set 不抛异常（仅签发 state 的用例需要） */
    private void givenRedisWorks() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doNothing().when(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    /** 正常流程：签发的 state 能解析回原来的 uid */
    @Test
    void signedState_shouldRoundTrip() {
        givenSecret();
        givenRedisWorks();
        String state = controller.buildSignedState("u1001");
        assertEquals(4, state.split("\\.").length, "state 应含 uid/时间戳/nonce/签名四段");
        assertEquals("u1001", controller.parseUidFromState(state));
    }

    /** 每次签发都不同（nonce 随机）—— 修复"确定性可重放"的关键 */
    @Test
    void signedState_shouldBeDifferentEveryTime() {
        givenSecret();
        givenRedisWorks();
        assertNotEquals(controller.buildSignedState("u1001"), controller.buildSignedState("u1001"),
                "同一 uid 两次签发的 state 必须不同，否则仍可重放");
    }

    @Test
    void shouldReject_forgedSignature() {
        // 伪造的签名在常量时间比较前应被识别；这里给正确 secret 以便走到验签分支
        givenSecret();
        String goodTs = String.valueOf(System.currentTimeMillis());
        String payload = "u1001." + goodTs + ".abc";
        assertNull(controller.parseUidFromState(payload + ".forged_signature"));
    }

    @Test
    void shouldReject_tamperedUid() {
        givenSecret();
        givenRedisWorks();
        String[] parts = controller.buildSignedState("attacker").split("\\.");
        // 只换 uid，其余（ts/nonce/签名）不动 → 验签失败
        assertNull(controller.parseUidFromState("victim." + parts[1] + "." + parts[2] + "." + parts[3]));
    }

    @Test
    void shouldReject_tamperedNonce() {
        givenSecret();
        givenRedisWorks();
        String[] parts = controller.buildSignedState("u1001").split("\\.");
        assertNull(controller.parseUidFromState(parts[0] + "." + parts[1] + ".evilnonce." + parts[3]),
                "nonce 被改应验签失败");
    }

    @Test
    void shouldReject_expiredTimestamp() {
        givenSecret();
        // 20 分钟前的时间戳（超出 10 分钟窗口），签名正确也应被时间窗口拒绝
        String ts = String.valueOf(System.currentTimeMillis() - 20 * 60 * 1000L);
        String payload = "u1001." + ts + ".somenonce";
        assertNull(controller.parseUidFromState(payload + "." + controller.signState(payload)));
    }

    @Test
    void shouldReject_futureTimestamp() {
        givenSecret();
        String ts = String.valueOf(System.currentTimeMillis() + 5 * 60 * 1000L);
        String payload = "u1001." + ts + ".somenonce";
        assertNull(controller.parseUidFromState(payload + "." + controller.signState(payload)));
    }

    @Test
    void shouldReject_stateSignedWithDifferentSecret() {
        givenSecret();
        givenRedisWorks();
        String state = controller.buildSignedState("u1001");

        when(ssoProperties.getClientSecret()).thenReturn("another-secret");
        assertNull(controller.parseUidFromState(state));
    }

    @Test
    void shouldReject_malformedState() {
        // 这些输入在解析阶段提前返回，不碰签名/Redis，按 Mockito strict 规则不给多余 stub
        assertNull(controller.parseUidFromState(null));
        assertNull(controller.parseUidFromState(""));
        assertNull(controller.parseUidFromState("no-separator"));
        assertNull(controller.parseUidFromState(".onlysignature"));
        assertNull(controller.parseUidFromState("a.b.c"));
    }

    @Test
    void parseNonceFromState_shouldExtractCorrectly() {
        givenSecret();
        givenRedisWorks();
        String state = controller.buildSignedState("u1001");
        String nonce = controller.parseNonceFromState(state);
        assertNotNull(nonce);
        assertEquals(32, nonce.length(), "nonce 是去掉横杠的 UUID(32位)");
        assertTrue(state.contains(nonce));
    }

    /** 签名本身稳定：相同 payload 的 HMAC 恒定（验签可靠性的基础） */
    @Test
    void signature_shouldBeStable() {
        givenSecret();
        assertEquals(controller.signState("u1001.123.nonce"), controller.signState("u1001.123.nonce"));
    }

    /** fail-closed：Redis 存不进 nonce 时签发失败，而不是发一个注定无法回调的 URL */
    @Test
    void buildSignedState_shouldFailClosedWhenRedisDown() {
        givenSecret();
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        assertThrows(RuntimeException.class, () -> controller.buildSignedState("u1001"));
    }
}
