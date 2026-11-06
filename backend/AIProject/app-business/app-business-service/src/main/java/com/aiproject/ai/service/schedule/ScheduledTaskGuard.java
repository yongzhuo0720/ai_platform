package com.aiproject.ai.service.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务的多实例互斥锁。
 *
 * <p>单机部署时 {@code @Scheduled} 天然只跑一次；一旦部署两个实例，周报生成与
 * 僵死任务回收会被每个实例各跑一遍。周报有数据库唯一约束兜底，但回收任务会做
 * 大量无意义的 CAS 扫描，且两个实例同时清理同一批任务容易放大日志与锁竞争。</p>
 *
 * <p>用 Redis {@code setIfAbsent} 做「带过期的占位」：拿到锁的实例执行，其他实例
 * 直接跳过；执行结束用 owner token + Lua 脚本比较后删除，避免 TTL 过期后把别的
 * 实例刚拿到的锁误删。</p>
 *
 * <p>Redis 不可用时选择 fail-open（照常执行）：这几个任务本身都是幂等的——周报有
 * 唯一约束、僵死回收用状态 CAS——单实例部署下因为 Redis 故障就不生成周报，比偶尔
 * 重复执行更糟。多实例 + Redis 故障属于降级场景，日志会明确记录。</p>
 */
@Slf4j
@Component
public class ScheduledTaskGuard {

    private static final String KEY_PREFIX = "ai:sched:";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 可选依赖：没有 Redis 时退化为单机执行，不会让定时任务停摆 */
    @Autowired(required = false)
    StringRedisTemplate stringRedisTemplate;

    /**
     * 抢到锁才执行任务。
     *
     * @param task       任务名，同一任务的所有实例必须使用相同值
     * @param ttlSeconds 锁的过期时间；应大于任务正常耗时、小于调度间隔，避免任务挂死永久占锁
     * @param body       任务体
     * @return true 表示本次执行了任务；false 表示没抢到锁或参数非法
     */
    public boolean run(String task, long ttlSeconds, Runnable body) {
        if (task == null || task.trim().isEmpty() || body == null) {
            return false;
        }
        String redisKey = KEY_PREFIX + task.trim();
        String owner = UUID.randomUUID().toString().replace("-", "");
        boolean locked = false;

        if (stringRedisTemplate != null) {
            try {
                Boolean acquired = stringRedisTemplate.opsForValue()
                        .setIfAbsent(redisKey, owner, ttlSeconds, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(acquired)) {
                    locked = true;
                    log.debug("[Scheduled] 已获得锁: {}", task);
                } else if (Boolean.FALSE.equals(acquired)) {
                    log.info("[Scheduled] 另一实例正在执行，跳过本次: {}", task);
                    return false;
                } else {
                    log.warn("[Scheduled] 获取锁返回空，按单机模式执行: {}", task);
                }
            } catch (Exception e) {
                // Redis 故障不阻塞任务：这些任务在数据库层是幂等的
                log.warn("[Scheduled] 获取锁失败，按单机模式执行, task={}: {}", task, e.getMessage());
            }
        }

        try {
            body.run();
            return true;
        } finally {
            if (locked) {
                release(redisKey, owner, task);
                log.debug("[Scheduled] 已释放锁: {}", task);
            }
        }
    }

    private void release(String redisKey, String owner, String task) {
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT,
                    Collections.singletonList(redisKey), owner);
        } catch (Exception e) {
            // 删除失败只会让锁等到 TTL 自然过期，不影响任务结果
            log.warn("[Scheduled] 释放锁失败, task={}: {}", task, e.getMessage());
        }
    }
}
