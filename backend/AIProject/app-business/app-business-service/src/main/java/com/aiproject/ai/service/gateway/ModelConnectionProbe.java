package com.aiproject.ai.service.gateway;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.gateway.dto.ModelConnectionTestResult;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 直连模型「连接测试」。
 *
 * <p>只做只读探测：对话模型先 GET {@code /models}（不消耗 token），
 * 服务不支持该端点时再退化为一次 {@code max_tokens=1} 的最小对话请求；
 * 向量模型探测 {@code /embeddings}；重排模型探测 {@code /rerank}。
 * 不经过网关主链路，因此不会写用量审计、不影响配额与限流。</p>
 *
 * <p>为什么只接受 iid、不接受浏览器提交的 baseUrl / apiKey：
 * 凭据必须由服务端从数据库读取，避免把密钥回传浏览器再传回来；
 * 同时把可探测目标限定在管理员已保存的配置上，不新增任意 URL 探测面。</p>
 */
@Slf4j
@Component
public class ModelConnectionProbe {

    private static final int PROBE_TIMEOUT_MS = 5000;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String PROTOCOL_OPENAI = "openai";
    private static final String PROTOCOL_RERANK = "rerank";

    @Autowired
    ModelsConfigBeanHolder modelsConfigBeanHolder;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    private final ObjectMapper mapper = new ObjectMapper();

    public Result<ModelConnectionTestResult> test(String uid, ModelsConfigBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(null, "仅管理员可测试模型连接");
        }
        if (data == null || data.getIid() == null) {
            return Result.failed(null, "请选择要测试的模型配置");
        }
        ModelsConfigBean config = modelsConfigBeanHolder.findByIid(data.getIid());
        if (config == null) {
            return Result.failed(null, "模型配置不存在，请刷新后重试");
        }
        if (PROTOCOL_RERANK.equalsIgnoreCase(config.getProtocol())) {
            return testRerank(uid, config);
        }
        if (!PROTOCOL_OPENAI.equalsIgnoreCase(config.getProtocol())) {
            return Result.failed(null, "该配置类型暂不支持连接测试");
        }

        String baseUrl = trimUrl(config.getBaseUrl());
        String apiKey = trimToNull(config.getApiKey());
        String upstreamModel = trimToNull(config.getUpstreamModel());
        String embedModel = trimToNull(config.getEmbedModel());
        // 向量模型没有 chat 模型名，配置的是 embedModel；用向量接口探测才有意义
        if (upstreamModel == null && embedModel != null) {
            return testEmbedding(uid, config, baseUrl, apiKey, embedModel);
        }
        if (baseUrl == null || apiKey == null || upstreamModel == null) {
            return Result.failed(null, "服务地址、服务密钥、上游模型名均配置后才能测试");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return Result.failed(null, "服务地址必须以 http:// 或 https:// 开头");
        }

        ModelConnectionTestResult result = probe(baseUrl, apiKey, upstreamModel);
        return finish(uid, config, upstreamModel, result);
    }

    private Result<ModelConnectionTestResult> testEmbedding(String uid, ModelsConfigBean config,
                                                            String baseUrl, String apiKey,
                                                            String embedModel) {
        if (baseUrl == null || apiKey == null) {
            return Result.failed(null, "服务地址与服务密钥配置后才能测试");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return Result.failed(null, "服务地址必须以 http:// 或 https:// 开头");
        }
        long startedAt = System.currentTimeMillis();
        String body;
        try {
            body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", embedModel)
                    .put("input", "ping"));
        } catch (Exception e) {
            return Result.failed(null, "连接测试请求构造失败");
        }
        HttpReply reply = request("POST", baseUrl + "/embeddings", apiKey, body);
        if (reply.status == 200) {
            if (!embeddingReturned(reply.body)) {
                return finish(uid, config, embedModel, result(ModelConnectionTestResult.STATUS_HTTP_ERROR,
                        200, false, "向量接口返回的内容不可识别，请检查服务地址是否为 OpenAI 兼容接口",
                        startedAt));
            }
            return finish(uid, config, embedModel, result(ModelConnectionTestResult.STATUS_OK,
                    200, true, "连接成功（通过向量接口验证），模型「" + embedModel + "」可用", startedAt));
        }
        return finish(uid, config, embedModel,
                postFailure(reply, "向量", startedAt));
    }

    private Result<ModelConnectionTestResult> testRerank(String uid, ModelsConfigBean config) {
        String baseUrl = trimUrl(config.getBaseUrl());
        String apiKey = trimToNull(config.getApiKey());
        String upstreamModel = trimToNull(config.getUpstreamModel());
        if (baseUrl == null || apiKey == null || upstreamModel == null) {
            return Result.failed(null, "服务地址、服务密钥、上游模型名均配置后才能测试");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return Result.failed(null, "服务地址必须以 http:// 或 https:// 开头");
        }
        long startedAt = System.currentTimeMillis();
        String body;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode()
                    .put("model", upstreamModel)
                    .put("query", "ping");
            payload.set("documents", mapper.createArrayNode().add("ping"));
            payload.put("top_n", 1);
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return Result.failed(null, "连接测试请求构造失败");
        }
        HttpReply reply = request("POST", baseUrl + "/rerank", apiKey, body);
        if (reply.status == 200) {
            if (!rerankReturned(reply.body)) {
                return finish(uid, config, upstreamModel, result(ModelConnectionTestResult.STATUS_HTTP_ERROR,
                        200, false, "重排接口返回的内容不可识别，请检查服务地址与上游模型名", startedAt));
            }
            return finish(uid, config, upstreamModel, result(ModelConnectionTestResult.STATUS_OK,
                    200, true, "连接成功（通过重排接口验证），模型「" + upstreamModel + "」可用", startedAt));
        }
        return finish(uid, config, upstreamModel, postFailure(reply, "重排", startedAt));
    }

    private Result<ModelConnectionTestResult> finish(String uid, ModelsConfigBean config,
                                                     String probedModel,
                                                     ModelConnectionTestResult result) {
        result.setIid(config.getIid());
        result.setModelName(config.getName());
        result.setUpstreamModel(probedModel);
        result.setCheckedAt(System.currentTimeMillis());
        log.info("[ModelProbe] uid={}, iid={}, model={}, status={}, http={}, latency={}ms",
                uid, config.getIid(), probedModel, result.getStatus(),
                result.getHttpStatus(), result.getLatencyMs());
        return Result.succeed(result, result.getMessage());
    }

    /** 非 200 响应统一分类，避免对话 / 向量 / 重排三条路径的提示口径漂移 */
    private ModelConnectionTestResult postFailure(HttpReply reply, String label, long startedAt) {
        if (reply.networkError) {
            return result(ModelConnectionTestResult.STATUS_UNREACHABLE, null, false,
                    "无法连接到模型服务，请检查服务地址与网络", startedAt);
        }
        if (reply.status == 401 || reply.status == 403) {
            return result(ModelConnectionTestResult.STATUS_AUTH_FAILED, reply.status, false,
                    "鉴权失败（HTTP " + reply.status + "），请检查服务密钥", startedAt);
        }
        if (reply.status == 429) {
            return result(ModelConnectionTestResult.STATUS_RATE_LIMITED, reply.status, false,
                    "服务可达，但当前被限流（HTTP 429），请稍后重试", startedAt);
        }
        if (reply.status == 400 || reply.status == 404) {
            return result(ModelConnectionTestResult.STATUS_HTTP_ERROR, reply.status, false,
                    "服务可达，但" + label + "请求被拒绝（HTTP " + reply.status + "），请核对上游模型名",
                    startedAt);
        }
        return result(ModelConnectionTestResult.STATUS_HTTP_ERROR, reply.status, false,
                "服务返回 HTTP " + reply.status + "，请检查服务地址与模型配置", startedAt);
    }

    private boolean embeddingReturned(String body) {
        try {
            JsonNode data = mapper.readTree(body).path("data");
            if (!data.isArray() || data.size() == 0) {
                return false;
            }
            JsonNode embedding = data.get(0).path("embedding");
            return embedding.isArray() && embedding.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean rerankReturned(String body) {
        try {
            JsonNode results = mapper.readTree(body).path("results");
            if (!results.isArray() || results.size() == 0) {
                return false;
            }
            return results.get(0).has("index");
        } catch (Exception e) {
            return false;
        }
    }

    private ModelConnectionTestResult probe(String baseUrl, String apiKey, String upstreamModel) {
        long startedAt = System.currentTimeMillis();
        HttpReply modelsReply = request("GET", baseUrl + "/models", apiKey, null);
        if (modelsReply.networkError) {
            return result(ModelConnectionTestResult.STATUS_UNREACHABLE, null, false,
                    "无法连接到模型服务，请检查服务地址与网络", startedAt);
        }
        int status = modelsReply.status;
        if (status == 401 || status == 403) {
            return result(ModelConnectionTestResult.STATUS_AUTH_FAILED, status, false,
                    "鉴权失败（HTTP " + status + "），请检查服务密钥", startedAt);
        }
        if (status == 429) {
            return result(ModelConnectionTestResult.STATUS_RATE_LIMITED, status, false,
                    "服务可达，但当前被限流（HTTP 429），请稍后重试", startedAt);
        }
        if (status == 200) {
            Boolean available = modelListed(modelsReply.body, upstreamModel);
            if (available == null) {
                return result(ModelConnectionTestResult.STATUS_HTTP_ERROR, status, false,
                        "服务返回的不是可识别的模型列表，请检查服务地址是否为 OpenAI 兼容接口",
                        startedAt);
            }
            if (available) {
                return result(ModelConnectionTestResult.STATUS_OK, status, true,
                        "连接成功，模型「" + upstreamModel + "」可用", startedAt);
            }
            return result(ModelConnectionTestResult.STATUS_MODEL_MISSING, status, false,
                    "服务连接成功，但模型列表中没有「" + upstreamModel + "」，请核对上游模型名",
                    startedAt);
        }
        if (status == 404 || status == 405) {
            return chatFallback(baseUrl, apiKey, upstreamModel, startedAt);
        }
        return result(ModelConnectionTestResult.STATUS_HTTP_ERROR, status, false,
                "服务返回 HTTP " + status + "，请检查服务地址与模型配置", startedAt);
    }

    /**
     * 部分 OpenAI 兼容服务没有实现 /models，但 /chat/completions 可用。
     * 用 max_tokens=1 验证地址、密钥与模型名三者是否匹配，成本可忽略。
     */
    private ModelConnectionTestResult chatFallback(String baseUrl, String apiKey,
                                                   String upstreamModel, long startedAt) {
        String body;
        try {
            body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", upstreamModel)
                    .put("stream", false)
                    .put("max_tokens", 1)
                    .set("messages", mapper.createArrayNode()
                            .add(mapper.createObjectNode()
                                    .put("role", "user")
                                    .put("content", "ping"))));
        } catch (Exception e) {
            return result(ModelConnectionTestResult.STATUS_HTTP_ERROR, null, false,
                    "连接测试请求构造失败", startedAt);
        }

        HttpReply reply = request("POST", baseUrl + "/chat/completions", apiKey, body);
        if (reply.networkError) {
            return result(ModelConnectionTestResult.STATUS_UNREACHABLE, null, false,
                    "无法连接到模型服务，请检查服务地址与网络", startedAt);
        }
        if (reply.status == 200) {
            return result(ModelConnectionTestResult.STATUS_OK, reply.status, true,
                    "连接成功（通过对话接口验证），模型「" + upstreamModel + "」可用", startedAt);
        }
        if (reply.status == 401 || reply.status == 403) {
            return result(ModelConnectionTestResult.STATUS_AUTH_FAILED, reply.status, false,
                    "鉴权失败（HTTP " + reply.status + "），请检查服务密钥", startedAt);
        }
        if (reply.status == 429) {
            return result(ModelConnectionTestResult.STATUS_RATE_LIMITED, reply.status, false,
                    "服务可达，但当前被限流（HTTP 429），请稍后重试", startedAt);
        }
        if (reply.status == 400 || reply.status == 404) {
            return result(ModelConnectionTestResult.STATUS_HTTP_ERROR, reply.status, false,
                    "服务可达，但对话请求被拒绝（HTTP " + reply.status + "），请核对上游模型名",
                    startedAt);
        }
        return result(ModelConnectionTestResult.STATUS_HTTP_ERROR, reply.status, false,
                "服务返回 HTTP " + reply.status + "，请检查服务地址与模型配置", startedAt);
    }

    /**
     * 返回 null 表示响应不是可识别的 /models 结构；
     * true / false 表示是否列出了配置的上游模型。
     */
    private Boolean modelListed(String body, String upstreamModel) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode data = mapper.readTree(body).path("data");
            if (!data.isArray()) {
                return null;
            }
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (id.equalsIgnoreCase(upstreamModel)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            return null;
        }
    }

    private HttpReply request(String method, String url, String apiKey, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(PROBE_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            HttpReply reply = new HttpReply();
            reply.status = status;
            if (status >= 200 && status < 300) {
                reply.body = readBody(conn.getInputStream());
            }
            return reply;
        } catch (Exception e) {
            HttpReply reply = new HttpReply();
            reply.networkError = true;
            return reply;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readBody(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        int total = 0;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                break;
            }
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private ModelConnectionTestResult result(String status, Integer httpStatus, boolean available,
                                             String message, long startedAt) {
        ModelConnectionTestResult result = new ModelConnectionTestResult();
        result.setStatus(status);
        result.setHttpStatus(httpStatus);
        result.setModelAvailable(available);
        result.setMessage(message);
        result.setLatencyMs(System.currentTimeMillis() - startedAt);
        return result;
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

    private static class HttpReply {
        private int status;
        private String body;
        private boolean networkError;
    }
}
