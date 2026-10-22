package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.holde.AiUserQuotaHolder;
import com.heneng.hnsp.common.redis.service.RedisServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 网关限流与每日 Token 配额。
 * <p>
 * Redis 不可用时选择 fail-open：宁可暂时失去治理能力，也不能让整个 AI 入口不可用；
 * 失败会打 warn 日志，便于监控发现。
 */
@Slf4j
@Component
public class AiGatewayGuard {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    RedisServer redisServer;

    @Autowired
    RedisTemplate<String, Object> redisTemplate;

    @Autowired
    AiGatewayProperties properties;

    @Autowired
    AiUserQuotaHolder quotaHolder;

    public Decision check(String userId, String module) {
        Decision rate = checkRateLimit(userId);
        if (!rate.isAllowed()) {
            return rate;
        }
        return checkQuota(userId, module);
    }

    private Decision checkRateLimit(String userId) {
        int limit = properties.getRateLimitPerMinute();
        if (limit <= 0) {
            return Decision.allow();
        }
        String key = "ai:gateway:rate:" + userId + ":" + MINUTE.format(LocalDateTime.now());
        try {
            Long count = redisServer.increment(key, 1);
            redisTemplate.expire(key, 120, TimeUnit.SECONDS);
            if (count != null && count > limit) {
                return Decision.block("rate_limited",
                        "请求过于频繁，每分钟最多 " + limit + " 次，请稍后再试");
            }
        } catch (Exception e) {
            log.warn("[AiGateway] 限流检查失败，已放行, uid={}: {}", userId, e.getMessage());
        }
        return Decision.allow();
    }

    private Decision checkQuota(String userId, String module) {
        if (!tokenQuotaApplies(module)) {
            return Decision.allow();
        }
        long quota;
        try {
            quota = quotaHolder.effectiveQuota(userId);
        } catch (Exception e) {
            log.warn("[AiGateway] 读取用户配额失败，已放行, uid={}: {}", userId, e.getMessage());
            return Decision.allow();
        }
        if (quota <= 0) {
            return Decision.allow();
        }
        long used = usedTokens(userId);
        if (used >= quota) {
            return Decision.block("quota_exceeded",
                    "今日 AI 额度已用完（已用 " + used + " / " + quota + " tokens），请明天再试或联系管理员");
        }
        return Decision.allow();
    }

    public long usedTokens(String userId) {
        try {
            Object value = redisServer.get(todayTokenKey(userId));
            if (value == null) {
                return 0L;
            }
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    public void addTokens(String userId, long tokens) {
        if (userId == null || tokens <= 0) {
            return;
        }
        String key = todayTokenKey(userId);
        try {
            redisServer.increment(key, tokens);
            redisTemplate.expire(key, 2, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("[AiGateway] 记录 Token 用量失败, uid={}: {}", userId, e.getMessage());
        }
    }

    private String todayTokenKey(String userId) {
        return "ai:gateway:tokens:" + userId + ":" + DAY.format(LocalDate.now());
    }

    private boolean tokenQuotaApplies(String module) {
        return AiGatewayConfigResolver.MODULE_CHAT.equals(module)
                || AiGatewayConfigResolver.MODULE_KNOWLEDGE_CHAT.equals(module)
                || ModelGatewayResolver.MODULE_MODELS.equals(module);
    }

    @Getter
    public static class Decision {
        private final boolean allowed;
        private final String code;
        private final String message;

        private Decision(boolean allowed, String code, String message) {
            this.allowed = allowed;
            this.code = code;
            this.message = message;
        }

        public static Decision allow() {
            return new Decision(true, null, null);
        }

        public static Decision block(String code, String message) {
            return new Decision(false, code, message);
        }
    }
}
