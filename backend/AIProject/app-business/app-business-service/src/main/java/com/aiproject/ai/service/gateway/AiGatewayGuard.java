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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /**
     * 预扣台账：预扣成功后把「占用了哪个用户池、哪个部门池、多少 Token」写进一个短期 Hash。
     * 进程崩溃或容器被强杀时，finally 里的 settle 不会执行，预扣额度会一直占到当天结束；
     * 台账让定时任务能发现并退回这些「无人认领」的预扣。
     *
     * <p>StringRedisTemplate 是可选依赖：它缺失时只退化为无台账（与旧行为一致），
     * 不影响正常请求的预扣与结算。</p>
     */
    @Autowired(required = false)
    StringRedisTemplate stringRedisTemplate;

    private static final String LEDGER_INDEX_KEY = "ai:gateway:reservation:index";
    private static final String LEDGER_KEY_PREFIX = "ai:gateway:reservation:";
    private static final long LEDGER_TTL_SECONDS = 2 * 60 * 60L;
    /** 超过该时长仍未结算，视为请求线程已经不在了；留足 5 分钟读超时的余量 */
    private static final long LEDGER_STALE_MS = 30 * 60 * 1000L;

    private static final DefaultRedisScript<Long> SETTLE_LEDGER_SCRIPT = new DefaultRedisScript<Long>(
            "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if not status then return -1 end "
                    + "if status ~= 'active' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'status', 'settled') "
                    + "redis.call('ZREM', KEYS[2], KEYS[1]) "
                    + "redis.call('EXPIRE', KEYS[1], 60) return 1",
            Long.class);

    private static final DefaultRedisScript<Long> REAP_LEDGER_SCRIPT = new DefaultRedisScript<Long>(
            "local status = redis.call('HGET', KEYS[1], 'status') "
                    + "if not status then return -1 end "
                    + "if status ~= 'active' then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'status', 'reaped') "
                    + "redis.call('ZREM', KEYS[2], KEYS[1]) "
                    + "redis.call('EXPIRE', KEYS[1], 60) return 1",
            Long.class);

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
            String ledgerKey = recordLedger(userId, unitOwner, tokens, userKey, unitKey);
            return Reservation.active(userId, unitOwner, tokens, userKey, unitKey, ledgerKey);
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
     * 写预扣台账。失败只告警、不阻断请求：台账是崩溃恢复的增强，
     * 正常路径的「finally 里 settle」仍然是主通道，不能因为台账写不进去就把用户挡在门外。
     */
    private String recordLedger(String userId, String unitOwner, long tokens,
                                String userKey, String unitKey) {
        if (stringRedisTemplate == null) {
            return null;
        }
        String ledgerKey = LEDGER_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "");
        try {
            long now = System.currentTimeMillis();
            Map<String, String> fields = new LinkedHashMap<String, String>();
            fields.put("userId", userId);
            fields.put("unitOwner", unitOwner == null ? "" : unitOwner);
            fields.put("tokens", String.valueOf(tokens));
            fields.put("userKey", userKey);
            fields.put("unitKey", unitKey == null ? "" : unitKey);
            fields.put("status", "active");
            fields.put("createdAt", String.valueOf(now));
            stringRedisTemplate.opsForHash().putAll(ledgerKey, fields);
            stringRedisTemplate.expire(ledgerKey, LEDGER_TTL_SECONDS, TimeUnit.SECONDS);
            stringRedisTemplate.opsForZSet().add(LEDGER_INDEX_KEY, ledgerKey, (double) now);
            return ledgerKey;
        } catch (Exception e) {
            log.warn("[AiGateway] 写预扣台账失败，本次请求无崩溃恢复保护, uid={}: {}",
                    userId, e.getMessage());
            return null;
        }
    }

    /**
     * 请求结束后按真实 usage 结算预扣：多退少补；usage 缺失（失败/中断）时传 0 全额释放。
     * <p>
     * 同一 Reservation 只结算一次；未预扣（配额不限或 Redis 降级）时是空操作，
     * 调用方仍按原逻辑走 {@link #addTokens}。
     * <p>
     * 两个容易踩的细节：
     * <ul>
     *   <li>用<b>预扣时记下的 key</b>，而不是结算时重新按日期拼 key。跨零点完成的长请求
     *       如果在结算时重新拼 key，会去改第二天的计数，第一天的预扣永远退不回来；</li>
     *   <li>有台账时先原子认领（active → settled）再调计数。认领失败说明已被别的进程
     *       结算或回收，不能再调一次，否则会重复退款。</li>
     * </ul>
     */
    public void settle(Reservation reservation, long actualTokens) {
        if (reservation == null || !reservation.isActive() || !reservation.trySettle()) {
            return;
        }
        Boolean claimed = claimLedger(reservation.getLedgerKey(), SETTLE_LEDGER_SCRIPT);
        if (Boolean.FALSE.equals(claimed)) {
            log.warn("[AiGateway] 预扣台账已被结算或回收，跳过重复调整, uid={}",
                    reservation.getUserId());
            return;
        }
        long delta = actualTokens - reservation.getTokens();
        try {
            adjustTokens(userKeyOf(reservation), delta);
            if (reservation.getUnitOwner() != null) {
                adjustTokens(unitKeyOf(reservation), delta);
            }
        } catch (Exception e) {
            log.warn("[AiGateway] 结算预扣配额失败, uid={}: {}", reservation.getUserId(), e.getMessage());
        }
    }

    private String userKeyOf(Reservation reservation) {
        return reservation.getUserKey() == null
                ? todayTokenKey(reservation.getUserId()) : reservation.getUserKey();
    }

    private String unitKeyOf(Reservation reservation) {
        return reservation.getUnitKey() == null
                ? todayTokenKey(reservation.getUnitOwner()) : reservation.getUnitKey();
    }

    /**
     * 原子认领台账。
     *
     * @return true 表示本次调用成功认领；false 表示已被结算/回收；null 表示没有台账或 Redis 不可用
     */
    private Boolean claimLedger(String ledgerKey, DefaultRedisScript<Long> script) {
        if (ledgerKey == null || stringRedisTemplate == null) {
            return null;
        }
        try {
            Long claimed = stringRedisTemplate.execute(script,
                    Arrays.asList(ledgerKey, LEDGER_INDEX_KEY));
            if (claimed == null || claimed < 0) {
                return null;
            }
            return claimed > 0;
        } catch (Exception e) {
            log.warn("[AiGateway] 认领预扣台账失败，按无台账结算: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 回收超时未结算的预扣。
     *
     * <p>典型场景：容器发布时被 kill -9，请求线程的 finally 没机会执行。
     * 没有回收时这笔预扣会占到当天 key 过期（2 天），用户额度看起来「凭空少了一截」。
     * 只退不扣：认领成功后按台账里的 tokens 反向调整，重复执行由 Lua 里的状态判断兜住。</p>
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 2 * 60 * 1000L)
    public void reapExpiredReservations() {
        if (stringRedisTemplate == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - LEDGER_STALE_MS;
        Set<String> ledgerKeys;
        try {
            ledgerKeys = stringRedisTemplate.opsForZSet()
                    .rangeByScore(LEDGER_INDEX_KEY, 0D, (double) cutoff, 0, 100);
        } catch (Exception e) {
            log.warn("[AiGateway] 查询超时预扣台账失败: {}", e.getMessage());
            return;
        }
        if (ledgerKeys == null || ledgerKeys.isEmpty()) {
            return;
        }
        for (String ledgerKey : ledgerKeys) {
            try {
                Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(ledgerKey);
                if (fields == null || fields.isEmpty()) {
                    // Hash 已过期但索引还在：清掉索引即可，没有可退的额度
                    stringRedisTemplate.opsForZSet().remove(LEDGER_INDEX_KEY, ledgerKey);
                    continue;
                }
                Long claimed = stringRedisTemplate.execute(REAP_LEDGER_SCRIPT,
                        Arrays.asList(ledgerKey, LEDGER_INDEX_KEY));
                if (claimed == null || claimed <= 0) {
                    continue;
                }
                long tokens = parseLong(fields.get("tokens"));
                String userKey = asString(fields.get("userKey"));
                String unitKey = asString(fields.get("unitKey"));
                if (tokens <= 0 || userKey == null) {
                    continue;
                }
                adjustTokens(userKey, -tokens);
                if (unitKey != null && !unitKey.isEmpty()) {
                    adjustTokens(unitKey, -tokens);
                }
                log.warn("[AiGateway] 已回收超时未结算的预扣: uid={}, tokens={}",
                        fields.get("userId"), tokens);
            } catch (Exception e) {
                log.warn("[AiGateway] 回收预扣台账失败, key={}: {}", ledgerKey, e.getMessage());
            }
        }
    }

    private static long parseLong(Object value) {
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
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
        private static final Reservation NONE =
                new Reservation(false, false, null, null, 0L, null, null, null, null, null);

        private final boolean active;
        private final boolean blocked;
        private final String blockedCode;
        private final String blockedMessage;
        private final long tokens;
        private final String userId;
        /** 部门共享池的配额行 userId（"unit:gid"），无部门时为 null */
        private final String unitOwner;
        /** 预扣时实际写入的用户池 key；跨零点结算必须用它，而不是按结算时间重新拼 */
        private final String userKey;
        /** 预扣时实际写入的部门池 key；未预扣部门池时为 null */
        private final String unitKey;
        /** 预扣台账 key；未启用台账时为 null */
        private final String ledgerKey;
        private final java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private Reservation(boolean active, boolean blocked, String blockedCode, String blockedMessage,
                            long tokens, String userId, String unitOwner,
                            String userKey, String unitKey, String ledgerKey) {
            this.active = active;
            this.blocked = blocked;
            this.blockedCode = blockedCode;
            this.blockedMessage = blockedMessage;
            this.tokens = tokens;
            this.userId = userId;
            this.unitOwner = unitOwner;
            this.userKey = userKey;
            this.unitKey = unitKey;
            this.ledgerKey = ledgerKey;
        }

        public static Reservation none() {
            return NONE;
        }

        public static Reservation blocked(String message) {
            return blocked("quota_exceeded", message);
        }

        /** 超额之外的拒绝（如 Redis / 配额表不可用），必须带自己的错误码，前端与看板才能区分 */
        public static Reservation blocked(String code, String message) {
            return new Reservation(false, true, code, message, 0L, null, null, null, null, null);
        }

        /** 兼容旧调用：没有 key 与台账，settle 时按当天 key 兜底 */
        public static Reservation active(String userId, String unitOwner, long tokens) {
            return active(userId, unitOwner, tokens, null, null, null);
        }

        public static Reservation active(String userId, String unitOwner, long tokens,
                                         String userKey, String unitKey, String ledgerKey) {
            return new Reservation(true, false, null, null, tokens, userId, unitOwner,
                    userKey, unitKey, ledgerKey);
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

    private static String todayTokenKey(String userId) {
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
