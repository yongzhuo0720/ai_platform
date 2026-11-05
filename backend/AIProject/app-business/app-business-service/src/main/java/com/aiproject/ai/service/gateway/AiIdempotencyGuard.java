package com.aiproject.ai.service.gateway;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 写请求幂等：同一个 {@code Idempotency-Key} 在有效期内只允许执行一次。
 *
 * <p>为什么需要它：客户端超时重试时，网关可能已经收到并完成了上一次请求。
 * 对话请求按次消耗 Token，重试一次就是重复计费；文件与数据集创建则会产生重复数据。
 * 仅靠「客户端不要重试」约束不住网络层自动重试，必须在服务端留一个短期占位。</p>
 *
 * <p>实现是最小够用的「占位」而不是「结果缓存」：第一次请求成功后键保留 10 分钟，
 * 期间重复请求返回 409，客户端应把 409 理解为「上次已经处理过」。
 * 不缓存并重放响应体，是为了不把大响应（尤其是 SSE）再存一份。</p>
 *
 * <p>调用方未带幂等键时行为完全不变；Redis 不可用时带键请求返回 503，
 * 让调用方明确知道「这次无法保证幂等」，而不是悄悄放过。</p>
 */
@Slf4j
@Component
public class AiIdempotencyGuard {

    public static final String HEADER = "Idempotency-Key";
    public static final String FALLBACK_HEADER = "X-Idempotency-Key";
    public static final String CODE_CONFLICT = "idempotency_conflict";
    public static final String CODE_UNAVAILABLE = "idempotency_unavailable";
    public static final String CODE_INVALID = "invalid_idempotency_key";

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final long TTL_MINUTES = 10L;

    /** 可选依赖：没有 StringRedisTemplate 时，带键请求按「无法保证幂等」拒绝 */
    @Autowired(required = false)
    StringRedisTemplate stringRedisTemplate;

    /**
     * 取得本次请求的幂等占位。
     *
     * @param userId 登录用户；幂等键按用户隔离，避免不同用户互相顶掉
     * @param module 网关模块；同一个键在不同模块下代表不同操作
     */
    public Acquisition acquire(String userId, String module, HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return Acquisition.none();
        }
        String key = request.getHeader(HEADER);
        if (key == null) {
            key = request.getHeader(FALLBACK_HEADER);
        }
        if (key == null) {
            return Acquisition.none();
        }
        key = key.trim();
        if (key.isEmpty()) {
            return Acquisition.none();
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            return Acquisition.rejected(400, CODE_INVALID,
                    "Idempotency-Key 必须是 8~128 位字母、数字或 . _ : -");
        }
        if (userId == null || userId.isEmpty()) {
            return Acquisition.rejected(401, "unauthorized", "请先登录");
        }
        if (stringRedisTemplate == null) {
            return Acquisition.rejected(503, CODE_UNAVAILABLE,
                    "幂等服务暂不可用，请稍后重试；或不带 Idempotency-Key 发起请求");
        }

        String redisKey = "ai:gateway:idem:" + userId + ":" + module + ":" + key;
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", TTL_MINUTES, TimeUnit.MINUTES);
            if (Boolean.TRUE.equals(acquired)) {
                return Acquisition.lease(new Lease(redisKey));
            }
            if (Boolean.FALSE.equals(acquired)) {
                return Acquisition.rejected(409, CODE_CONFLICT,
                        "相同请求已处理或正在处理中，请勿重复提交");
            }
            return Acquisition.rejected(503, CODE_UNAVAILABLE,
                    "幂等服务暂不可用，请稍后重试");
        } catch (Exception e) {
            log.warn("[AiGateway] 幂等占位失败, uid={}, module={}: {}", userId, module, e.getMessage());
            return Acquisition.rejected(503, CODE_UNAVAILABLE,
                    "幂等服务暂不可用，请稍后重试");
        }
    }

    /** 请求成功（含按预扣计费的中断）后调用：键保留到 TTL 结束，重复请求会被 409 挡住 */
    public void complete(Lease lease) {
        if (lease == null || stringRedisTemplate == null) {
            return;
        }
        try {
            stringRedisTemplate.expire(lease.getRedisKey(), TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("[AiGateway] 延长幂等键失败: {}", e.getMessage());
        }
    }

    /** 请求未产生实际效果时调用：立即释放，允许客户端用同一个键重试 */
    public void release(Lease lease) {
        if (lease == null || stringRedisTemplate == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(lease.getRedisKey());
        } catch (Exception e) {
            log.warn("[AiGateway] 释放幂等键失败: {}", e.getMessage());
        }
    }

    @Getter
    public static class Lease {
        private final String redisKey;

        private Lease(String redisKey) {
            this.redisKey = redisKey;
        }
    }

    @Getter
    public static class Acquisition {
        private final boolean rejected;
        private final int httpStatus;
        private final String code;
        private final String message;
        private final Lease lease;

        private Acquisition(boolean rejected, int httpStatus, String code, String message, Lease lease) {
            this.rejected = rejected;
            this.httpStatus = httpStatus;
            this.code = code;
            this.message = message;
            this.lease = lease;
        }

        static Acquisition none() {
            return new Acquisition(false, 0, null, null, null);
        }

        static Acquisition lease(Lease lease) {
            return new Acquisition(false, 0, null, null, lease);
        }

        static Acquisition rejected(int httpStatus, String code, String message) {
            return new Acquisition(true, httpStatus, code, message, null);
        }
    }
}
