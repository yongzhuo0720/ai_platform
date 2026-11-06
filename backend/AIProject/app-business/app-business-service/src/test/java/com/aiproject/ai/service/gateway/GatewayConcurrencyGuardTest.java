package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
