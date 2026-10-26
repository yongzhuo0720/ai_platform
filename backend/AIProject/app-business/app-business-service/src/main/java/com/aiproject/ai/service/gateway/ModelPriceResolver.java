package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.mapper.ModelsConfigBeanMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型单价解析：把 token 用量折算成钱。
 *
 * <p>为什么要单独一个组件：治理看板原本只能回答「用了多少 token」，
 * 而运营真正关心的是「花了多少钱」。不同模型的价差可以到几十倍
 * （同样 100 万 token，用便宜模型和用旗舰模型完全不是一回事），
 * 只看 token 数会严重误判成本结构。</p>
 *
 * <p><b>缓存策略</b>：单价是低频变更的配置数据，但每次 AI 调用都要查一次。
 * 这里用带 TTL 的内存缓存（默认 5 分钟），而不是：
 * <ul>
 *   <li>不缓存——每次调用多一次数据库查询，AI 调用本身就要几百毫秒到几秒，
 *       多一跳 DB 不值得；</li>
 *   <li>永久缓存——管理员改了价格要等重启才生效，体验差且容易和实际账单对不上。</li>
 * </ul>
 * TTL 取 5 分钟是折衷：价格改动的生效延迟可以接受，同时把数据库压力降到
 * 每分钟一次。</p>
 */
@Slf4j
@Component
public class ModelPriceResolver {

    /** 缓存有效期。价格是低频变更配置，5 分钟延迟可接受 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    @Autowired
    ModelsConfigBeanMapper<ModelsConfigBean> modelsConfigBeanMapper;

    /** modelName -> 单价。用 volatile 保证多线程下的可见性 */
    private volatile Map<String, Price> cache;
    private volatile long cacheAt;

    /** 模型单价。单位：分 / 百万 tokens */
    @Getter
    public static class Price {
        private final long inputPerMillion;
        private final long outputPerMillion;

        public Price(long inputPerMillion, long outputPerMillion) {
            this.inputPerMillion = inputPerMillion;
            this.outputPerMillion = outputPerMillion;
        }

        /** 是否配置了有效价格（至少一项非空） */
        public boolean isConfigured() {
            return inputPerMillion > 0 || outputPerMillion > 0;
        }

        /**
         * 折算成本，单位「毫分」（1 分 = 1000 毫分）。
         *
         * <p>用毫分而不是分：单次对话往往只有几百 token，按
         * 分/百万 的单价折算不足 1 分，直接用分会把小调用全部
         * 下取整成 0，看板永远显示 0。毫分保留到小数点后三位，
         * 累加也不失真。</p>
         *
         * <p>全程 long 整数运算，避免浮点误差在大量累加后放大。</p>
         */
        public long costCents(long promptTokens, long completionTokens) {
            long inputCost = promptTokens * inputPerMillion / 1_000L;
            long outputCost = completionTokens * outputPerMillion / 1_000L;
            return inputCost + outputCost;
        }
    }

    /**
     * 查询模型单价。
     *
     * @param modelName 上游模型名（审计日志里的 modelName）
     * @return 单价；未配置该模型或未配置价格时返回 {@code null}
     */
    public Price resolve(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return null;
        }
        String key = modelName.trim();
        Map<String, Price> snapshot = cache;
        if (snapshot != null && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) {
            return snapshot.get(key);
        }
        return refresh().get(key);
    }

    /** 重新加载全部单价 */
    private synchronized Map<String, Price> refresh() {
        // double-check：并发刷新时只让一个线程真正查库
        if (cache != null && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) {
            return cache;
        }
        Map<String, Price> fresh = new HashMap<String, Price>();
        try {
            List<ModelsConfigBean> all = modelsConfigBeanMapper.select(null, new ModelsConfigBean());
            if (all != null) {
                for (ModelsConfigBean item : all) {
                    Long in = item.getPriceInputPerMillion();
                    Long out = item.getPriceOutputPerMillion();
                    if (in == null && out == null) {
                        continue;
                    }
                    Price price = new Price(in == null ? 0L : in, out == null ? 0L : out);
                    if (!price.isConfigured()) {
                        continue;
                    }
                    // 同时按展示名与上游模型名建索引：
                    // 审计日志里的 modelName 可能来自任一侧
                    if (item.getName() != null && !item.getName().trim().isEmpty()) {
                        fresh.put(item.getName().trim(), price);
                    }
                    if (item.getUpstreamModel() != null && !item.getUpstreamModel().trim().isEmpty()) {
                        fresh.put(item.getUpstreamModel().trim(), price);
                    }
                }
            }
        } catch (Exception e) {
            // 价格查不到不该影响 AI 调用本身：保留旧缓存，只记日志
            log.warn("[ModelPrice] 加载模型单价失败，沿用旧缓存: {}", e.getMessage());
            if (cache != null) {
                return cache;
            }
        }
        cache = fresh;
        cacheAt = System.currentTimeMillis();
        log.info("[ModelPrice] 单价缓存已加载: {} 个模型, keys={}", fresh.size(), fresh.keySet());
        return fresh;
    }
}
