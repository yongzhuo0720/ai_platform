package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型直连路由：按 modelsconfigbean 的 iid 解析出上游 OpenAI 兼容服务。
 * <p>
 * 一行配置 = 一个可直连的模型：protocol=openai 且 baseUrl / apiKey / upstreamModel 齐全。
 * 密钥只在服务端使用；浏览器只能拿到「是否已配置」的状态。
 */
@Slf4j
@Component
public class ModelGatewayResolver {

    public static final String MODULE_MODELS = "models";
    public static final String MODULE_NAME = "模型直连";
    public static final String PROTOCOL_OPENAI = "openai";

    /** 配置快照 TTL：后台写入会发 ModelConfigChangedEvent 立即失效，TTL 只兜底直改库 */
    private static final long CONFIG_CACHE_TTL_MS = 5 * 60 * 1000L;

    @Autowired
    ModelsConfigBeanHolder modelsConfigBeanHolder;

    private volatile Map<Long, ResolvedModel> modelsCache;
    private volatile long cacheAt;
    private volatile boolean cacheLoaded;

    /**
     * @return 解析结果；模型不存在、未配置直连或配置不全时返回 null
     */
    public ResolvedModel resolve(long iid) {
        Map<Long, ResolvedModel> models = allModels();
        return models == null ? null : models.get(iid);
    }

    /**
     * 一次读全表建快照：直连对话每次请求都要按 iid 取配置，
     * 逐条查库在热门路径上纯属浪费；配置表规模很小。
     */
    private Map<Long, ResolvedModel> allModels() {
        long now = System.currentTimeMillis();
        if (cacheLoaded && now - cacheAt < CONFIG_CACHE_TTL_MS) {
            return modelsCache;
        }
        try {
            return refresh(now);
        } catch (Exception e) {
            // 读库失败回退上一次快照：一次抖动不应该让所有对话不可用
            log.warn("[AiGateway] 读取直连模型配置失败: {}", e.getMessage());
            return modelsCache;
        }
    }

    private synchronized Map<Long, ResolvedModel> refresh(long now) {
        // 双重检查：并发首访时只让一个线程查库
        if (cacheLoaded && now - cacheAt < CONFIG_CACHE_TTL_MS) {
            return modelsCache;
        }
        Map<Long, ResolvedModel> loaded = loadFromDb();
        modelsCache = loaded;
        cacheAt = now;
        cacheLoaded = true;
        return loaded;
    }

    private Map<Long, ResolvedModel> loadFromDb() {
        Map<Long, ResolvedModel> models = new HashMap<Long, ResolvedModel>();
        List<ModelsConfigBean> list = modelsConfigBeanHolder.queryList(new ModelsConfigBean(), null);
        if (list == null) {
            return models;
        }
        for (ModelsConfigBean item : list) {
            ResolvedModel resolved = build(item);
            if (resolved != null && item.getIid() != null) {
                models.put(item.getIid(), resolved);
            }
        }
        return models;
    }

    /** 后台保存模型配置后立即丢快照，不必等 TTL */
    @EventListener
    public void onModelConfigChanged(ModelConfigChangedEvent event) {
        invalidateCache();
    }

    /** synchronized 与 refresh 互斥，避免失效瞬间被并发刷新写回旧值 */
    public synchronized void invalidateCache() {
        modelsCache = null;
        cacheAt = 0L;
        cacheLoaded = false;
    }

    /** 后台配置状态用：是否存在至少一个可直连的模型 */
    public boolean hasDirectModels() {
        Map<Long, ResolvedModel> models = allModels();
        return models != null && !models.isEmpty();
    }

    private ResolvedModel build(ModelsConfigBean bean) {
        if (bean == null || Boolean.FALSE.equals(bean.getEnable())
                || !PROTOCOL_OPENAI.equalsIgnoreCase(bean.getProtocol())) {
            return null;
        }
        String baseUrl = trimUrl(bean.getBaseUrl());
        String apiKey = trimToNull(bean.getApiKey());
        String upstreamModel = trimToNull(bean.getUpstreamModel());
        if (baseUrl == null || apiKey == null || upstreamModel == null) {
            return null;
        }
        return new ResolvedModel(baseUrl, apiKey, upstreamModel, bean.getName());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimUrl(String url) {
        String result = trimToNull(url);
        if (result == null) {
            return null;
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    @Getter
    public static class ResolvedModel {
        private final String baseUrl;
        private final String apiKey;
        private final String upstreamModel;
        private final String name;

        public ResolvedModel(String baseUrl, String apiKey, String upstreamModel, String name) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.upstreamModel = upstreamModel;
            this.name = name;
        }
    }
}
