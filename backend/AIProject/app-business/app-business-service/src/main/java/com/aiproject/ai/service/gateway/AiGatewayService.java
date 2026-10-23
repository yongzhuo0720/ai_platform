package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.holde.AiCallLogHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 网关核心：把浏览器对 Dify 的调用收口到后端。
 * <p>
 * 这样做换来三件事：
 * 1. Dify API Key 只存在于服务端，浏览器不再持有；
 * 2. 每次调用都经过限流 / 每日 Token 配额；
 * 3. 每次调用写入审计日志（状态、tokens、耗时、会话ID），供后台看板统计。
 * <p>
 * 响应采用「边读边写」的透传方式，SSE 不会被缓冲，因此对话仍然是逐字输出。
 */
@Slf4j
@Service
public class AiGatewayService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 小于该大小的 JSON 响应会被完整读入，用于提取 usage；超过则直接透传 */
    private static final int MAX_JSON_BUFFER = 2 * 1024 * 1024;

    /** SSE 行缓冲上限，防止异常上游把内存撑爆 */
    private static final int MAX_SSE_LINE = 1024 * 1024;

    private static final Set<String> SKIP_RESPONSE_HEADERS = new HashSet<String>(Arrays.asList(
            "content-length", "transfer-encoding", "connection", "content-encoding",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te",
            "trailer", "upgrade", "set-cookie"
    ));

    @Autowired
    AiGatewayProperties properties;

    @Autowired
    AiGatewayConfigResolver configResolver;

    @Autowired
    ModelGatewayResolver modelGatewayResolver;

    @Autowired
    AiGatewayGuard guard;

    @Autowired
    AiCallLogHolder callLogHolder;

    public void proxy(String module, String userId,
                      HttpServletRequest request, HttpServletResponse response) {
        long start = System.currentTimeMillis();
        String method = request.getMethod();

        if (!properties.isEnabled()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "gateway_disabled", "AI 网关已关闭");
            return;
        }
        if (userId == null || userId.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "unauthorized", "请先登录");
            return;
        }
        if (ModelGatewayResolver.MODULE_MODELS.equals(module)) {
            proxyModel(userId, request, response, start);
            return;
        }
        if (!configResolver.isSupported(module)) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "module_not_found", "未知的 AI 模块");
            return;
        }

        String endpoint = extractEndpoint(request, module);
        if (endpoint == null || endpoint.isEmpty()) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "endpoint_not_found", "缺少 Dify 接口路径");
            return;
        }
        if (endpoint.contains("..") || endpoint.toLowerCase().contains("%2e")) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "invalid_endpoint", "非法的接口路径");
            return;
        }

        AiGatewayConfigResolver.ResolvedConfig config = configResolver.resolve(module);
        if (config == null) {
            String message = "AI 服务尚未配置，请管理员在后台「Dify 配置管理」中填写地址与密钥";
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "gateway_not_configured", message);
            record(userId, module, endpoint, method, "failed",
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE, null, start, message,
                    clientIp(request));
            return;
        }

        AiGatewayGuard.Decision decision = guard.check(userId, module);
        if (!decision.isAllowed()) {
            writeError(response, 429, decision.getCode(), decision.getMessage());
            record(userId, module, endpoint, method, "blocked", 429, null, start,
                    decision.getMessage(), clientIp(request));
            return;
        }

        doProxy(module, userId, endpoint, endpoint, config, request, response, start);
    }

    /**
     * 模型直连网关：/api/aiGateway/models/{模型iid}/chat/completions。
     * <p>
     * 与 Dify 透传不同，这里收下 OpenAI 协议的请求体并做两处收敛后转发：
     * 1. model 字段以服务端配置为准 —— 防止借网关调用该服务上的任意模型；
     * 2. stream=true 时注入 stream_options.include_usage —— OpenAI 协议默认
     *    不随流返回 usage，不注入就无法统计 tokens、执行每日配额。
     */
    private void proxyModel(String userId, HttpServletRequest request,
                            HttpServletResponse response, long start) {
        String method = request.getMethod();
        String endpoint = extractEndpoint(request, ModelGatewayResolver.MODULE_MODELS);
        if (endpoint == null || endpoint.isEmpty()) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "endpoint_not_found", "缺少模型接口路径");
            return;
        }
        if (endpoint.contains("..") || endpoint.toLowerCase().contains("%2e")) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "invalid_endpoint", "非法的接口路径");
            return;
        }

        int slash = endpoint.indexOf('/');
        if (slash <= 0 || slash == endpoint.length() - 1) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "model_not_found", "缺少模型标识");
            return;
        }
        String modelKey = endpoint.substring(0, slash);
        String upstreamEndpoint = endpoint.substring(slash + 1);
        long iid = parseModelId(modelKey);
        if (iid <= 0) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "invalid_model", "非法的模型标识");
            return;
        }
        if (!"chat/completions".equals(upstreamEndpoint)) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "endpoint_not_found", "模型直连仅支持 chat/completions 接口");
            return;
        }

        ModelGatewayResolver.ResolvedModel model = modelGatewayResolver.resolve(iid);
        if (model == null) {
            String message = "模型不存在或未启用直连（需配置 OpenAI 兼容协议、服务地址、密钥与上游模型名）";
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "model_unavailable", message);
            record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "failed",
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE, null, start, message,
                    clientIp(request));
            return;
        }

        AiGatewayGuard.Decision decision =
                guard.check(userId, ModelGatewayResolver.MODULE_MODELS);
        if (!decision.isAllowed()) {
            writeError(response, 429, decision.getCode(), decision.getMessage());
            record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "blocked",
                    429, null, start, decision.getMessage(), clientIp(request));
            return;
        }

        byte[] presetBody = readModelRequestBody(request, response, userId,
                model, endpoint, method, start);
        if (presetBody == null) {
            // 响应与审计已在 readModelRequestBody 内处理
            return;
        }

        AiGatewayConfigResolver.ResolvedConfig config =
                new AiGatewayConfigResolver.ResolvedConfig(model.getBaseUrl(), model.getApiKey());
        doProxy(ModelGatewayResolver.MODULE_MODELS, userId,
                upstreamEndpoint, endpoint, config, request, response, start, presetBody);
    }

    /** 读取并改写 OpenAI 协议请求体；失败时写错误响应并记录审计，返回 null */
    private byte[] readModelRequestBody(HttpServletRequest request, HttpServletResponse response,
                                        String userId, ModelGatewayResolver.ResolvedModel model,
                                        String endpoint, String method, long start) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = request.getInputStream()) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (buffer.size() + read > MAX_JSON_BUFFER) {
                        writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                                "payload_too_large", "请求体过大");
                        record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "failed",
                                HttpServletResponse.SC_BAD_REQUEST, null, start,
                                "请求体过大", clientIp(request));
                        return null;
                    }
                    buffer.write(chunk, 0, read);
                }
            }
            if (buffer.size() == 0) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "invalid_request", "请求体不能为空");
                record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "failed",
                        HttpServletResponse.SC_BAD_REQUEST, null, start,
                        "请求体为空", clientIp(request));
                return null;
            }

            com.fasterxml.jackson.databind.JsonNode root =
                    MAPPER.readTree(buffer.toByteArray());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("root is not an object");
            }
            com.fasterxml.jackson.databind.node.ObjectNode body =
                    (com.fasterxml.jackson.databind.node.ObjectNode) root;
            body.put("model", model.getUpstreamModel());
            if (body.path("stream").asBoolean(false)) {
                body.putObject("stream_options").put("include_usage", true);
            }
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            String message = "请求体必须是 OpenAI 协议 JSON（含 messages 字段）：" + rootMessage(e);
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "invalid_request", message);
            record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "failed",
                    HttpServletResponse.SC_BAD_REQUEST, null, start, message,
                    clientIp(request));
            return null;
        }
    }

    private static long parseModelId(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void doProxy(String module, String userId,
                         String upstreamEndpoint, String auditEndpoint,
                         AiGatewayConfigResolver.ResolvedConfig config,
                         HttpServletRequest request, HttpServletResponse response,
                         long start) {
        doProxy(module, userId, upstreamEndpoint, auditEndpoint, config,
                request, response, start, null);
    }

    private void doProxy(String module, String userId,
                         String upstreamEndpoint, String auditEndpoint,
                         AiGatewayConfigResolver.ResolvedConfig config,
                         HttpServletRequest request, HttpServletResponse response,
                         long start, byte[] presetBody) {
        String method = request.getMethod();
        String clientIp = clientIp(request);
        HttpURLConnection conn = null;
        InputStream upstream = null;

        try {
            String target = config.getApiUrl() + "/" + upstreamEndpoint;
            String query = request.getQueryString();
            if (query != null && !query.isEmpty()) {
                target = target + "?" + query;
            }

            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setConnectTimeout(properties.getConnectTimeoutMs());
            conn.setReadTimeout(properties.getReadTimeoutMs());
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method.toUpperCase());
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
            conn.setRequestProperty("Accept", "text/event-stream, application/json");
            // 让上游不要压缩，否则透传的 SSE / JSON 会变成二进制
            conn.setRequestProperty("Accept-Encoding", "identity");
            String contentType = request.getContentType();
            boolean multipart = contentType != null
                    && contentType.toLowerCase().startsWith("multipart/form-data");
            if (contentType != null && !multipart) {
                conn.setRequestProperty("Content-Type", contentType);
            }

            if (presetBody != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(presetBody.length);
                OutputStream bodyOut = conn.getOutputStream();
                bodyOut.write(presetBody);
                bodyOut.flush();
                bodyOut.close();
            } else {
                relayRequestBody(request, conn, method);
            }

            int upstreamStatus = conn.getResponseCode();
            String upstreamType = conn.getContentType();
            upstream = upstreamStatus >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (upstream == null) {
                upstream = new java.io.ByteArrayInputStream(new byte[0]);
            }

            response.setStatus(upstreamStatus);
            copyResponseHeaders(conn, response);

            boolean sse = upstreamType != null
                    && upstreamType.toLowerCase().contains("text/event-stream");
            if (sse) {
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("X-Accel-Buffering", "no");
            }

            AiGatewayUsageParser parser = new AiGatewayUsageParser();
            if (sse) {
                streamSse(upstream, response.getOutputStream(), parser);
            } else {
                relayBody(upstream, response.getOutputStream(), parser);
            }

            boolean failed = upstreamStatus >= 400 || parser.getErrorMessage() != null;
            String status = failed ? "failed" : "success";
            String error = parser.getErrorMessage();
            if (failed && error == null) {
                error = "上游返回 HTTP " + upstreamStatus;
            }
            record(userId, module, auditEndpoint, method, status, upstreamStatus, parser,
                    start, error, clientIp);
            if (parser.getTotalTokens() != null) {
                guard.addTokens(userId, parser.getTotalTokens());
            }
        } catch (Exception e) {
            String message = rootMessage(e);
            boolean aborted = isClientAbort(e);
            String status = aborted ? "aborted" : "failed";
            String error = aborted ? "客户端已断开连接" : message;

            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_BAD_GATEWAY,
                        "upstream_error", "AI 服务调用失败：" + message);
            } else {
                log.warn("[AiGateway] 透传中断, uid={}, module={}, endpoint={}, aborted={}: {}",
                        userId, module, auditEndpoint, aborted, message);
            }
            record(userId, module, auditEndpoint, method, status,
                    HttpServletResponse.SC_BAD_GATEWAY, null, start, error, clientIp);
        } finally {
            closeQuietly(upstream);
            if (conn != null) {
                conn.disconnect();
            }
            try {
                response.flushBuffer();
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
        }
    }

    private void relayRequestBody(HttpServletRequest request, HttpURLConnection conn,
                                  String method) throws Exception {
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
            relayMultipart(request, conn);
            return;
        }
        long contentLength = request.getContentLengthLong();
        boolean chunked = "chunked".equalsIgnoreCase(request.getHeader("Transfer-Encoding"));
        if (contentLength <= 0 && !chunked) {
            return;
        }
        conn.setDoOutput(true);
        if (contentLength > 0 && !chunked) {
            conn.setFixedLengthStreamingMode(contentLength);
        } else {
            conn.setChunkedStreamingMode(8192);
        }
        try (InputStream in = request.getInputStream();
             OutputStream out = conn.getOutputStream()) {
            copy(in, out);
        }
    }

    /**
     * 重建 multipart 请求体。
     * <p>
     * Spring / Tomcat 在参数解析时可能已经把 multipart 原始流消费掉，
     * 此时按 Content-Length 转发原始流会因「写入字节不足」失败。
     * 因此改为从已解析的 Part 重新拼一份 multipart（新的 boundary）发给上游，
     * 语义等价且不依赖请求体是否已被读取。
     */
    private void relayMultipart(HttpServletRequest request, HttpURLConnection conn) throws Exception {
        String boundary = "----AiGatewayBoundary"
                + UUID.randomUUID().toString().replace("-", "");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(8192);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        Collection<Part> parts = request.getParts();
        try (OutputStream out = conn.getOutputStream()) {
            for (Part part : parts) {
                StringBuilder header = new StringBuilder();
                header.append("--").append(boundary).append("\r\n");
                header.append("Content-Disposition: form-data; name=\"")
                        .append(escapeQuoted(part.getName())).append("\"");
                String filename = part.getSubmittedFileName();
                if (filename != null) {
                    header.append("; filename=\"").append(escapeQuoted(filename)).append("\"");
                }
                header.append("\r\n");
                if (part.getContentType() != null) {
                    header.append("Content-Type: ").append(part.getContentType()).append("\r\n");
                }
                header.append("\r\n");
                out.write(header.toString().getBytes(StandardCharsets.UTF_8));
                try (InputStream in = part.getInputStream()) {
                    copy(in, out);
                }
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String escapeQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "");
    }

    /**
     * SSE 逐块透传：每读到一块立即写给浏览器，同时按行嗅探 usage。
     * 注意不能按行读上游再写，否则会改变分包与到达时序，破坏「逐字输出」。
     */
    private void streamSse(InputStream in, OutputStream out, AiGatewayUsageParser parser)
            throws IOException {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush();
            for (int i = 0; i < read; i++) {
                byte value = buffer[i];
                if (value == '\n') {
                    parser.acceptSseLine(new String(line.toByteArray(), StandardCharsets.UTF_8));
                    line.reset();
                } else if (value != '\r' && line.size() < MAX_SSE_LINE) {
                    line.write(value);
                }
            }
        }
        if (line.size() > 0) {
            parser.acceptSseLine(new String(line.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 非 SSE 响应：小响应完整缓冲后写入并解析 usage；大响应直接透传。
     */
    private void relayBody(InputStream in, OutputStream out, AiGatewayUsageParser parser)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        boolean spilled = false;
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (!spilled) {
                if (buffer.size() + read > MAX_JSON_BUFFER) {
                    out.write(buffer.toByteArray());
                    buffer = null;
                    spilled = true;
                } else {
                    buffer.write(chunk, 0, read);
                }
            }
            if (spilled) {
                out.write(chunk, 0, read);
                out.flush();
            }
        }
        if (!spilled) {
            byte[] body = buffer.toByteArray();
            out.write(body);
            out.flush();
            parser.acceptJson(new String(body, StandardCharsets.UTF_8));
        }
    }

    private void copyResponseHeaders(HttpURLConnection conn, HttpServletResponse response) {
        for (Map.Entry<String, java.util.List<String>> entry : conn.getHeaderFields().entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            if (SKIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            for (String value : entry.getValue()) {
                response.addHeader(name, value);
            }
        }
    }

    private String extractEndpoint(HttpServletRequest request, String module) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String prefix = "/api/aiGateway/" + module;
        if (!uri.startsWith(prefix)) {
            return null;
        }
        String endpoint = uri.substring(prefix.length());
        while (endpoint.startsWith("/")) {
            endpoint = endpoint.substring(1);
        }
        return endpoint;
    }

    private void record(String userId, String module, String endpoint, String method,
                        String status, Integer httpStatus, AiGatewayUsageParser parser,
                        long start, String error, String clientIp) {
        AiCallLogBean entry = new AiCallLogBean();
        entry.setUserId(userId);
        entry.setModule(module);
        entry.setEndpoint(truncate(endpoint, 500));
        entry.setMethod(method);
        entry.setStatus(status);
        entry.setHttpStatus(httpStatus);
        entry.setLatencyMs(System.currentTimeMillis() - start);
        entry.setClientIp(truncate(clientIp, 64));
        entry.setErrorMessage(truncate(error, properties.getMaxErrorLength()));
        if (parser != null) {
            entry.setPromptTokens(parser.getPromptTokens());
            entry.setCompletionTokens(parser.getCompletionTokens());
            entry.setTotalTokens(parser.getTotalTokens());
            entry.setModelName(truncate(parser.getModelName(), 200));
            entry.setConversationId(truncate(parser.getConversationId(), 200));
            entry.setMessageId(truncate(parser.getMessageId(), 200));
        }
        callLogHolder.record(entry);
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) {
        try {
            if (response.isCommitted()) {
                return;
            }
            response.reset();
            response.setStatus(status);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("code", code);
            body.put("message", message);
            body.put("status", status);
            response.getOutputStream().write(
                    MAPPER.writeValueAsBytes(body));
            response.flushBuffer();
        } catch (Exception e) {
            log.warn("[AiGateway] 写错误响应失败: {}", e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean isClientAbort(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String name = current.getClass().getSimpleName();
            String message = current.getMessage();
            if (name.contains("ClientAbort")) {
                return true;
            }
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("broken pipe") || lower.contains("connection reset")
                        || lower.contains("an established connection was aborted")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof SocketTimeoutException) {
            return "AI 响应超时";
        }
        String message = current.getMessage();
        return message == null || message.isEmpty() ? current.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
