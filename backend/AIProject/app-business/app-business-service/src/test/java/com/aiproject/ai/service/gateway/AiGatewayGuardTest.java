package com.aiproject.ai.service.gateway;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.aiproject.ai.service.holde.AiUserQuotaHolder;
import com.heneng.hnsp.common.redis.service.RedisServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配额预扣：并发请求在「检查」与「完成后计费」之间不能一起击穿额度。
 * 这里锁住预扣、结算（多退少补）、失败释放，以及治理依赖不可用时的 fail-closed 行为。
 */
class AiGatewayGuardTest {

    private AiGatewayGuard guard;
    private RedisServer redisServer;
    private RedisTemplate<String, Object> redisTemplate;
    private AiUserQuotaHolder quotaHolder;
    private MyUserBaseDbHolder userBaseDbHolder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guard = new AiGatewayGuard();
        redisServer = mock(RedisServer.class);
        redisTemplate = mock(RedisTemplate.class);
        quotaHolder = mock(AiUserQuotaHolder.class);
        userBaseDbHolder = mock(MyUserBaseDbHolder.class);
        guard.redisServer = redisServer;
        guard.redisTemplate = redisTemplate;
        guard.quotaHolder = quotaHolder;
        guard.userBaseDbHolder = userBaseDbHolder;
        guard.properties = new AiGatewayProperties();
    }

    @Test
    void reserveIncrementsBeforeRequestAndSettleChargesOnlyActualUsage() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(1500L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");

        assertTrue(reservation.isActive());
        assertFalse(reservation.isBlocked());
        assertEquals(1500L, reservation.getTokens());
        verify(redisServer).increment(anyString(), eq(1500L));

        guard.settle(reservation, 900L);

        verify(redisServer).decrement(anyString(), eq(600L));
    }

    @Test
    void settleAddsDifferenceWhenActualUsageExceedsReserve() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(1500L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");
        guard.settle(reservation, 2000L);

        verify(redisServer).increment(anyString(), eq(500L));
    }

    @Test
    void failedRequestReleasesReservation() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(1500L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");
        guard.settle(reservation, 0L);

        verify(redisServer).decrement(anyString(), eq(1500L));
        verify(redisServer, never()).increment(anyString(), eq(500L));
    }

    @Test
    void settleIsIdempotent() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(1500L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");
        guard.settle(reservation, 900L);
        guard.settle(reservation, 900L);

        verify(redisServer, times(1)).decrement(anyString(), eq(600L));
    }

    @Test
    void overQuotaReservationBlocksAndRollsBack() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(10001L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");

        assertTrue(reservation.isBlocked());
        assertFalse(reservation.isActive());
        assertTrue(reservation.getBlockedMessage().contains("今日 AI 额度已用完"));
        verify(redisServer).decrement(anyString(), eq(1500L));
    }

    @Test
    void unitQuotaExceededRollsBackBothPools() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        AiUserQuotaBean unitQuota = new AiUserQuotaBean();
        unitQuota.setDailyTokenQuota(5000L);
        when(quotaHolder.find("unit:g1")).thenReturn(unitQuota);
        MyUserBaseDb user = new MyUserBaseDb();
        user.setGid("g1");
        when(userBaseDbHolder.queryUserByUid("u1")).thenReturn(user);
        when(redisServer.increment(contains(":u1:"), eq(1500L))).thenReturn(1500L);
        when(redisServer.increment(contains("unit:g1"), eq(1500L))).thenReturn(5100L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");

        assertTrue(reservation.isBlocked());
        assertTrue(reservation.getBlockedMessage().contains("所在部门"));
        verify(redisServer).decrement(contains("unit:g1"), eq(1500L));
        verify(redisServer).decrement(contains(":u1:"), eq(1500L));
    }

    @Test
    void unlimitedQuotaSkipsReservation() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(0L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");

        assertFalse(reservation.isActive());
        assertFalse(reservation.isBlocked());
        verify(redisServer, never()).increment(anyString(), anyLong());
    }

    @Test
    void nonQuotaModuleSkipsReservation() {
        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "file");

        assertFalse(reservation.isActive());
        verify(redisServer, never()).increment(anyString(), anyLong());
    }

    @Test
    void redisFailureRejectsInsteadOfFailingOpen() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L)))
                .thenThrow(new RuntimeException("redis down"));

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");

        // 2026-11-01 改成 fail-closed：预扣读不到就放行，等于一次 Redis 抖动期间额度控制完全失效
        assertTrue(reservation.isBlocked());
        assertFalse(reservation.isActive());
        assertEquals(AiGatewayGuard.CODE_GUARD_UNAVAILABLE, reservation.getBlockedCode());
    }

    @Test
    void reserveWithoutIncrementCountRejects() {
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        // 自增没生效时 RedisServer 返回 null：既不能假装已占用，也不能当作没超额
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(null);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");

        assertTrue(reservation.isBlocked());
        assertEquals(AiGatewayGuard.CODE_GUARD_UNAVAILABLE, reservation.getBlockedCode());
    }

    @Test
    void quotaReadFailureRejectsInsteadOfAllowing() {
        when(quotaHolder.effectiveQuota("u1")).thenThrow(new RuntimeException("db down"));

        AiGatewayGuard.Decision decision = guard.check("u1", "chat");

        assertFalse(decision.isAllowed());
        assertEquals(AiGatewayGuard.CODE_GUARD_UNAVAILABLE, decision.getCode());
    }

    @Test
    void rateLimitFailureRejectsInsteadOfAllowing() {
        when(redisServer.increment(anyString(), eq(1L))).thenThrow(new RuntimeException("redis down"));

        AiGatewayGuard.Decision decision = guard.check("u1", "chat");

        assertFalse(decision.isAllowed());
        assertEquals(AiGatewayGuard.CODE_GUARD_UNAVAILABLE, decision.getCode());
    }

    @Test
    void overQuotaStillReportsQuotaExceededCode() {
        // 新增 guard_unavailable 码之后，正常超额不能被误标成「治理组件坏了」
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(10001L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");

        assertTrue(reservation.isBlocked());
        assertEquals("quota_exceeded", reservation.getBlockedCode());
    }
}
