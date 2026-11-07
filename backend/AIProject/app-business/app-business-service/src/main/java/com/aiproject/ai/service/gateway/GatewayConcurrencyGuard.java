package com.aiproject.ai.service.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 网关并发闸门：限制「每个模块同时进行中的上游请求数」。
 * <p>
 * 配额与限流管的是「谁可以发」，管不住「同时有多少个请求正占着 Web 线程
 * 等上游」。上游一旦变慢，请求会在线程池里越堆越多，最后连登录这类
 * 无关接口都拿不到线程。这里在调用上游之前做一次非阻塞的许可检查：
 * 拿不到就立刻 429，不排队、不占用线程、也不产生上游费用。
 * <p>
 * 按模块分别计数，避免长对话把文件上传/知识库检索一起饿死。
 * 许可在请求进入 {@code doProxy} 后获取、在 finally 中释放，
 * 覆盖「读上游 + 写浏览器」的完整生命周期。
 * <p>
 * 计数存放在 Redis 的有序集合里（member 是每次请求的唯一 id，score 是租约到期时间），
 * 因此多实例部署时上限是<b>全局限额</b>，而不是「每实例限额 × 实例数」。
 * 每个请求只持有自己的 member：进程被强杀时没人来 ZREM，但下一次获取会用
 * ZREMRANGEBYSCORE 清掉过期 member，不会把并发名额永久占死。
 * Redis 不可用时退回本机信号量，至少保住单实例的线程保护能力。
 */
@Slf4j
@Component
public class GatewayConcurrencyGuard {

    /** 超限错误码：前端可据此提示「忙，稍后重试」，区别于额度用尽的 quota_exceeded */
    public static final String CODE_BUSY = "gateway_busy";

    private static final String DEFAULT_MODULE = "default";
    private static final String KEY_PREFIX = "ai:gateway:concurrent:";
    private static final long MIN_LEASE_MS = 60 * 1000L;
    private static final long UNBOUNDED_LEASE_MS = 30 * 60 * 1000L;

    /**
     * 获取许可：先清过期租约，再判断数量，最后写入自己的租约。
     * 五步在同一次脚本里完成，避免「查完再写」的窗口把并发放大。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<Long>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) "
                    + "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end "
                    + "redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4]) "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[5]) return 1",
            Long.class);

    /** 释放许可；集合空了顺手删 key，避免模块闲置后留下空集合 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<Long>(
            "local removed = redis.call('ZREM', KEYS[1], ARGV[1]) "
                    + "if removed > 0 and redis.call('ZCARD', KEYS[1]) == 0 then "
                    + "redis.call('DEL', KEYS[1]) end "
                    + "return removed",
            Long.class);

    private final AiGatewayProperties properties;

    /**
     * 可选依赖：缺失或 Redis 操作失败时退回进程内信号量。
     * 网关治理主链路（限流）本身已 fail-closed，这里不必为了并发保护把请求挡在门外。
     */
    @Autowired(required = false)
    StringRedisTemplate stringRedisTemplate;

    /**
     * 每个模块一个信号量。数量在首次使用时按配置创建；
     * 修改配置需要重启才生效（运行期热更新这里没有做，避免许可数中途变化时出现负数）。
     */
    private final ConcurrentHashMap<String, Semaphore> semaphores =
            new ConcurrentHashMap<String, Semaphore>();

    public GatewayConcurrencyGuard(AiGatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试占用一个并发许可。
     *
     * @return 许可对象；超限返回 {@code null}。未限制并发时返回一个无需释放的空许可
     */
    public Lease tryAcquire(String module) {
        int limit = properties.getMaxConcurrentPerModule();
        if (limit <= 0) {
            return Lease.UNLIMITED;
        }
        String key = keyOf(module);
        if (stringRedisTemplate != null) {
            String memberId = UUID.randomUUID().toString().replace("-", "");
            Boolean acquired = redisAcquire(key, limit, memberId);
            if (acquired != null) {
                return acquired ? Lease.redis(stringRedisTemplate, key, memberId) : null;
            }
        }
        return localAcquire(key, limit);
    }

    /**
     * @return true 已占用；false 达到全局上限；null Redis 不可用（调用方退回本机信号量）
     */
    private Boolean redisAcquire(String key, int limit, String memberId) {
        long leaseMs = leaseMillis();
        long now = System.currentTimeMillis();
        try {
            Long result = stringRedisTemplate.execute(ACQUIRE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(now), String.valueOf(limit),
                    String.valueOf(now + leaseMs), memberId, String.valueOf(leaseMs));
            if (result == null) {
                return null;
            }
            return result > 0;
        } catch (Exception e) {
            log.warn("[AiGateway] 并发闸门 Redis 不可用，退回本机限流, key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 许可租约时长。默认覆盖单次请求的硬上限（流式总时长），再加一分钟余量；
     * 关闭流式上限时用一个固定值兜底，宁可多占一会儿，也不要在请求进行中丢名额。
     */
    private long leaseMillis() {
        long base = properties.getMaxStreamMs();
        if (base <= 0) {
            return UNBOUNDED_LEASE_MS;
        }
        return Math.max(MIN_LEASE_MS, base + 60 * 1000L);
    }

    private Lease localAcquire(String key, int limit) {
        Semaphore semaphore = semaphoreFor(key, limit);
        return semaphore.tryAcquire() ? Lease.local(semaphore) : null;
    }

    private Semaphore semaphoreFor(String key, int limit) {
        Semaphore existing = semaphores.get(key);
        if (existing != null) {
            return existing;
        }
        return semaphores.computeIfAbsent(key, k -> new Semaphore(limit));
    }

    private static String keyOf(String module) {
        return KEY_PREFIX + (module == null ? DEFAULT_MODULE : module);
    }

    /** 许可句柄：release 必须只调一次，重复释放会把并发上限悄悄放大 */
    public static final class Lease {
        private static final Lease UNLIMITED = new Lease(null, null, null, null);

        private final Semaphore semaphore;
        private final StringRedisTemplate redisTemplate;
        private final String key;
        private final String memberId;

        private Lease(Semaphore semaphore, StringRedisTemplate redisTemplate,
                      String key, String memberId) {
            this.semaphore = semaphore;
            this.redisTemplate = redisTemplate;
            this.key = key;
            this.memberId = memberId;
        }

        private static Lease local(Semaphore semaphore) {
            return new Lease(semaphore, null, null, null);
        }

        private static Lease redis(StringRedisTemplate redisTemplate, String key, String memberId) {
            return new Lease(null, redisTemplate, key, memberId);
        }

        public void release() {
            if (redisTemplate != null) {
                try {
                    redisTemplate.execute(RELEASE_SCRIPT,
                            Collections.singletonList(key), memberId);
                } catch (Exception e) {
                    // 释放失败只会多占一个名额，租约到期后会被下一次获取清理
                    log.warn("[AiGateway] 释放并发租约失败, key={}: {}", key, e.getMessage());
                }
                return;
            }
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }
}