package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.AiCallLogHolder;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heneng.hnsp.common.croe.model.Result;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** 知识库增强默认检索条数 */
    private static final int KB_DEFAULT_TOP_K = 4;
    /** 知识库增强最多注入的片段数 */
    private static final int KB_MAX_PIECES = 8;
    /** 注入上下文的字符总量上限，防止撑爆上游模型窗口 */
    private static final int KB_MAX_CONTEXT_CHARS = 6000;
    /** 与 KbRagHolder.MAX_QUERY_CHARS 对齐：超长查询截断而非让检索失败 */
    private static final int KB_QUERY_MAX_CHARS = 2000;

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

    @Autowired
    KbRagHolder kbRagHolder;

    @Autowired
    ModelPriceResolver priceResolver;

    /**
     * 全链路请求 ID。
     *
     * <p>浏览器在发请求前生成一个 UUID 放进 {@code X-Request-Id} 头，
     * 网关读进来后一路带着走：审计日志落库、透传给上游、失败时写进错误响应。
     * 排查问题时用这一个 ID 就能把浏览器控制台、网关日志、
     * 上游调用记录串成一条线。</p>
     *
     * <p>用 ThreadLocal 而不是方法参数层层传递：proxy() 的调用链有四五层
     * （proxy → doProxy → streamSse/relayBody → record），加参数会污染
     * 所有这些签名。Tomcat 每个请求一个线程，ThreadLocal 天然隔离。</p>
     *
     * <p>必须清理：Tomcat 的线程是复用的，不清掉会把上一个请求的 ID
     * 泄漏给下一个请求，排查时反而误导。</p>
     */
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<String>();

    /**
     * 首块流式响应写出的时刻。与 requestId 同一套 ThreadLocal 模式：
     * proxy() 置空、streamSse 首写时赋值、record() 读取后随审计落库（TTFT）。
     * 非流式请求没有「首块」概念，保持 null。
     */
    private final ThreadLocal<Long> firstTokenAt = new ThreadLocal<Long>();

    /** 请求头名。小写是 HTTP/2 的规范形式，Tomcat 取值不区分大小写 */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    public void proxy(String module, String userId,
                      HttpServletRequest request, HttpServletResponse response) {
        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String requestId = resolveRequestId(request);
        currentRequestId.set(requestId);
        firstTokenAt.set(null);
        try {
            doProxyEntry(module, userId, request, response, start, requestId);
        } finally {
            // Tomcat 线程复用，必须清理，否则 ID 会串到下一个请求
            currentRequestId.remove();
            firstTokenAt.remove();
        }
    }

    /** proxy() 的主体，拆出来只是为了包住 try/finally 清理 ThreadLocal */
    private void doProxyEntry(String module, String userId,
                              HttpServletRequest request, HttpServletResponse response,
                              long start, String requestId) {
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

            JsonNode root = MAPPER.readTree(buffer.toByteArray());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("root is not an object");
            }
            ObjectNode body = (ObjectNode) root;
            body.put("model", model.getUpstreamModel());
            if (body.path("stream").asBoolean(false)) {
                body.putObject("stream_options").put("include_usage", true);
            }

            // 知识库增强字段只在本层消费，转发上游前剥离
            String kbName = body.path("kb_name").asText("").trim();
            JsonNode topKNode = body.path("kb_top_k");
            Integer kbTopK = topKNode.isInt() ? topKNode.asInt() : null;
            body.remove("kb_name");
            body.remove("kb_top_k");
            if (!kbName.isEmpty()) {
                int hits = applyKnowledgeContext(body, userId, kbName, kbTopK);
                response.setHeader("X-KB-Hits", String.valueOf(hits));
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

    /**
     * 知识库增强：取最后一条 user 消息做相似度检索，把命中片段以 system 消息
     * 注入到该 user 消息之前，模型回答时可引用来源。
     * <p>
     * 检索是增强而非依赖：未配置向量模型、检索异常、知识库为空等情况
     * 都静默降级为普通直连，不影响对话本身。
     * <p>
     * <b>注入策略</b>（两条都是生产环境踩出来的）：
     * <ul>
     *   <li><b>最相关的片段放最前</b>。LLM 对长上下文中段信息的利用度显著低于
     *       首尾（lost-in-the-middle），所以按 RRF 融合分降序注入，
     *       高分片段优先占位。</li>
     *   <li><b>按字符预算截断</b>而不是固定条数。检索片段是变长的，
     *       固定 8 条可能只有 2K 字符，也可能塞进 20K——后者会挤占对话历史
     *       与生成空间，甚至直接超窗口。</li>
     * </ul>
     *
     * @return 实际注入的片段数（写入 X-KB-Hits 响应头）
     */
    private int applyKnowledgeContext(ObjectNode body, String userId,
                                      String kbName, Integer topK) {
        try {
            JsonNode messagesNode = body.path("messages");
            if (!messagesNode.isArray()) {
                return 0;
            }
            ArrayNode messages = (ArrayNode) messagesNode;
            int userIndex = lastUserMessageIndex(messages);
            if (userIndex < 0) {
                return 0;
            }
            String query = extractUserText(messages.get(userIndex));
            if (query == null || query.trim().isEmpty()) {
                return 0;
            }
            if (query.length() > KB_QUERY_MAX_CHARS) {
                query = query.substring(0, KB_QUERY_MAX_CHARS);
            }

            Result<KbSearchResult> result = kbRagHolder.search(userId, query, kbName,
                    topK == null ? KB_DEFAULT_TOP_K : topK);
            List<KbSearchItem> items = result == null || result.getDatas() == null
                    ? null : result.getDatas().getItems();
            if (items == null || items.isEmpty()) {
                return 0;
            }

            // 按融合分降序：RRF 分越高越相关，必须排在上下文前部
            List<KbSearchItem> sorted = new ArrayList<KbSearchItem>(items);
            Collections.sort(sorted, new Comparator<KbSearchItem>() {
                @Override
                public int compare(KbSearchItem a, KbSearchItem b) {
                    return Double.compare(b.getScore(), a.getScore());
                }
            });

            StringBuilder prompt = new StringBuilder()
                    .append("以下是知识库「").append(kbName)
                    .append("」中与用户问题最相关的检索片段（已按相关度排序）。回答时请优先依据片段内容，并在用到的地方标注「来源：文档名」；")
                    .append("若片段与问题无关，请忽略它们并按你自己的知识回答。\n");
            int used = 0;
            int injected = 0;
            for (KbSearchItem item : sorted) {
                if (injected >= KB_MAX_PIECES) {
                    break;
                }
                String content = item.getContent() == null ? "" : item.getContent();
                String source = item.getDocName() == null || item.getDocName().trim().isEmpty()
                        ? "文档#" + item.getDocIid() : item.getDocName().trim();
                String block = "\n【片段" + (injected + 1) + " · 来源：" + source + "】\n" + content + "\n";
                if (used + block.length() > KB_MAX_CONTEXT_CHARS) {
                    int remain = KB_MAX_CONTEXT_CHARS - used;
                    // 剩余空间连一个片段头都放不下时就停，不要注入残句
                    if (remain < 200) {
                        break;
                    }
                    block = block.substring(0, remain) + "…";
                }
                prompt.append(block);
                used += block.length();
                injected++;
            }
            if (injected == 0) {
                return 0;
            }

            ObjectNode context = MAPPER.createObjectNode();
            context.put("role", "system");
            context.put("content", prompt.toString());
            messages.insert(userIndex, context);
            return injected;
        } catch (Exception e) {
            log.warn("[AiGateway] 知识库检索失败，降级为无上下文直连: {}", e.getMessage());
            return 0;
        }
    }

    private static int lastUserMessageIndex(ArrayNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).path("role").asText())) {
                return i;
            }
        }
        return -1;
    }

    /** user 消息文本：兼容纯字符串与 OpenAI 多模态 content 数组（取 text 部分） */
    private static String extractUserText(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText());
                }
            }
            return text.toString();
        }
        return null;
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
            // 全链路请求 ID 透传给上游：上游日志里出现同一个 ID，
            // 就能把「网关看到的」和「上游看到的」对齐，定位是网关问题还是上游问题
            String rid = currentRequestId.get();
            if (rid != null) {
                conn.setRequestProperty(REQUEST_ID_HEADER, rid);
            }
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
            if (firstTokenAt.get() == null) {
                firstTokenAt.set(System.currentTimeMillis());
            }
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

    /** 取出浏览器生成的请求 ID；没带就自己生成一个，保证链路一定有 ID */
    private String resolveRequestId(HttpServletRequest request) {
        String fromHeader = request.getHeader(REQUEST_ID_HEADER);
        if (fromHeader != null) {
            String trimmed = fromHeader.trim();
            // 限长防滥用：这个值会进数据库列和日志
            if (!trimmed.isEmpty() && trimmed.length() <= 64) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
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
        entry.setRequestId(currentRequestId.get());
        Long firstAt = firstTokenAt.get();
        if (firstAt != null) {
            entry.setFirstTokenMs(firstAt - start);
        }
        if (parser != null) {
            entry.setPromptTokens(parser.getPromptTokens());
            entry.setCompletionTokens(parser.getCompletionTokens());
            entry.setTotalTokens(parser.getTotalTokens());
            entry.setModelName(truncate(parser.getModelName(), 200));
            entry.setConversationId(truncate(parser.getConversationId(), 200));
            entry.setMessageId(truncate(parser.getMessageId(), 200));
        }
        entry.setCostCents(computeCostCents(entry.getModelName(),
                entry.getPromptTokens(), entry.getCompletionTokens()));
        callLogHolder.record(entry);
    }

    /**
     * 折算本次调用的成本（单位：分）。
     *
     * <p>模型未配置单价时返回 0。这里刻意不返回 null——
     * 0 在语义上表示「未知成本」，看板会据此提示配置价格；
     * 而 null 会让聚合逻辑到处判空。</p>
     */
    private Long computeCostCents(String modelName, Long promptTokens, Long completionTokens) {
        if (modelName == null || promptTokens == null || completionTokens == null) {
            return 0L;
        }
        try {
            ModelPriceResolver.Price price = priceResolver.resolve(modelName);
            if (price == null || !price.isConfigured()) {
                return 0L;
            }
            return price.costCents(promptTokens, completionTokens);
        } catch (Exception e) {
            // 成本核算失败绝不能影响 AI 调用本身
            log.warn("[AiGateway] 成本折算失败, model={}: {}", modelName, e.getMessage());
            return 0L;
        }
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
            // 错误响应也带上请求 ID：用户报障时把这个 ID 给管理员，
            // 管理员能直接捞出完整链路，不用靠「大概几点」猜
            String rid = currentRequestId.get();
            if (rid != null) {
                response.setHeader(REQUEST_ID_HEADER, rid);
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("code", code);
            body.put("message", message);
            body.put("status", status);
            body.put("requestId", rid);
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
