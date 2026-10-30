package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

/**
 * 向量化客户端：调用 OpenAI 兼容的 /embeddings 接口。
 * <p>
 * 向量模型在 modelsconfigbean 上配置（protocol=openai 且 embedModel 非空），
 * 密钥只在服务端使用；embeddings 为服务端内部调用，不经过浏览器网关。
 */
@Slf4j
@Component
public class EmbeddingClient {

    private static final int TIMEOUT_MS = 30000;

    /** 配置缓存 TTL：后台写入会发 ModelConfigChangedEvent 立即失效，TTL 只兜底直改库 */
    private static final long CONFIG_CACHE_TTL_MS = 5 * 60 * 1000L;

    @Autowired
    ModelsConfigBeanHolder modelsConfigBeanHolder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile ResolvedEmbedding configCache;
    private volatile long configCacheAt;
    private volatile boolean configResolvedOnce;

    /**
     * 解析出一个可用的向量化配置；不存在返回 null。
     * <p>
     * 带 5 分钟缓存：每次检索都读一遍 modelsconfigbean 是纯浪费，
     * 而未配置时返回 null 同样要缓存——否则没配模型的知识库每次提问都会落一次查询。
     */
    public ResolvedEmbedding resolve() {
        long now = System.currentTimeMillis();
        if (configResolvedOnce && now - configCacheAt < CONFIG_CACHE_TTL_MS) {
            return configCache;
        }
        try {
            return refresh(now);
        } catch (Exception e) {
            // 读库失败不写缓存：下一次调用可以立即重试，而不是把故障状态粘住 5 分钟
            log.warn("[KbRag] 读取向量化模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    private synchronized ResolvedEmbedding refresh(long now) {
        // 双重检查：并发首访时只让一个线程查库
        if (configResolvedOnce && now - configCacheAt < CONFIG_CACHE_TTL_MS) {
            return configCache;
        }
        ResolvedEmbedding resolved = loadFromDb();
        configCache = resolved;
        configCacheAt = now;
        configResolvedOnce = true;
        return resolved;
    }

    private ResolvedEmbedding loadFromDb() {
        ModelsConfigBean where = new ModelsConfigBean();
        where.setProtocol(ModelGatewayResolver.PROTOCOL_OPENAI);
        List<ModelsConfigBean> list = modelsConfigBeanHolder.queryList(where, null);
        if (list == null) {
            return null;
        }
        for (ModelsConfigBean item : list) {
            String embedModel = trimToNull(item.getEmbedModel());
            String baseUrl = trimToNull(item.getBaseUrl());
            if (embedModel == null || baseUrl == null) {
                continue;
            }
            String enabled = String.valueOf(item.getEnable());
            if ("false".equalsIgnoreCase(enabled)) {
                continue;
            }
            String baseUrlTrimmed = baseUrl;
            while (baseUrlTrimmed.endsWith("/")) {
                baseUrlTrimmed = baseUrlTrimmed.substring(0, baseUrlTrimmed.length() - 1);
            }
            return new ResolvedEmbedding(baseUrlTrimmed, trimToNull(item.getApiKey()),
                    embedModel, item.getName());
        }
        return null;
    }

    /** 后台保存模型配置后立即丢缓存，不必等 TTL */
    @EventListener
    public void onModelConfigChanged(ModelConfigChangedEvent event) {
        invalidateCache();
    }

    /** synchronized 与 refresh 互斥，避免失效瞬间被并发刷新写回旧值 */
    public synchronized void invalidateCache() {
        configCache = null;
        configCacheAt = 0L;
        configResolvedOnce = false;
    }

    /**
     * 批量向量化。
     *
     * @return 与输入顺序一致的一组向量
     * @throws IllegalStateException 未配置向量模型
     * @throws Exception             上游调用失败
     */
    public List<double[]> embed(ResolvedEmbedding config, List<String> inputs) throws Exception {
        if (config == null) {
            throw new IllegalStateException("未配置向量模型，请先在模型配置中设置直连协议与向量模型");
        }
        String target = config.getBaseUrl() + "/embeddings";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getEmbedModel());
        ArrayNode inputNode = body.putArray("input");
        for (String item : inputs) {
            inputNode.add(item);
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (config.getApiKey() != null) {
                conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
            }
            byte[] payload = objectMapper.writeValueAsBytes(body);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String response = readAll(stream);
            if (status >= 400) {
                throw new IllegalStateException("向量化接口返回 HTTP " + status + "：" + snippet(response));
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.size() != inputs.size()) {
                throw new IllegalStateException("向量化接口返回格式异常：" + snippet(response));
            }
            List<double[]> vectors = new ArrayList<double[]>(Collections.<double[]>nCopies(inputs.size(), null));
            int dimensions = -1;
            for (JsonNode item : data) {
                JsonNode indexNode = item.get("index");
                if (indexNode == null || !indexNode.isInt() || indexNode.asInt() < 0
                        || indexNode.asInt() >= inputs.size() || vectors.get(indexNode.asInt()) != null) {
                    throw new IllegalStateException("向量化接口返回的 index 缺失、重复或越界");
                }
                JsonNode embedding = item.get("embedding");
                if (embedding == null || !embedding.isArray() || embedding.size() == 0
                        || (dimensions >= 0 && dimensions != embedding.size())) {
                    throw new IllegalStateException("向量化接口返回的 embedding 为空或维度不一致");
                }
                dimensions = embedding.size();
                double[] vector = new double[embedding.size()];
                for (int dimension = 0; dimension < embedding.size(); dimension++) {
                    JsonNode value = embedding.get(dimension);
                    if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                        throw new IllegalStateException("向量化接口返回了无效的向量数值");
                    }
                    vector[dimension] = value.asDouble();
                }
                vectors.set(indexNode.asInt(), vector);
            }
            return vectors;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 200 ? singleLine.substring(0, 200) + "…" : singleLine;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Getter
    public static class ResolvedEmbedding {
        private final String baseUrl;
        private final String apiKey;
        private final String embedModel;
        private final String name;

        public ResolvedEmbedding(String baseUrl, String apiKey, String embedModel, String name) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.embedModel = embedModel;
            this.name = name;
        }
    }
}
