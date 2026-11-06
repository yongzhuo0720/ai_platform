package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 熔断状态机：连续失败打开、冷却后单探针、成功恢复、失败重新熔断。
 *
 * <p>用可替换时钟而不是 sleep：熔断的判定完全由「连续失败次数 + 冷却时间」决定，
 * 真实等待只会让测试变慢且不稳定。</p>
 */
class GatewayCircuitBreakerTest {

    private static final String KEY = "chat|http://dify";

    private AiGatewayProperties properties;
    private GatewayCircuitBreaker breaker;
    private long now = 1_000_000L;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        properties.setCircuitFailureThreshold(3);
        properties.setCircuitOpenMs(10_000);
        breaker = new GatewayCircuitBreaker();
        breaker.properties = properties;
        breaker.clock = () -> now;
    }

    @Test
    void opensAfterConsecutiveFailuresAndRejectsUntilCooldownEnds() {
        assertNotNull(allow(KEY));
        breaker.recordFailure(KEY);
        breaker.recordFailure(KEY);
        assertEquals("CLOSED", breaker.stateOf(KEY), "两次失败还没到阈值");

        breaker.recordFailure(KEY);
        assertEquals("OPEN", breaker.stateOf(KEY));
        assertNull(breaker.tryAcquire(KEY), "熔断期间必须直接拒绝，不能再调上游");
        assertEquals(10, breaker.retryAfterSeconds(KEY), "剩余冷却时间应向上取整为 10 秒");

        now += 10_000;
        assertNotNull(breaker.tryAcquire(KEY), "冷却结束应放行半开探针");
        assertEquals("HALF_OPEN", breaker.stateOf(KEY));
    }

    @Test
    void halfOpenAllowsOnlyOneProbeAndSuccessRestores() {
        openCircuit();
        now += 10_000;

        GatewayCircuitBreaker.Admission probe = breaker.tryAcquire(KEY);
        assertNotNull(probe);
        assertNull(breaker.tryAcquire(KEY), "探针未返回前不能再放第二个请求");

        breaker.recordSuccess(KEY);
        probe.release();
        assertEquals("CLOSED", breaker.stateOf(KEY));
        assertNotNull(breaker.tryAcquire(KEY), "恢复后应正常放行");
    }

    @Test
    void failedProbeReopensCircuitForAnotherCooldown() {
        openCircuit();
        now += 10_000;

        GatewayCircuitBreaker.Admission probe = breaker.tryAcquire(KEY);
        assertNotNull(probe);
        breaker.recordFailure(KEY);
        probe.release();

        assertEquals("OPEN", breaker.stateOf(KEY), "探针失败必须重新熔断");
        assertNull(breaker.tryAcquire(KEY));
        now += 9_000;
        assertNull(breaker.tryAcquire(KEY), "重新计时后未满冷却仍拒绝");
        now += 1_000;
        assertNotNull(breaker.tryAcquire(KEY));
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        breaker.recordFailure(KEY);
        breaker.recordFailure(KEY);
        breaker.recordSuccess(KEY);
        breaker.recordFailure(KEY);
        breaker.recordFailure(KEY);

        assertEquals("CLOSED", breaker.stateOf(KEY), "成功一次后应从零重新计数");

        breaker.recordFailure(KEY);
        assertEquals("OPEN", breaker.stateOf(KEY));
    }

    @Test
    void disabledThresholdNeverOpens() {
        properties.setCircuitFailureThreshold(0);
        for (int i = 0; i < 100; i++) {
            breaker.recordFailure(KEY);
        }
        assertEquals("CLOSED", breaker.stateOf(KEY));
        assertNotNull(breaker.tryAcquire(KEY));
    }

    @Test
    void upstreamsAreIsolated() {
        openCircuit();
        assertEquals("OPEN", breaker.stateOf(KEY));
        assertEquals("CLOSED", breaker.stateOf("models|http://other"));
        assertNotNull(breaker.tryAcquire("models|http://other"),
                "一个上游熔断不能影响另一个上游");
    }

    private void openCircuit() {
        for (int i = 0; i < properties.getCircuitFailureThreshold(); i++) {
            breaker.recordFailure(KEY);
        }
        assertEquals("OPEN", breaker.stateOf(KEY));
    }

    private GatewayCircuitBreaker.Admission allow(String key) {
        GatewayCircuitBreaker.Admission admission = breaker.tryAcquire(key);
        assertNotNull(admission);
        return admission;
    }
}
