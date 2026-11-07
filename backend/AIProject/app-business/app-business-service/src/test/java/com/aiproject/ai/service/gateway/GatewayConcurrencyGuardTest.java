package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayConcurrencyGuardTest {

    private AiGatewayProperties properties;
    private GatewayConcurrencyGuard guard;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        guard = new GatewayConcurrencyGuard(properties);
    }

    @Test
    void rejectsWhenModuleLimitIsReachedAndRecoversAfterRelease() {
        properties.setMaxConcurrentPerModule(1);

        GatewayConcurrencyGuard.Lease lease = guard.tryAcquire("chat");
        assertNotNull(lease);
        assertNull(guard.tryAcquire("chat"), "同一模块超过上限必须立刻拒绝");

        // 模块之间互不挤占：长对话不该饿死知识库检索
        GatewayConcurrencyGuard.Lease knowledge = guard.tryAcquire("knowledgeChat");
        assertNotNull(knowledge);

        lease.release();
        GatewayConcurrencyGuard.Lease again = guard.tryAcquire("chat");
        assertNotNull(again, "释放后许可必须可以重新获取");
        again.release();
        knowledge.release();
    }

    @Test
    void disabledLimitNeverRejectsAndReleaseIsSafe() {
        properties.setMaxConcurrentPerModule(0);

        for (int i = 0; i < 100; i++) {
            GatewayConcurrencyGuard.Lease lease = guard.tryAcquire("chat");
            assertNotNull(lease, "关闭并发限制时不应拒绝任何请求");
            lease.release();
        }
    }

    @Test
    void differentModulesHaveIndependentCounters() {
        properties.setMaxConcurrentPerModule(1);

        GatewayConcurrencyGuard.Lease chat = guard.tryAcquire("chat");
        GatewayConcurrencyGuard.Lease models = guard.tryAcquire("models");
        GatewayConcurrencyGuard.Lease file = guard.tryAcquire("file");
        assertNotNull(chat);
        assertNotNull(models);
        assertNotNull(file);
        assertNull(guard.tryAcquire("models"), "models 自己的配额已用完");

        chat.release();
        models.release();
        file.release();
    }

    @Test
    void redisRejectionWinsOverLocalCapacity() {
        properties.setMaxConcurrentPerModule(1);
        FakeRedisTemplate redis = new FakeRedisTemplate();
        redis.result = 0L;
        guard.stringRedisTemplate = redis;

        // 本机信号量是空的，但全局（其他实例）已占满：必须拒绝，而不是退回本机
        assertNull(guard.tryAcquire("chat"));
        assertEquals("ai:gateway:concurrent:chat", redis.keyCalls.get(0).get(0));
    }

    @Test
    void redisAcquireAndReleaseUseSharedKeyAndLease() {
        properties.setMaxConcurrentPerModule(1);
        FakeRedisTemplate redis = new FakeRedisTemplate();
        guard.stringRedisTemplate = redis;

        GatewayConcurrencyGuard.Lease lease = guard.tryAcquire("chat");
        assertNotNull(lease);
        lease.release();

        assertEquals(2, redis.keyCalls.size());
        assertEquals("ai:gateway:concurrent:chat", redis.keyCalls.get(0).get(0));
        Object[] acquireArgs = redis.argCalls.get(0);
        assertEquals("1", acquireArgs[1], "脚本必须使用配置的模块并发上限");
        // 默认流式总时长 10 分钟，租约再加 1 分钟余量
        assertEquals(String.valueOf(11 * 60 * 1000L), acquireArgs[4]);
        assertEquals(acquireArgs[3], redis.argCalls.get(1)[0], "释放必须移除同一个 member");
    }

    @Test
    void fallsBackToLocalSemaphoreWhenRedisThrows() {
        properties.setMaxConcurrentPerModule(1);
        FakeRedisTemplate redis = new FakeRedisTemplate();
        redis.failure = new RuntimeException("redis down");
        guard.stringRedisTemplate = redis;

        GatewayConcurrencyGuard.Lease first = guard.tryAcquire("chat");
        assertNotNull(first, "Redis 故障时不能把请求全部拒绝");
        assertNull(guard.tryAcquire("chat"), "退回本机后仍要遵守单实例上限");

        first.release();
        assertNotNull(guard.tryAcquire("chat"));
    }

    @Test
    void unlimitedModeNeverTouchesRedis() {
        properties.setMaxConcurrentPerModule(0);
        FakeRedisTemplate redis = new FakeRedisTemplate();
        guard.stringRedisTemplate = redis;

        GatewayConcurrencyGuard.Lease lease = guard.tryAcquire("chat");
        assertNotNull(lease);
        lease.release();

        assertEquals(0, redis.keyCalls.size());
    }

    /** 只拦截脚本执行并记录参数，避免 Mockito 对可变参数的匹配歧义 */
    private static final class FakeRedisTemplate extends StringRedisTemplate {
        private Long result = 1L;
        private RuntimeException failure;
        private final List<List<String>> keyCalls = new ArrayList<List<String>>();
        private final List<Object[]> argCalls = new ArrayList<Object[]>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            keyCalls.add(keys);
            argCalls.add(Arrays.copyOf(args, args.length));
            if (failure != null) {
                throw failure;
            }
            return (T) result;
        }
    }
}
