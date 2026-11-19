package com.aiproject.ai.service.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AI 网关的 Prometheus 业务指标（对标 LiteLLM 的 per-key/per-model spend & rate 指标）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>只依赖 Micrometer 的 {@link MeterRegistry}，由 Spring Boot Actuator 自动装配；
 *       {@code micrometer-registry-prometheus} 把指标暴露成 {@code /actuator/prometheus}。</li>
 *   <li>指标以「module + model + status + priced」为 tag，可回答"哪个模型调用最多/失败最多/花了多少钱"，
 *       但 tag 不含 uid / requestId 等高基数值，避免指标基数爆炸。</li>
 *   <li>{@link #registry} 可能为 null（如单测里 new 出来没注入），此时全部指标退化为 no-op，
 *       绝不影响调用记账主流程——可观测性永远不能反过来拖垮业务。</li>
 * </ul>
 */
@Component
public class GatewayMetrics {

    private static final String PREFIX = "aiproject.gateway";

    @Autowired(required = false)
    private MeterRegistry registry;

    /** 供单测注入 registry（生产由 Spring 自动装配） */
    public void setRegistry(MeterRegistry registry) {
        this.registry = registry;
    }

    public boolean isEnabled() {
        return registry != null;
    }

    /**
     * 记录一次 AI 调用。所有指标更新都做 null 保护；即使某个 tag 为空也不抛异常。
     *
     * @param module   调用模块（chat / knowledgeChat / file / models）
     * @param model    上游模型名（审计日志里的 modelName）
     * @param status   成功/失败/拦截/中断
     * @param priced   该模型是否配置了单价（区分"0 成本"与"未定价"）
     * @param latencyMs 本次耗时（毫秒）
     * @param tokens   本次总 token
     * @param costMilliCents 本次成本（毫分）
     */
    public void record(String module, String model, String status, boolean priced,
                       long latencyMs, long tokens, long costMilliCents) {
        if (registry == null) {
            return;
        }
        String m = safe(module);
        String modelName = safe(model);
        String s = safe(status);

        Counter.builder(PREFIX + ".calls.total")
                .description("AI 网关调用次数")
                .tag("module", m).tag("model", modelName).tag("status", s)
                .register(registry).increment();

        DistributionSummary.builder(PREFIX + ".latency.milliseconds")
                .description("AI 网关调用耗时（毫秒）")
                .tag("module", m).tag("status", s)
                .register(registry).record(Math.max(0, latencyMs));

        DistributionSummary.builder(PREFIX + ".tokens")
                .description("AI 网关每次调用 token 数")
                .tag("module", m).tag("model", modelName)
                .register(registry).record(Math.max(0, tokens));

        // 成本单独一个指标，且用 priced tag 区分：未定价模型的 0 不等于真的免费
        Counter.builder(PREFIX + ".cost.millicents")
                .description("AI 网关成本累计（毫分，1分=1000毫分）")
                .tag("module", m).tag("model", modelName).tag("priced", String.valueOf(priced))
                .register(registry).increment(Math.max(0, costMilliCents));
    }

    /** 失败调用单独计数，便于配 Prometheus 告警规则（如 5 分钟失败率 > 10%） */
    public void recordFailure(String module, String model, String status) {
        if (registry == null) {
            return;
        }
        Counter.builder(PREFIX + ".failures.total")
                .description("AI 网关失败/拦截次数")
                .tag("module", safe(module)).tag("model", safe(model)).tag("status", safe(status))
                .register(registry).increment();
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }
}
