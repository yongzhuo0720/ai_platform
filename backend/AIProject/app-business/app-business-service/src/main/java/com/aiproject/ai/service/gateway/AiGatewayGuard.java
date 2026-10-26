package com.aiproject.ai.service.gateway;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.AiUserQuotaBean;
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

    /** 部门配额需要按用户查所属部门（gid） */
    @Autowired
    MyUserBaseDbHolder userBaseDbHolder;

    /** uid -> gid 进程内缓存（部门归属低频变更，10 分钟足够新） */
    private final java.util.concurrent.ConcurrentHashMap<String, String[]> gidCache = new java.util.concurrent.ConcurrentHashMap<String, String[]>();
    private static final long GID_CACHE_MS = 10 * 60 * 1000L;

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
        long userQuota;
        try {
            userQuota = quotaHolder.effectiveQuota(userId);
        } catch (Exception e) {
            log.warn("[AiGateway] 读取用户配额失败，已放行, uid={}: {}", userId, e.getMessage());
            return Decision.allow();
        }
        if (userQuota > 0) {
            long used = usedTokens(userId);
            if (used >= userQuota) {
                return Decision.block("quota_exceeded",
                        "今日 AI 额度已用完（已用 " + used + " / " + userQuota + " tokens），请明天再试或联系管理员");
            }
        }

        // 部门共享池：配额行 userId 存 "unit:部门gid"，部门内所有人共用一个 Token 池
        String gid = gidOf(userId);
        if (gid != null && !gid.trim().isEmpty()) {
            try {
                AiUserQuotaBean unitQuota = quotaHolder.find(UNIT_PREFIX + gid.trim());
                long unitLimit = unitQuota == null || unitQuota.getDailyTokenQuota() == null
                        ? 0L : unitQuota.getDailyTokenQuota();
                if (unitLimit > 0) {
                    long unitUsed = usedTokens(UNIT_PREFIX + gid.trim());
                    if (unitUsed >= unitLimit) {
                        return Decision.block("quota_exceeded",
                                "所在部门今日 AI 额度已用完（" + unitUsed + " / " + unitLimit + " tokens），请联系管理员");
                    }
                }
            } catch (Exception e) {
                log.warn("[AiGateway] 读取部门配额失败，已放行, uid={}: {}", userId, e.getMessage());
            }
        }
        return Decision.allow();
    }

    /** 部门配额行的 userId 前缀 */

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

    public static final String UNIT_PREFIX = "unit:";

    public void addTokens(String userId, long tokens) {
        if (userId == null || tokens <= 0) {
            return;
        }
        try {
            String key = todayTokenKey(userId);
            redisServer.increment(key, tokens);
            redisTemplate.expire(key, 2, TimeUnit.DAYS);
            // 部门共享池同步累计：配额检查按双池各自比对
            String gid = gidOf(userId);
            if (gid != null && !gid.trim().isEmpty()) {
                String unitKey = todayTokenKey(UNIT_PREFIX + gid.trim());
                redisServer.increment(unitKey, tokens);
                redisTemplate.expire(unitKey, 2, TimeUnit.DAYS);
            }
        } catch (Exception e) {
            log.warn("[AiGateway] 记录 Token 用量失败, uid={}: {}", userId, e.getMessage());
        }
    }

    /** 查用户所属部门 gid，带 10 分钟进程内缓存；查不到返回 null（无部门配额概念） */
    private String gidOf(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        String[] cached = gidCache.get(userId);
        long now = System.currentTimeMillis();
        if (cached != null && now - Long.parseLong(cached[0]) < GID_CACHE_MS) {
            // 空串占位表示「已查过且无部门」
            return cached[1].isEmpty() ? null : cached[1];
        }
        String gid = null;
        try {
            MyUserBaseDb user = userBaseDbHolder.queryUserByUid(userId);
            if (user != null) {
                gid = user.getGid();
            }
        } catch (Exception e) {
            log.warn("[AiGateway] 查询用户部门失败: {}", e.getMessage());
        }
        gidCache.put(userId, new String[]{String.valueOf(now), gid == null ? "" : gid});
        return gid;
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
