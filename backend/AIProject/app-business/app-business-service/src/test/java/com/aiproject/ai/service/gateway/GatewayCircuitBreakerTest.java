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
        properties.setCircuitProbeTimeoutMs(5_000);
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

        breaker.recordSuccess(probe);
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
        breaker.recordFailure(probe);
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

    /** 熔断前已在途的普通请求迟到成功，不能替还没验证的探针关闭熔断 */
    @Test
    void staleNonProbeSuccessCannotCloseHalfOpen() {
        GatewayCircuitBreaker.Admission stale = allow(KEY);
        openCircuit();
        now += 10_000;

        GatewayCircuitBreaker.Admission probe = breaker.tryAcquire(KEY);
        assertNotNull(probe);
        assertEquals("HALF_OPEN", breaker.stateOf(KEY));

        breaker.recordSuccess(stale);
        assertEquals("HALF_OPEN", breaker.stateOf(KEY),
                "非探针结果不能结束半开");

        breaker.recordSuccess(probe);
        probe.release();
        assertEquals("CLOSED", breaker.stateOf(KEY), "只有当前探针成功才恢复");
    }

    /** 半开期间旁路失败也不能覆盖探针窗口 */
    @Test
    void nonProbeFailureDuringHalfOpenIsIgnored() {
        openCircuit();
        now += 10_000;
        GatewayCircuitBreaker.Admission probe = breaker.tryAcquire(KEY);
        assertNotNull(probe);

        breaker.recordFailure(KEY);
        assertEquals("HALF_OPEN", breaker.stateOf(KEY),
                "旁路失败不能把正在验证的半开直接重新熔断");

        breaker.recordSuccess(probe);
        probe.release();
        assertEquals("CLOSED", breaker.stateOf(KEY));
    }

    /** 探针卡住超过期限：自动重新熔断，不能把所有请求永久挡住 */
    @Test
    void probeTimeoutReopensCircuitAndStaleReleaseDoesNotUnlockNextProbe() {
        openCircuit();
        now += 10_000;
        GatewayCircuitBreaker.Admission first = breaker.tryAcquire(KEY);
        assertNotNull(first);
        assertEquals("HALF_OPEN", breaker.stateOf(KEY));

        now += 5_000;
        assertNull(breaker.tryAcquire(KEY), "探针超时后本次请求仍应快速拒绝");
        assertEquals("OPEN", breaker.stateOf(KEY), "超时探针按失败处理，重新冷却");

        // 旧探针迟到的 release 不能把新探针的名额放出来
        first.release();
        now += 10_000;
        GatewayCircuitBreaker.Admission second = breaker.tryAcquire(KEY);
        assertNotNull(second);
        assertNull(breaker.tryAcquire(KEY), "新探针在途时仍只允许一个请求");
        assertEquals("HALF_OPEN", breaker.stateOf(KEY));
    }

    /** 旧探针的迟到结果属于上一代，不能结束新的半开窗口 */
    @Test
    void staleProbeResultCannotCloseNewHalfOpenWindow() {
        openCircuit();
        now += 10_000;
        GatewayCircuitBreaker.Admission first = breaker.tryAcquire(KEY);
        assertNotNull(first);

        now += 5_000;
        assertNull(breaker.tryAcquire(KEY));
        now += 10_000;
        GatewayCircuitBreaker.Admission second = breaker.tryAcquire(KEY);
        assertNotNull(second);

        breaker.recordSuccess(first);
        assertEquals("HALF_OPEN", breaker.stateOf(KEY),
                "上一代探针的结果不能替当前探针判定");

        breaker.recordSuccess(second);
        second.release();
        assertEquals("CLOSED", breaker.stateOf(KEY));
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
