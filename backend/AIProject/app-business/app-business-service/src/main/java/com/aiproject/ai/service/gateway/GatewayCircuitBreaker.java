package com.aiproject.ai.service.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 按上游隔离的熔断器。
 *
 * <p>并发闸门解决的是「我们自己别被拖垮」，熔断解决的是「上游已经挂了，
 * 别再每个请求都去等一次超时」。没有熔断时，上游不可用会让每个请求都卡满
 * 连接/读取超时，32 个并发许可很快被占满，后续请求全部 429；
 * 有了熔断，连续失败达到阈值后直接 503，请求快速失败、线程立刻归还。</p>
 *
 * <p><b>状态机：</b>CLOSED → 连续失败达到阈值 → OPEN（冷却期内拒绝）→
 * 冷却结束转 HALF_OPEN（只放一个探针）→ 探针成功回 CLOSED，失败回 OPEN。</p>
 *
 * <p><b>计数口径：</b>只统计连接失败、读超时、上游 5xx；客户端断开、上游 4xx
 * 不计入。把「用户自己取消」当成上游故障，会在用户频繁点停止时误开熔断。</p>
 *
 * <p><b>单实例语义：</b>状态存在进程内，多实例部署时各实例独立判断；
 * 这是有意的取舍——熔断的目标是保护本实例的线程与延迟，
 * 而不是做全局精确计数。</p>
 */
@Slf4j
@Component
public class GatewayCircuitBreaker {

    /** 熔断拒绝的错误码，前端按 code 分支 */
    public static final String CODE_OPEN = "circuit_open";

    @Autowired
    AiGatewayProperties properties;

    /** 单测可替换的时钟，避免 sleep */
    LongSupplier clock = System::currentTimeMillis;

    private final ConcurrentHashMap<String, Breaker> breakers =
            new ConcurrentHashMap<String, Breaker>();

    /**
     * 尝试获取一次上游调用许可。
     *
     * @return null 表示熔断中，调用方应直接 503；否则必须在使用后调用
     *         {@link Admission#release()}（CLOSED 状态下是无操作）
     */
    public Admission tryAcquire(String key) {
        Breaker breaker = breakerFor(key);
        synchronized (breaker) {
            if (properties.getCircuitFailureThreshold() <= 0) {
                return Admission.allowed(breaker, false);
            }
            long now = clock.getAsLong();
            if (breaker.state == State.OPEN) {
                if (now < breaker.openUntil) {
                    return null;
                }
                breaker.state = State.HALF_OPEN;
                breaker.probesInFlight.set(0);
                log.info("[AiGateway] 熔断冷却结束，进入半开, key={}", key);
            }
            if (breaker.state == State.HALF_OPEN) {
                if (breaker.probesInFlight.get() >= 1) {
                    return null;
                }
                breaker.probesInFlight.incrementAndGet();
                return Admission.allowed(breaker, true);
            }
            return Admission.allowed(breaker, false);
        }
    }

    /** 上游正常响应后调用：半开探针成功则恢复，连续失败计数清零 */
    public void recordSuccess(String key) {
        // 按需建状态：调用方可能只记录结果而没有先 tryAcquire（如测试或未来的健康探测）
        Breaker breaker = breakerFor(key);
        synchronized (breaker) {
            if (breaker.state == State.HALF_OPEN) {
                breaker.state = State.CLOSED;
                breaker.consecutiveFailures = 0;
                breaker.openUntil = 0L;
                log.info("[AiGateway] 半开探针成功，熔断恢复, key={}", key);
            } else if (breaker.state == State.CLOSED) {
                breaker.consecutiveFailures = 0;
            }
            // OPEN 期间旧请求的成功不直接恢复：上游是否真的好了，
            // 由冷却结束后的半开探针确认，避免一次侥幸成功掩盖持续故障。
        }
    }

    /** 上游不可用类失败后调用：累加失败计数，达到阈值则熔断 */
    public void recordFailure(String key) {
        if (properties.getCircuitFailureThreshold() <= 0) {
            return;
        }
        Breaker breaker = breakerFor(key);
        synchronized (breaker) {
            if (breaker.state == State.OPEN) {
                return;
            }
            if (breaker.state == State.HALF_OPEN) {
                breaker.state = State.OPEN;
                breaker.openUntil = clock.getAsLong() + openMs();
                log.warn("[AiGateway] 半开探针失败，重新熔断, key={}", key);
                return;
            }
            breaker.consecutiveFailures++;
            if (breaker.consecutiveFailures >= properties.getCircuitFailureThreshold()) {
                breaker.state = State.OPEN;
                breaker.openUntil = clock.getAsLong() + openMs();
                log.warn("[AiGateway] 上游连续失败 {} 次，熔断 {} ms, key={}",
                        breaker.consecutiveFailures, openMs(), key);
            }
        }
    }

    /** 熔断时建议客户端等待的秒数，至少 1 秒 */
    public int retryAfterSeconds(String key) {
        Breaker breaker = breakers.get(safeKey(key));
        if (breaker == null) {
            return 1;
        }
        synchronized (breaker) {
            long now = clock.getAsLong();
            if (breaker.state == State.OPEN && breaker.openUntil > now) {
                return seconds(breaker.openUntil - now);
            }
            return seconds(openMs());
        }
    }

    /** 供测试与健康面板读取当前状态：CLOSED / OPEN / HALF_OPEN */
    public String stateOf(String key) {
        Breaker breaker = breakers.get(safeKey(key));
        if (breaker == null) {
            return State.CLOSED.name();
        }
        synchronized (breaker) {
            return breaker.state.name();
        }
    }

    private long openMs() {
        return Math.max(1L, properties.getCircuitOpenMs());
    }

    private static int seconds(long millis) {
        // 向上取整：剩余 200ms 也应提示「1 秒后重试」，不能提示 0
        return (int) Math.max(1L, (millis + 999L) / 1000L);
    }

    private Breaker breakerFor(String key) {
        String safe = safeKey(key);
        Breaker existing = breakers.get(safe);
        if (existing != null) {
            return existing;
        }
        return breakers.computeIfAbsent(safe, k -> new Breaker());
    }

    private static String safeKey(String key) {
        return key == null || key.isEmpty() ? "default" : key;
    }

    private enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private static final class Breaker {
        private State state = State.CLOSED;
        private int consecutiveFailures;
        private long openUntil;
        private final AtomicInteger probesInFlight = new AtomicInteger();
    }

    /** 一次上游调用许可；半开探针必须 release，否则不会再放第二个探针 */
    public static final class Admission {
        private final Breaker breaker;
        private final boolean probe;

        private Admission(Breaker breaker, boolean probe) {
            this.breaker = breaker;
            this.probe = probe;
        }

        static Admission allowed(Breaker breaker, boolean probe) {
            return new Admission(breaker, probe);
        }

        public void release() {
            if (probe) {
                breaker.probesInFlight.decrementAndGet();
            }
        }
    }
}
