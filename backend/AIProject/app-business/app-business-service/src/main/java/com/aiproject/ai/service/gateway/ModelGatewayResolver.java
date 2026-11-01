package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
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
    /** /v1/models 需要的原始配置（含 createtime 与 name），与 modelsCache 同批加载 */
    private volatile List<ModelsConfigBean> configCache;
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
     * 按 OpenAI 协议里的 {@code model} 字段解析。
     * <p>
     * {@code /v1/chat/completions} 的模型标识来自请求体，可能是三种写法之一：
     * 平台 iid 字符串、平台模型名、上游模型名。顺序是先精确（iid）后忽略大小写匹配名字，
     * 名字重复时取 iid 最小的那个（{@link #directConfigs()} 已按 iid 排序构建快照）。
     *
     * @return 解析结果；没有任何可直连模型或名字对不上时返回 null
     */
    public ResolvedModel resolveByName(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) {
            return null;
        }
        String wanted = modelId.trim();
        Map<Long, ResolvedModel> models = allModels();
        if (models == null || models.isEmpty()) {
            return null;
        }
        try {
            ResolvedModel byIid = models.get(Long.parseLong(wanted));
            if (byIid != null) {
                return byIid;
            }
        } catch (NumberFormatException ignored) {
            // 不是数字就按名字找
        }
        for (Map.Entry<Long, ResolvedModel> entry : orderedEntries(models)) {
            ResolvedModel model = entry.getValue();
            if (wanted.equalsIgnoreCase(model.getName())) {
                return model;
            }
        }
        for (Map.Entry<Long, ResolvedModel> entry : orderedEntries(models)) {
            if (wanted.equalsIgnoreCase(entry.getValue().getUpstreamModel())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 可直连模型配置清单，供 {@code GET /v1/models} 使用。
     * 与快照同一批数据、同一套失效规则，避免「列表里有但调用时 404」。
     */
    public List<ModelsConfigBean> directConfigs() {
        // 快照是懒建的，冷启动或刚失效时 configCache 还是 null。
        // 直接读它会让 /v1/models 在「一个模型都没列出来」和「正常」之间随机切换，
        // 而 /v1/chat/completions 走 allModels() 又正常——两者必须同一批数据。
        allModels();
        List<ModelsConfigBean> configs = configCache;
        return configs == null ? Collections.<ModelsConfigBean>emptyList() : configs;
    }

    /** 快照里的 Map 是 HashMap，遍历顺序不稳定；对外的解析与列表统一按 iid 升序走 */
    private static List<Map.Entry<Long, ResolvedModel>> orderedEntries(Map<Long, ResolvedModel> models) {
        List<Map.Entry<Long, ResolvedModel>> entries = new ArrayList<Map.Entry<Long, ResolvedModel>>(models.entrySet());
        Collections.sort(entries, new java.util.Comparator<Map.Entry<Long, ResolvedModel>>() {
            @Override
            public int compare(Map.Entry<Long, ResolvedModel> a, Map.Entry<Long, ResolvedModel> b) {
                return Long.compare(a.getKey(), b.getKey());
            }
        });
        return entries;
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
        List<ModelsConfigBean> callable = new ArrayList<ModelsConfigBean>();
        List<ModelsConfigBean> list = modelsConfigBeanHolder.queryList(new ModelsConfigBean(), null);
        if (list != null) {
            List<ModelsConfigBean> sorted = new ArrayList<ModelsConfigBean>(list);
            // 名字重复时靠 iid 稳定决断（resolveByName 与 /v1/models 必须给出同一个模型）
            Collections.sort(sorted, new java.util.Comparator<ModelsConfigBean>() {
                @Override
                public int compare(ModelsConfigBean a, ModelsConfigBean b) {
                    long left = a.getIid() == null ? Long.MAX_VALUE : a.getIid();
                    long right = b.getIid() == null ? Long.MAX_VALUE : b.getIid();
                    return Long.compare(left, right);
                }
            });
            for (ModelsConfigBean item : sorted) {
                ResolvedModel resolved = build(item);
                if (resolved == null || item.getIid() == null) {
                    continue;
                }
                models.put(item.getIid(), resolved);
                callable.add(item);
            }
        }
        configCache = Collections.unmodifiableList(callable);
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
        configCache = null;
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
        return new ResolvedModel(bean.getIid(), baseUrl, apiKey, upstreamModel, bean.getName());
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
        /** 平台配置行的 iid：令牌的模型白名单可能写 iid，需要它作为可匹配的写法之一 */
        private final Long iid;
        private final String baseUrl;
        private final String apiKey;
        private final String upstreamModel;
        private final String name;

        public ResolvedModel(String baseUrl, String apiKey, String upstreamModel, String name) {
            this(null, baseUrl, apiKey, upstreamModel, name);
        }

        public ResolvedModel(Long iid, String baseUrl, String apiKey,
                             String upstreamModel, String name) {
            this.iid = iid;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.upstreamModel = upstreamModel;
            this.name = name;
        }
    }
}
