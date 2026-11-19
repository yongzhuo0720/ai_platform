package com.aiproject.ai.service.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关 Prometheus 指标单测。用 SimpleMeterRegistry（内存实现）验证 tag 与数值，
 * 不依赖 Spring 容器，也不依赖真实的 Prometheus 端点。
 */
class GatewayMetricsTest {

    @Test
    void noRegistry_isNoOp_andDoesNotThrow() {
        GatewayMetrics metrics = new GatewayMetrics();
        assertFalse(metrics.isEnabled());
        assertDoesNotThrow(() -> metrics.record("chat", "m", "success", true, 100, 50, 20));
        assertDoesNotThrow(() -> metrics.recordFailure("chat", "m", "failed"));
    }

    @Test
    void record_incrementsCallsWithExpectedTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        metrics.setRegistry(registry);
        assertTrue(metrics.isEnabled());

        metrics.record("chat", "deepseek-flash", "success", true, 120L, 80L, 25L);

        Counter calls = registry.find("aiproject.gateway.calls.total")
                .tag("module", "chat")
                .tag("model", "deepseek-flash")
                .tag("status", "success")
                .counter();
        assertNotNull(calls, "调用计数应带 module/model/status 三个 tag");
        assertEquals(1.0, calls.count(), 0.0001);
    }

    @Test
    void record_repeatedCallsAccumulateCostInMilliCents() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        metrics.setRegistry(registry);

        // 两次各 25 毫分、30 毫分，且都标记为已定价
        metrics.record("chat", "m", "success", true, 100, 50, 25L);
        metrics.record("chat", "m", "success", true, 100, 50, 30L);

        Counter cost = registry.find("aiproject.gateway.cost.millicents")
                .tag("priced", "true").counter();
        assertNotNull(cost);
        assertEquals(55.0, cost.count(), 0.0001, "成本按毫分累加，不做二次换算");
    }

    @Test
    void record_unpricedModelTaggedSeparately() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        metrics.setRegistry(registry);

        // costCents 为 null 的调用：priced=false、成本记 0，不与真实免费混淆
        metrics.record("chat", "free-model", "success", false, 100, 50, 0L);

        Counter cost = registry.find("aiproject.gateway.cost.millicents")
                .tag("priced", "false").counter();
        assertNotNull(cost);
        assertEquals(0.0, cost.count(), 0.0001);
    }

    @Test
    void recordFailure_onlyCountsFailureStatuses_throughDedicatedMetric() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        metrics.setRegistry(registry);

        metrics.recordFailure("models", "m", "failed");
        metrics.recordFailure("models", "m", "blocked");

        Counter failed = registry.find("aiproject.gateway.failures.total")
                .tag("status", "failed").counter();
        Counter blocked = registry.find("aiproject.gateway.failures.total")
                .tag("status", "blocked").counter();
        assertNotNull(failed);
        assertNotNull(blocked);
        assertEquals(1.0, failed.count(), 0.0001);
        assertEquals(1.0, blocked.count(), 0.0001);
    }

    @Test
    void record_latencyAndTokensRecordedAsDistributions() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        metrics.setRegistry(registry);

        metrics.record("chat", "m", "success", true, 150L, 90L, 10L);

        DistributionSummary latency = registry.find("aiproject.gateway.latency.milliseconds")
                .tag("module", "chat").summary();
        DistributionSummary tokens = registry.find("aiproject.gateway.tokens")
                .tag("model", "m").summary();
        assertNotNull(latency);
        assertNotNull(tokens);
        assertEquals(150.0, latency.totalAmount(), 0.0001);
        assertEquals(90.0, tokens.totalAmount(), 0.0001);
    }

    @Test
    void record_nullTagsBecomeUnknown_notThrow() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics();
        metrics.setRegistry(registry);

        assertDoesNotThrow(() -> metrics.record(null, null, null, true, 0, 0, 0));

        Counter calls = registry.find("aiproject.gateway.calls.total")
                .tag("module", "unknown").tag("model", "unknown").tag("status", "unknown").counter();
        assertNotNull(calls, "空 tag 应归一为 unknown，避免指标缺失或异常");
    }
}
