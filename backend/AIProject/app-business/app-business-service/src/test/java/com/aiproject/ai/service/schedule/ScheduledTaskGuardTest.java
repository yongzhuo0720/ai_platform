package com.aiproject.ai.service.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务互斥锁的单元测试。
 *
 * <p>重点验证三类行为：拿到锁才执行、锁被占时跳过、Redis 故障时降级执行。
 * 另外锁的释放必须带 owner token——TTL 过期后不能再误删别的实例刚拿到的锁。</p>
 */
@ExtendWith(MockitoExtension.class)
class ScheduledTaskGuardTest {

    private static final String TASK = "weekly-report";
    private static final String REDIS_KEY = "ai:sched:" + TASK;

    @Mock
    StringRedisTemplate stringRedisTemplate;

    @Mock
    ValueOperations<String, String> valueOperations;

    @InjectMocks
    ScheduledTaskGuard guard;

    @Test
    void run_withoutRedis_shouldExecuteAsSingleInstance() {
        ScheduledTaskGuard noRedis = new ScheduledTaskGuard();
        AtomicInteger runs = new AtomicInteger();

        assertTrue(noRedis.run(TASK, 60, runs::incrementAndGet));

        assertEquals(1, runs.get());
    }

    @Test
    void run_whenLockAcquired_shouldExecuteAndReleaseWithOwnerToken() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), any(), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L);
        AtomicInteger runs = new AtomicInteger();

        assertTrue(guard.run(TASK, 300, runs::incrementAndGet));

        assertEquals(1, runs.get());
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq(REDIS_KEY), owner.capture(),
                eq(300L), eq(TimeUnit.SECONDS));

        ArgumentCaptor<Object> releaseOwner = ArgumentCaptor.forClass(Object.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class),
                eq(Collections.singletonList(REDIS_KEY)), releaseOwner.capture());
        // 释放时必须带抢锁时的 owner，避免 TTL 过期后误删别的实例的锁
        assertEquals(owner.getValue(), releaseOwner.getValue());
    }

    @Test
    void run_whenAnotherInstanceHoldsLock_shouldSkip() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), any(), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);
        AtomicInteger runs = new AtomicInteger();

        assertFalse(guard.run(TASK, 300, runs::incrementAndGet));

        assertEquals(0, runs.get());
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void run_whenRedisThrows_shouldFailOpenAndExecute() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), any(), eq(300L), eq(TimeUnit.SECONDS)))
                .thenThrow(new IllegalStateException("redis down"));
        AtomicInteger runs = new AtomicInteger();

        assertTrue(guard.run(TASK, 300, runs::incrementAndGet));

        assertEquals(1, runs.get());
        // 没拿到锁就不该释放，否则可能删掉别的实例的锁
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void run_whenSetIfAbsentReturnsNull_shouldExecuteWithoutRelease() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), any(), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(null);
        AtomicInteger runs = new AtomicInteger();

        assertTrue(guard.run(TASK, 300, runs::incrementAndGet));

        assertEquals(1, runs.get());
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void run_whenBodyThrows_shouldPropagateAndStillRelease() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), any(), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L);

        assertThrows(IllegalStateException.class, () -> guard.run(TASK, 300, () -> {
            throw new IllegalStateException("任务失败");
        }));

        verify(stringRedisTemplate, times(1))
                .execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void run_whenReleaseFails_shouldNotAffectTaskResult() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), any(), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new IllegalStateException("redis down"));
        AtomicInteger runs = new AtomicInteger();

        assertDoesNotThrow(() -> assertTrue(guard.run(TASK, 300, runs::incrementAndGet)));

        assertEquals(1, runs.get());
    }

    @Test
    void run_withInvalidArguments_shouldNotExecuteOrTouchRedis() {
        AtomicInteger runs = new AtomicInteger();

        assertFalse(guard.run(null, 300, runs::incrementAndGet));
        assertFalse(guard.run("  ", 300, runs::incrementAndGet));
        assertFalse(guard.run(TASK, 300, null));

        assertEquals(0, runs.get());
        verify(stringRedisTemplate, never()).opsForValue();
    }
}
