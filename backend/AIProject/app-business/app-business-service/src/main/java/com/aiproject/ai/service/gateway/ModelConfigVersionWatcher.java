package com.aiproject.ai.service.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 模型配置变更的跨实例同步。
 *
 * <p><b>为什么需要它。</b>ModelGatewayResolver / EmbeddingClient / ModelPriceResolver /
 * RerankResolver 对 modelsconfigbean 各自持有 5 分钟 TTL 的进程内快照，后台保存后靠
 * {@link ModelConfigChangedEvent} 立即失效。但 Spring 事件只在当前进程广播：多实例部署时
 * A 实例新增或停用模型，B/C 最长 5 分钟仍按旧配置工作——表现为「新模型在别的实例报未配置」
 * 或「密钥已轮换但旧密钥还在打上游」。</p>
 *
 * <p><b>做法。</b>写入方先调用 {@link #bumpVersion()} 递增 Redis 版本号，再发本地事件；
 * 各实例每 2 秒读一次版本号，发现变化就广播同一个本地事件，复用既有监听逻辑。
 * 首次读到的版本只建立基线，不触发失效。版本键缺失按 0 处理，这样 Redis 被清空后
 * 的第一次写入也能被感知（多一次无副作用的失效而已）。</p>
 *
 * <p><b>故障取舍。</b>Redis 不可用时只打状态切换日志，各组件退化为原有的 5 分钟 TTL：
 * 一致性变弱，但对话链路不受影响，符合「治理组件故障不阻断业务」的约定。</p>
 */
@Slf4j
@Component
public class ModelConfigVersionWatcher {

    static final String VERSION_KEY = "ai:gateway:modelconfig:version";

    private static final long POLL_INTERVAL_MS = 2000L;

    /** 可选依赖：没有 Redis 时只保留本实例的事件失效，不改变单机行为 */
    @Autowired(required = false)
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    /** null 表示尚未建立基线（冷启动或版本键刚被清空） */
    private Long lastVersion;

    /** 只在「正常→故障」「故障→正常」时各打一条日志，避免 2 秒一条刷屏 */
    private boolean redisFailed;

    /** 配置写入成功后调用：让其他实例在下一次轮询时失效本地快照 */
    public void bumpVersion() {
        StringRedisTemplate template = stringRedisTemplate;
        if (template == null) {
            return;
        }
        try {
            Long version = template.opsForValue().increment(VERSION_KEY);
            log.debug("[AiGateway] 模型配置版本号已递增: {}", version);
        } catch (Exception e) {
            log.warn("[AiGateway] 配置版本号递增失败，其他实例将退化为 5 分钟 TTL 后生效: {}",
                    e.getMessage());
        }
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS, initialDelay = POLL_INTERVAL_MS)
    public void pollVersion() {
        StringRedisTemplate template = stringRedisTemplate;
        if (template == null) {
            return;
        }
        long version;
        try {
            version = parseVersion(template.opsForValue().get(VERSION_KEY));
            if (redisFailed) {
                log.info("[AiGateway] 模型配置版本号读取已恢复, version={}", version);
                redisFailed = false;
            }
        } catch (Exception e) {
            if (!redisFailed) {
                log.warn("[AiGateway] 读取模型配置版本号失败，配置同步暂降级为本地 TTL: {}",
                        e.getMessage());
                redisFailed = true;
            }
            return;
        }

        Long previous = lastVersion;
        lastVersion = version;
        if (previous == null || previous.longValue() == version) {
            return;
        }
        log.info("[AiGateway] 检测到模型配置变更, version {} -> {}，失效本机快照", previous, version);
        try {
            eventPublisher.publishEvent(new ModelConfigChangedEvent());
        } catch (Exception e) {
            // 监听方只做缓存失效，这里兜底避免异常打断调度线程
            log.warn("[AiGateway] 模型配置变更事件发布失败: {}", e.getMessage());
        }
    }

    /** 版本键缺失按 0 处理：清空 Redis 后的首次写入也能触发其他实例失效 */
    static long parseVersion(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}