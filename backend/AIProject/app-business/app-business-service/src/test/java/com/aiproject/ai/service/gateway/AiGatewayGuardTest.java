package com.aiproject.ai.service.gateway;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.aiproject.ai.service.holde.AiUserQuotaHolder;
import com.heneng.hnsp.common.redis.service.RedisServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
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

    /**
     * 跨零点完成的长请求：结算必须改「预扣时写的那一天」的 key。
     * 如果按结算时刻重新拼日期，第一天的预扣永远退不回来，第二天的额度反而被扣。
     */
    @Test
    void settleUsesKeysCapturedAtReservationTime() {
        AiGatewayGuard.Reservation reservation = AiGatewayGuard.Reservation.active(
                "u1", "unit:g1", 1500L,
                "ai:gateway:tokens:u1:20260101", "ai:gateway:tokens:unit:g1:20260101", null);

        guard.settle(reservation, 900L);

        verify(redisServer).decrement("ai:gateway:tokens:u1:20260101", 600L);
        verify(redisServer).decrement("ai:gateway:tokens:unit:g1:20260101", 600L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserveWritesLedgerForCrashRecovery() {
        StringRedisTemplate stringRedis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(stringRedis.opsForHash()).thenReturn(hash);
        when(stringRedis.opsForZSet()).thenReturn(zset);
        guard.stringRedisTemplate = stringRedis;
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(1500L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");

        assertNotNull(reservation.getLedgerKey());
        assertTrue(reservation.getLedgerKey().startsWith("ai:gateway:reservation:"));
        assertTrue(reservation.getUserKey().startsWith("ai:gateway:tokens:u1:"));
        verify(hash).putAll(eq(reservation.getLedgerKey()), anyMap());
        verify(zset).add(eq("ai:gateway:reservation:index"),
                eq(reservation.getLedgerKey()), anyDouble());
    }

    /**
     * 台账已被别的进程结算/回收（Lua 返回 0）时不能再调整计数：
     * 否则同一笔预扣会被退两次，用户额度凭空变多。
     */
    @Test
    @SuppressWarnings("unchecked")
    void settleSkipsAdjustmentWhenLedgerAlreadyClaimed() {
        StringRedisTemplate stringRedis = mock(StringRedisTemplate.class);
        when(stringRedis.execute(any(DefaultRedisScript.class), anyList())).thenReturn(0L);
        guard.stringRedisTemplate = stringRedis;
        AiGatewayGuard.Reservation reservation = AiGatewayGuard.Reservation.active(
                "u1", null, 1500L, "uk", null, "ledger-1");

        guard.settle(reservation, 900L);

        verify(redisServer, never()).decrement(anyString(), anyLong());
        verify(redisServer, never()).increment(anyString(), anyLong());
    }

    /** Redis 认领失败时退化为「无台账」结算：宁可少一次保护，也不能不结算 */
    @Test
    @SuppressWarnings("unchecked")
    void settleFallsBackToAdjustmentWhenLedgerClaimFails() {
        StringRedisTemplate stringRedis = mock(StringRedisTemplate.class);
        when(stringRedis.execute(any(DefaultRedisScript.class), anyList()))
                .thenThrow(new RuntimeException("redis down"));
        guard.stringRedisTemplate = stringRedis;
        AiGatewayGuard.Reservation reservation = AiGatewayGuard.Reservation.active(
                "u1", null, 1500L, "uk", null, "ledger-1");

        guard.settle(reservation, 900L);

        verify(redisServer).decrement("uk", 600L);
    }

    /** 容器被 kill -9 后 finally 没执行：定时回收按台账退还预扣 */
    @Test
    @SuppressWarnings("unchecked")
    void reaperRefundsStaleReservation() {
        StringRedisTemplate stringRedis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(stringRedis.opsForZSet()).thenReturn(zset);
        when(stringRedis.opsForHash()).thenReturn(hash);
        guard.stringRedisTemplate = stringRedis;
        when(zset.rangeByScore(eq("ai:gateway:reservation:index"), eq(0D),
                anyDouble(), eq(0L), eq(100L)))
                .thenReturn(new LinkedHashSet<String>(Arrays.asList("ledger-1")));
        Map<Object, Object> fields = new LinkedHashMap<Object, Object>();
        fields.put("userId", "u1");
        fields.put("tokens", "1500");
        fields.put("userKey", "uk");
        fields.put("unitKey", "gk");
        when(hash.entries("ledger-1")).thenReturn(fields);
        when(stringRedis.execute(any(DefaultRedisScript.class), anyList())).thenReturn(1L);

        guard.reapExpiredReservations();

        verify(redisServer).decrement("uk", 1500L);
        verify(redisServer).decrement("gk", 1500L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reaperSkipsReservationAlreadySettled() {
        StringRedisTemplate stringRedis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(stringRedis.opsForZSet()).thenReturn(zset);
        when(stringRedis.opsForHash()).thenReturn(hash);
        guard.stringRedisTemplate = stringRedis;
        when(zset.rangeByScore(eq("ai:gateway:reservation:index"), eq(0D),
                anyDouble(), eq(0L), eq(100L)))
                .thenReturn(new LinkedHashSet<String>(Arrays.asList("ledger-1")));
        Map<Object, Object> fields = new LinkedHashMap<Object, Object>();
        fields.put("userId", "u1");
        fields.put("tokens", "1500");
        fields.put("userKey", "uk");
        when(hash.entries("ledger-1")).thenReturn(fields);
        when(stringRedis.execute(any(DefaultRedisScript.class), anyList())).thenReturn(0L);

        guard.reapExpiredReservations();

        verify(redisServer, never()).decrement(anyString(), anyLong());
    }

    /** 没有 StringRedisTemplate 时台账功能整体降级，不影响原有预扣/结算 */
    @Test
    void reservationWorksWithoutLedgerTemplate() {
        guard.stringRedisTemplate = null;
        when(quotaHolder.effectiveQuota("u1")).thenReturn(10000L);
        when(redisServer.increment(anyString(), eq(1500L))).thenReturn(1500L);

        AiGatewayGuard.Reservation reservation = guard.reserve("u1", "chat");
        guard.settle(reservation, 900L);

        assertNull(reservation.getLedgerKey());
        verify(redisServer).decrement(anyString(), eq(600L));
    }
}
