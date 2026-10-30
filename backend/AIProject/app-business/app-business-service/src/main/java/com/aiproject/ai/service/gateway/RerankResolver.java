package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Rerank 精排客户端：把混合检索的候选片段交给 rerank 模型重排。
 *
 * <p>混合检索（向量 + 关键词 RRF）是「召回」层，追求不漏；
 * rerank 是「精排」层，用交叉编码器对 query-doc 逐对打分，
 * 把最相关的片段顶到最前。两阶段是检索系统的标准架构。</p>
 *
 * <p>配置沿用 modelsconfigbean：一行 protocol='rerank' 的记录
 * （baseUrl / apiKey / upstreamModel 齐全即生效）。这样 rerank 服务
 * 与对话模型共用同一套配置页面与密钥治理，不引入新表。</p>
 *
 * <p>协议采用 Jina/Cohere/SiliconFlow 通用的 /rerank：
 * {@code POST {model, query, documents[], top_n}} →
 * {@code {results: [{index, relevance_score}]}}。</p>
 */
@Slf4j
@Component
public class RerankResolver {

    public static final String PROTOCOL_RERANK = "rerank";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_DOCS = 24;

    @Autowired
    ModelsConfigBeanHolder modelsConfigBeanHolder;

    private volatile ResolvedRerank cache;
    private volatile long cacheAt;
    private volatile boolean resolvedOnce;

    private final ObjectMapper mapper = new ObjectMapper();

    /** @return 已配置的 rerank 服务；未配置返回 null（调用方降级原排序） */
    public ResolvedRerank resolve() {
        ResolvedRerank snapshot = cache;
        if (resolvedOnce && snapshot == null && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) {
            return null;
        }
        if (snapshot != null && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) {
            return snapshot;
        }
        return refresh();
    }

    private synchronized ResolvedRerank refresh() {
        boolean hasNullCache = resolvedOnce && cache == null;
        boolean fresh = System.currentTimeMillis() - cacheAt < CACHE_TTL_MS;
        if (resolvedOnce && (cache != null ? fresh : hasNullCache && fresh)) {
            return cache;
        }
        ResolvedRerank found = null;
        try {
            ModelsConfigBean where = new ModelsConfigBean();
            where.setProtocol(PROTOCOL_RERANK);
            List<ModelsConfigBean> list = modelsConfigBeanHolder.queryList(where, null);
            if (list != null) {
                for (ModelsConfigBean item : list) {
                    if (item == null || Boolean.FALSE.equals(item.getEnable())) {
                        continue;
                    }
                    String baseUrl = trimTail(item.getBaseUrl());
                    String apiKey = trim(item.getApiKey());
                    String model = trim(item.getUpstreamModel());
                    if (baseUrl != null && apiKey != null && model != null) {
                        found = new ResolvedRerank(baseUrl, apiKey, model);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Rerank] 读取配置失败，本次降级为混合检索排序: {}", e.getMessage());
            return cache;
        }
        cache = found;
        cacheAt = System.currentTimeMillis();
        resolvedOnce = true;
        return found;
    }

    /**
     * 后台保存模型配置后主动失效。
     *
     * <p>没有这一步时，停用 rerank 或更换密钥最长要等 5 分钟 TTL 才生效——
     * 管理员看到「已保存」，实际请求仍在用旧配置，属于容易误判的隐蔽问题。</p>
     */
    @EventListener
    public void onModelConfigChanged(ModelConfigChangedEvent event) {
        invalidateCache();
    }

    /** synchronized 与 refresh 互斥，避免失效瞬间被并发刷新写回旧值 */
    public synchronized void invalidateCache() {
        cache = null;
        cacheAt = 0L;
        resolvedOnce = false;
    }

    /**
     * 调用 rerank 服务重排文档。
     *
     * @return 按 relevance 降序的文档下标列表；未配置 / 调用失败 / 响应异常返回 null，
     *         调用方必须降级为传入顺序——精排是增强手段，不能阻塞检索主链路
     */
    public List<Integer> rerank(ResolvedRerank config, String query, List<String> documents) {
        if (config == null || query == null || documents == null || documents.isEmpty()) {
            return null;
        }
        List<String> docs = documents.size() > MAX_DOCS ? documents.subList(0, MAX_DOCS) : documents;
        HttpURLConnection conn = null;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("model", config.getModel());
            body.put("query", query);
            body.set("documents", mapper.valueToTree(docs));
            body.put("top_n", docs.size());
            conn = (HttpURLConnection) new URL(config.getBaseUrl() + "/rerank").openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status != 200) {
                log.warn("[Rerank] 上游返回 {}，降级原排序", status);
                return null;
            }
            JsonNode root = mapper.readTree(readAll(conn.getInputStream()));
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.size() == 0) {
                return null;
            }
            List<Integer> order = new ArrayList<Integer>();
            boolean[] seen = new boolean[docs.size()];
            for (JsonNode result : results) {
                JsonNode index = result.get("index");
                if (index == null || !index.isInt() || index.asInt() < 0
                        || index.asInt() >= docs.size() || seen[index.asInt()]) {
                    return null;
                }
                seen[index.asInt()] = true;
                order.add(index.asInt());
            }
            // 防御：上游返回的下标不完整时降级
            return order.size() == docs.size() ? order : null;
        } catch (Exception e) {
            log.warn("[Rerank] 调用失败，降级原排序: {}", e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String trim(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimTail(String v) {
        String t = trim(v);
        if (t == null) {
            return null;
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** 已解析的 rerank 服务配置 */
    @Getter
    public static class ResolvedRerank {
        private final String baseUrl;
        private final String apiKey;
        private final String model;

        public ResolvedRerank(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
        }
    }
}
