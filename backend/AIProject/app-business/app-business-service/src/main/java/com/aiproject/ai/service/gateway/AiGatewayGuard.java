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
 * 治理依赖（Redis / 配额表）不可用时选择 <b>fail-closed</b>：限流与配额是花钱的控制点，
 * 「读不到就当没限制」等于把成本控制换成一次 Redis 抖动就能绕过。
 * 具体到本项目：登录态（Sa-Token）本身就存 Redis，Redis 挂了请求连 Controller 都进不来，
 * 所以「为保可用性而放行」保护的东西其实已经不存在了，放行只会静默失去治理能力。
 * <p>
 * 拒绝时统一用 {@link #CODE_GUARD_UNAVAILABLE}，与「超额」区分开：
 * 前者运维要去看 Redis/配额表，后者是正常的用户额度问题。
 */
@Slf4j
@Component
public class AiGatewayGuard {

    /** 治理组件不可用（Redis 读写失败、配额表读不到）——不是用户超额 */
    public static final String CODE_GUARD_UNAVAILABLE = "guard_unavailable";

    private static final String GUARD_UNAVAILABLE_MSG =
            "AI 网关暂时不可用（限流与配额依赖的服务未就绪），请稍后重试或联系管理员";

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
            log.error("[AiGateway] 限流计数失败，按不可用拒绝, uid={}", userId, e);
            return Decision.block(CODE_GUARD_UNAVAILABLE, GUARD_UNAVAILABLE_MSG);
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
            log.error("[AiGateway] 读取用户配额失败，按不可用拒绝, uid={}", userId, e);
            return Decision.block(CODE_GUARD_UNAVAILABLE, GUARD_UNAVAILABLE_MSG);
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
                log.error("[AiGateway] 读取部门配额失败，按不可用拒绝, uid={}", userId, e);
                return Decision.block(CODE_GUARD_UNAVAILABLE, GUARD_UNAVAILABLE_MSG);
            }
        }
        return Decision.allow();
    }

    /**
     * 请求开始前预扣 Token：先自增再比较，把「检查额度」与「完成后计费」之间的
     * 并发窗口压缩到一次 Redis 自增。
     * <p>
     * 用户池与部门池都可能生效：两个池都要预扣，任一超额就回滚并拒绝。
     * 读不到配额或 Redis 自增没生效时<b>拒绝本次请求</b>（{@link #CODE_GUARD_UNAVAILABLE}），
     * 而不是退回「不预扣、完成后累计」——后者等于治理依赖一旦异常就无限制放行。
     */
    public Reservation reserve(String userId, String module) {
        if (userId == null || userId.isEmpty() || !tokenQuotaApplies(module)) {
            return Reservation.none();
        }
        long tokens = properties.getQuotaReserveTokens();
        if (tokens <= 0) {
            return Reservation.none();
        }
        String userKey = null;
        String unitKey = null;
        boolean userReserved = false;
        boolean unitReserved = false;
        try {
            long userQuota;
            try {
                userQuota = quotaHolder.effectiveQuota(userId);
            } catch (Exception e) {
                log.error("[AiGateway] 读取用户配额失败，拒绝本次调用, uid={}", userId, e);
                return Reservation.blocked(CODE_GUARD_UNAVAILABLE, GUARD_UNAVAILABLE_MSG);
            }
            String gid = gidOf(userId);
            String unitOwner = gid == null || gid.trim().isEmpty() ? null : UNIT_PREFIX + gid.trim();
            long unitQuota = 0L;
            if (unitOwner != null) {
                AiUserQuotaBean unit = quotaHolder.find(unitOwner);
                unitQuota = unit == null || unit.getDailyTokenQuota() == null
                        ? 0L : unit.getDailyTokenQuota();
            }
            if (userQuota <= 0 && unitQuota <= 0) {
                return Reservation.none();
            }

            userKey = todayTokenKey(userId);
            Long userUsed = redisServer.increment(userKey, tokens);
            if (userUsed == null) {
                // Redis 未返回计数说明自增没生效：此时既不能假装已占用，也不能当作没超额
                log.error("[AiGateway] 预扣自增未生效（Redis 无返回），拒绝本次调用, uid={}", userId);
                return Reservation.blocked(CODE_GUARD_UNAVAILABLE, GUARD_UNAVAILABLE_MSG);
            }
            userReserved = true;
            redisTemplate.expire(userKey, 2, TimeUnit.DAYS);
            if (userQuota > 0 && userUsed > userQuota) {
                redisServer.decrement(userKey, tokens);
                userReserved = false;
                long used = userUsed - tokens;
                return Reservation.blocked("今日 AI 额度已用完（已用 " + used + " / "
                        + userQuota + " tokens），请明天再试或联系管理员");
            }

            if (unitQuota > 0) {
                unitKey = todayTokenKey(unitOwner);
                Long unitUsed = redisServer.increment(unitKey, tokens);
                if (unitUsed == null) {
                    redisServer.decrement(userKey, tokens);
                    userReserved = false;
                    log.error("[AiGateway] 部门池预扣自增未生效（Redis 无返回），拒绝本次调用, uid={}", userId);
                    return Reservation.blocked(CODE_GUARD_UNAVAILABLE, GUARD_UNAVAILABLE_MSG);
                }
                unitReserved = true;
                redisTemplate.expire(unitKey, 2, TimeUnit.DAYS);
                if (unitUsed > unitQuota) {
                    redisServer.decrement(unitKey, tokens);
                    unitReserved = false;
                    redisServer.decrement(userKey, tokens);
                    userReserved = false;
                    long used = unitUsed - tokens;
                    return Reservation.blocked("所在部门今日 AI 额度已用完（" + used + " / "
                            + unitQuota + " tokens），请联系管理员");
                }
            }
            userReserved = false; // 预扣转交给 Reservation，由 settle 负责结算
            unitReserved = false;
            return Reservation.active(userId, unitOwner, tokens);
        } catch (Exception e) {
            if (userReserved && userKey != null) {
                try {
                    redisServer.decrement(userKey, tokens);
                } catch (Exception ignored) {
                    // 回滚失败只能依赖次日 key 过期
                }
            }
            if (unitReserved && unitKey != null) {
                try {
                    redisServer.decrement(unitKey, tokens);
                } catch (Exception ignored) {
                    // 回滚失败只能依赖次日 key 过期
                }
            }
            log.error("[AiGateway] 预扣配额失败，拒绝本次调用, uid={}", userId, e);
            return Reservation.blocked(CODE_GUARD_UNAVAILABLE, GUARD_UNAVAILABLE_MSG);
        }
    }

    /**
     * 请求结束后按真实 usage 结算预扣：多退少补；usage 缺失（失败/中断）时传 0 全额释放。
     * <p>
     * 同一 Reservation 只结算一次；未预扣（配额不限或 Redis 降级）时是空操作，
     * 调用方仍按原逻辑走 {@link #addTokens}。
     */
    public void settle(Reservation reservation, long actualTokens) {
        if (reservation == null || !reservation.isActive() || !reservation.trySettle()) {
            return;
        }
        long delta = actualTokens - reservation.getTokens();
        try {
            adjustTokens(todayTokenKey(reservation.getUserId()), delta);
            if (reservation.getUnitOwner() != null) {
                adjustTokens(todayTokenKey(reservation.getUnitOwner()), delta);
            }
        } catch (Exception e) {
            log.warn("[AiGateway] 结算预扣配额失败, uid={}: {}", reservation.getUserId(), e.getMessage());
        }
    }

    private void adjustTokens(String key, long delta) {
        if (delta > 0) {
            redisServer.increment(key, delta);
            redisTemplate.expire(key, 2, TimeUnit.DAYS);
        } else if (delta < 0) {
            redisServer.decrement(key, -delta);
            redisTemplate.expire(key, 2, TimeUnit.DAYS);
        }
    }

    /** 预扣结果：active 表示已占用额度；blocked 表示额度不足或治理依赖不可用，调用方应直接拒绝 */
    @Getter
    public static class Reservation {
        private static final Reservation NONE = new Reservation(false, false, null, null, 0L, null, null);

        private final boolean active;
        private final boolean blocked;
        private final String blockedCode;
        private final String blockedMessage;
        private final long tokens;
        private final String userId;
        /** 部门共享池的配额行 userId（"unit:gid"），无部门时为 null */
        private final String unitOwner;
        private final java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private Reservation(boolean active, boolean blocked, String blockedCode, String blockedMessage,
                            long tokens, String userId, String unitOwner) {
            this.active = active;
            this.blocked = blocked;
            this.blockedCode = blockedCode;
            this.blockedMessage = blockedMessage;
            this.tokens = tokens;
            this.userId = userId;
            this.unitOwner = unitOwner;
        }

        public static Reservation none() {
            return NONE;
        }

        public static Reservation blocked(String message) {
            return blocked("quota_exceeded", message);
        }

        /** 超额之外的拒绝（如 Redis / 配额表不可用），必须带自己的错误码，前端与看板才能区分 */
        public static Reservation blocked(String code, String message) {
            return new Reservation(false, true, code, message, 0L, null, null);
        }

        public static Reservation active(String userId, String unitOwner, long tokens) {
            return new Reservation(true, false, null, null, tokens, userId, unitOwner);
        }

        boolean trySettle() {
            return settled.compareAndSet(false, true);
        }

        /** 未显式给码时按 quota_exceeded 处理，前端按 429 提示 */
        public String getBlockedCode() {
            return blockedCode == null ? "quota_exceeded" : blockedCode;
        }
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

    public void addTokens(String userId, String module, long tokens) {
        if (userId == null || tokens <= 0) {
            return;
        }
        // 仅对配额生效的模块计数，与 checkQuota 口径一致；
        // 否则 file 等非配额模块的 token 会污染「我的用量」统计。
        if (!tokenQuotaApplies(module)) {
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
