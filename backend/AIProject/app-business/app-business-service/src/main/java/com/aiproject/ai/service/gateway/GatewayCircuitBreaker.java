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
 * 冷却结束转 HALF_OPEN（只放一个探针）→ 探针成功回 CLOSED，失败回 OPEN。
 * 探针在 {@code circuitProbeTimeoutMs} 内没有结论时按失败处理，重新进入冷却，
 * 避免半开状态把所有请求永久挡在门外。</p>
 *
 * <p><b>归属：</b>只有真正拿到探针许可的那次调用才能结束半开。熔断打开前已经
 * 在途的旧请求、以及不经过 {@link #tryAcquire} 的旁路调用，它们的结果在
 * HALF_OPEN 期间一律忽略——否则一个迟到的成功会把还没验证的上游直接放回来。</p>
 *
 * <p><b>计数口径：</b>只统计连接失败、读超时、上游 5xx；客户端断开、上游 4xx
 * 不计入。把「用户自己取消」当成上游故障，会在用户频繁点停止时误开熔断；
 * 4xx 说明请求本身有问题，既不证明上游健康，也不证明它挂了，按中性处理。</p>
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
                return Admission.allowed(breaker, false, 0L);
            }
            long now = clock.getAsLong();
            if (breaker.state == State.OPEN) {
                if (now < breaker.openUntil) {
                    return null;
                }
                enterHalfOpen(breaker, now, key);
            }
            if (breaker.state == State.HALF_OPEN) {
                if (now >= breaker.probeDeadline) {
                    open(breaker, now);
                    log.warn("[AiGateway] 半开探针超时未返回，重新熔断, key={}", key);
                    return null;
                }
                if (breaker.probesInFlight.get() >= 1) {
                    return null;
                }
                breaker.probesInFlight.incrementAndGet();
                return Admission.allowed(breaker, true, breaker.probeEpoch);
            }
            return Admission.allowed(breaker, false, 0L);
        }
    }

    /**
     * 上游正常响应后调用（无归属）。
     *
     * <p>只对 CLOSED 状态有效；HALF_OPEN 必须由探针自己的结果来结束，
     * 避免任何一次旁路成功都替探针「签字」。</p>
     */
    public void recordSuccess(String key) {
        recordSuccess(breakerFor(key), null);
    }

    /** 上游正常响应后调用；半开探针成功则恢复，连续失败计数清零 */
    public void recordSuccess(Admission admission) {
        if (admission != null) {
            recordSuccess(admission.breaker, admission);
        }
    }

    /** 上游不可用类失败后调用（无归属），只对 CLOSED 状态计数 */
    public void recordFailure(String key) {
        recordFailure(breakerFor(key), null);
    }

    /** 上游不可用类失败后调用；半开探针失败则重新熔断 */
    public void recordFailure(Admission admission) {
        if (admission != null) {
            recordFailure(admission.breaker, admission);
        }
    }

    private void recordSuccess(Breaker breaker, Admission admission) {
        synchronized (breaker) {
            if (admission != null) {
                admission.settled = true;
            }
            if (breaker.state == State.HALF_OPEN) {
                if (isCurrentProbe(breaker, admission)) {
                    close(breaker);
                    log.info("[AiGateway] 半开探针成功，熔断恢复, key={}", breaker.name);
                }
                // 非探针或旧探针的迟到成功不能替当前探针做决定
                return;
            }
            if (breaker.state == State.CLOSED) {
                breaker.consecutiveFailures = 0;
            }
            // OPEN 期间旧请求的成功不直接恢复：上游是否真的好了，
            // 由冷却结束后的半开探针确认，避免一次侥幸成功掩盖持续故障。
        }
    }

    private void recordFailure(Breaker breaker, Admission admission) {
        if (properties.getCircuitFailureThreshold() <= 0) {
            return;
        }
        synchronized (breaker) {
            if (admission != null) {
                admission.settled = true;
            }
            if (breaker.state == State.HALF_OPEN) {
                if (isCurrentProbe(breaker, admission)) {
                    open(breaker, clock.getAsLong());
                    log.warn("[AiGateway] 半开探针失败，重新熔断, key={}", breaker.name);
                }
                return;
            }
            if (breaker.state == State.OPEN) {
                return;
            }
            breaker.consecutiveFailures++;
            if (breaker.consecutiveFailures >= properties.getCircuitFailureThreshold()) {
                open(breaker, clock.getAsLong());
                log.warn("[AiGateway] 上游连续失败 {} 次，熔断 {} ms, key={}",
                        breaker.consecutiveFailures, openMs(), breaker.name);
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

    private void enterHalfOpen(Breaker breaker, long now, String key) {
        breaker.state = State.HALF_OPEN;
        breaker.probesInFlight.set(0);
        breaker.probeEpoch++;
        breaker.probeDeadline = now + probeTimeoutMs();
        log.info("[AiGateway] 熔断冷却结束，进入半开, key={}", key);
    }

    private void open(Breaker breaker, long now) {
        breaker.state = State.OPEN;
        breaker.openUntil = now + openMs();
        breaker.probeDeadline = 0L;
        breaker.probesInFlight.set(0);
    }

    private void close(Breaker breaker) {
        breaker.state = State.CLOSED;
        breaker.consecutiveFailures = 0;
        breaker.openUntil = 0L;
        breaker.probeDeadline = 0L;
    }

    private static boolean isCurrentProbe(Breaker breaker, Admission admission) {
        return admission != null
                && admission.probe
                && admission.probeEpoch == breaker.probeEpoch;
    }

    private long openMs() {
        return Math.max(1L, properties.getCircuitOpenMs());
    }

    private long probeTimeoutMs() {
        return Math.max(1L, properties.getCircuitProbeTimeoutMs());
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
        return breakers.computeIfAbsent(safe, k -> new Breaker(safe));
    }

    private static String safeKey(String key) {
        return key == null || key.isEmpty() ? "default" : key;
    }

    private enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private static final class Breaker {
        private final String name;
        private State state = State.CLOSED;
        private int consecutiveFailures;
        private long openUntil;
        private long probeDeadline;
        private long probeEpoch;
        private final AtomicInteger probesInFlight = new AtomicInteger();

        private Breaker(String name) {
            this.name = name;
        }
    }

    /**
     * 一次上游调用许可；半开探针必须 release，否则不会再放第二个探针。
     * release 与结果记录是两条路径：4xx 这类中性结果只 release 不判定。
     */
    public static final class Admission {
        private final Breaker breaker;
        private final boolean probe;
        private final long probeEpoch;
        private boolean settled;

        private Admission(Breaker breaker, boolean probe, long probeEpoch) {
            this.breaker = breaker;
            this.probe = probe;
            this.probeEpoch = probeEpoch;
        }

        static Admission allowed(Breaker breaker, boolean probe, long probeEpoch) {
            return new Admission(breaker, probe, probeEpoch);
        }

        public void release() {
            if (!probe) {
                return;
            }
            synchronized (breaker) {
                // 已经被结果记录结算过，或探针已超时换代：不能再动新探针的计数
                if (settled || breaker.probeEpoch != probeEpoch) {
                    return;
                }
                settled = true;
                if (breaker.state == State.HALF_OPEN) {
                    breaker.probesInFlight.decrementAndGet();
                }
            }
        }
    }
}
