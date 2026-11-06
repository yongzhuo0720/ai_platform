package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.AiApiTokenBean;
import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.AiApiTokenHolder;
import com.aiproject.ai.service.holde.AiCallLogHolder;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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

    /** 响应 usage 解析缓冲上限：在此以内的完整 JSON 可精确提取 token；超出则对头部做 best-effort 嗅探 */
    private static final int MAX_JSON_PARSE = 8 * 1024 * 1024;

    /** SSE 行缓冲上限，防止异常上游把内存撑爆 */
    private static final int MAX_SSE_LINE = 1024 * 1024;

    /**
     * multipart 请求体上限，与 application.yml 的 {@code servlet.multipart.max-request-size}
     * 保持同一个数字；这里只是把「超限」提前到建连之前判定，好给出正确的 413。
     */
    private static final long MAX_MULTIPART_REQUEST_BYTES = 120L * 1024 * 1024;

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
    /** 检索来源事件的请求属性：由 readModelRequestBody 写入，doProxy 在转发上游前取出并下发 */
    private static final String KB_SOURCES_ATTR = "aiGateway.kbSourcesEvent";

    /** SSE 注释帧：客户端解析器会忽略，顺带起到保活与断连探测的作用 */
    private static final byte[] SSE_HEARTBEAT = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

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

    @Autowired
    AiApiTokenHolder apiTokenHolder;

    @Autowired
    AiIdempotencyGuard idempotencyGuard;

    @Autowired
    GatewayConcurrencyGuard concurrencyGuard;

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
     * 首个「带正文的 SSE 事件」到达的时刻。与 requestId 同一套 ThreadLocal 模式：
     * proxy() 置空、streamSse 嗅探到正文时赋值、record() 读取后随审计落库（TTFT）。
     * 非流式请求没有「首 token」概念，保持 null；上游全程无正文（如报错）同样为 null。
     */
    private final ThreadLocal<Long> firstTokenAt = new ThreadLocal<Long>();

    /** 请求头名。小写是 HTTP/2 的规范形式，Tomcat 取值不区分大小写 */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** OpenAI 兼容端点的审计端点名（module 仍是 models，与浏览器直连同账） */
    private static final String OPENAI_CHAT_ENDPOINT = "v1/chat/completions";

    /**
     * 错误响应是否改用 OpenAI 协议形状。
     *
     * <p>站内网关返回 {@code {code,message,status}}，前端按 code 分支；
     * 而 {@code /v1/**} 的外部调用方是现成的 OpenAI SDK，它们只认
     * {@code {"error":{"message","type","code"}}}，按 HTTP 状态码抛异常。
     * 两种形状靠这一个开关在同一套 writeError 上切换，
     * 目的是让 doProxy 内部十几处已有的 writeError 调用点在 v1 链路上
     * 自动给出协议正确的错误，而不必为兼容端点复制一条转发链路。</p>
     */
    private final ThreadLocal<Boolean> openAiErrorShape = new ThreadLocal<Boolean>();

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
        // 只放行前端实际使用的 Dify 接口：服务端持有的是应用/数据集密钥，
        // 不能因为「路径能拼出来」就把 Dify 的整棵 API 树开放给任意登录用户。
        if (!DifyEndpointPolicy.isAllowed(module, method, endpoint)) {
            String message = "该接口路径不允许通过 AI 网关访问";
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "upstream_path_forbidden", message);
            record(userId, module, endpoint, method, "failed",
                    HttpServletResponse.SC_FORBIDDEN, null, start, message, clientIp(request));
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
            int status = blockedHttpStatus(decision.getCode());
            writeError(response, status, decision.getCode(), decision.getMessage());
            record(userId, module, endpoint, method, "blocked", status, null, start,
                    decision.getMessage(), clientIp(request));
            return;
        }

        // 2026-11-01：上传超限在建立上游连接之前判定。
        // 修复前这里不看大小，超过 multipart 上限的请求会等到 relayMultipart 调
        // request.getParts() 时才抛 MaxUploadSizeExceededException，落到 doProxy 的
        // 兜底 catch，用户和看板看到的是 502「AI 服务调用失败」——
        // 可上游根本没收到这个请求，故障被记到了别人头上，还得白等一次建连。
        if (isOversizeUpload(request)) {
            int tooLarge = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE;
            String message = "上传内容超过 " + (MAX_MULTIPART_REQUEST_BYTES / (1024 * 1024))
                    + "MB，请拆分文件或减少一次提交的文件数";
            writeError(response, tooLarge, "payload_too_large", message);
            record(userId, module, endpoint, method, "failed", tooLarge, null, start,
                    message, clientIp(request));
            return;
        }

        AiGatewayGuard.Reservation reservation = guard.reserve(userId, module);
        if (reservation != null && reservation.isBlocked()) {
            int status = blockedHttpStatus(reservation.getBlockedCode());
            writeError(response, status, reservation.getBlockedCode(), reservation.getBlockedMessage());
            record(userId, module, endpoint, method, "blocked", status, null, start,
                    reservation.getBlockedMessage(), clientIp(request));
            return;
        }
        doProxy(module, userId, endpoint, endpoint, config, request, response, start,
                null, reservation);
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
            int status = blockedHttpStatus(decision.getCode());
            writeError(response, status, decision.getCode(), decision.getMessage());
            record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "blocked",
                    status, null, start, decision.getMessage(), clientIp(request));
            return;
        }

        byte[] presetBody = readModelRequestBody(request, response, userId,
                model, endpoint, method, start);
        if (presetBody == null) {
            // 响应与审计已在 readModelRequestBody 内处理
            return;
        }

        // 预扣放在请求体校验之后：非法请求不会占用额度
        AiGatewayGuard.Reservation reservation =
                guard.reserve(userId, ModelGatewayResolver.MODULE_MODELS);
        if (reservation != null && reservation.isBlocked()) {
            int status = blockedHttpStatus(reservation.getBlockedCode());
            writeError(response, status, reservation.getBlockedCode(), reservation.getBlockedMessage());
            record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "blocked",
                    status, null, start, reservation.getBlockedMessage(), clientIp(request));
            return;
        }

        AiGatewayConfigResolver.ResolvedConfig config =
                new AiGatewayConfigResolver.ResolvedConfig(model.getBaseUrl(), model.getApiKey());
        doProxy(ModelGatewayResolver.MODULE_MODELS, userId,
                upstreamEndpoint, endpoint, config, request, response, start, presetBody,
                reservation);
    }

    /** 读取 OpenAI 协议请求体（带大小上限）。失败时写响应与审计并返回 null */
    private byte[] readRequestBodyBytes(HttpServletRequest request, HttpServletResponse response,
                                        String userId, String endpoint, String method, long start) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = request.getInputStream()) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (buffer.size() + read > MAX_JSON_BUFFER) {
                        int tooLarge = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE;
                        writeError(response, tooLarge, "payload_too_large",
                                "请求体超过 " + (MAX_JSON_BUFFER / (1024 * 1024)) + "MB，请缩减图片或上下文长度");
                        record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "failed",
                                tooLarge, null, start, "请求体过大", clientIp(request));
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
            return buffer.toByteArray();
        } catch (Exception e) {
            String message = "请求体读取失败：" + rootMessage(e);
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request", message);
            record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "failed",
                    HttpServletResponse.SC_BAD_REQUEST, null, start, message,
                    clientIp(request));
            return null;
        }
    }

    /** 把请求体解析成 JSON 对象。失败时写响应与审计并返回 null */
    private ObjectNode parseRequestBodyNode(byte[] raw, HttpServletRequest request,
                                            HttpServletResponse response, String userId,
                                            String endpoint, String method, long start) {
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("root is not an object");
            }
            return (ObjectNode) root;
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
     * 按服务端配置收敛请求体：model 以配置为准、stream=true 时注入 include_usage、
     * 消费并剥离 kb_* 知识库增强字段。失败时写响应与审计并返回 null。
     */
    private byte[] normalizeModelBody(ObjectNode body, ModelGatewayResolver.ResolvedModel model,
                                      HttpServletRequest request, HttpServletResponse response,
                                      String userId, String endpoint, String method, long start) {
        try {
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
                List<KbSearchItem> used = applyKnowledgeContext(body, userId, kbName, kbTopK);
                response.setHeader("X-KB-Hits", String.valueOf(used.size()));
                // 流式请求在转发上游前先下发「实际注入的来源」，
                // 前端据此展示引用，不再自己重复检索一次
                if (!used.isEmpty() && body.path("stream").asBoolean(false)) {
                    request.setAttribute(KB_SOURCES_ATTR, buildKbSourcesEvent(used, kbName));
                }
            }
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            String message = "请求体处理失败：" + rootMessage(e);
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_request", message);
            record(userId, ModelGatewayResolver.MODULE_MODELS, endpoint, method, "failed",
                    HttpServletResponse.SC_BAD_REQUEST, null, start, message,
                    clientIp(request));
            return null;
        }
    }

    /**
     * 浏览器直连通道的请求体处理：读 → 解析 → 收敛。
     * 模型由 URL 里的 iid 决定，所以不看请求体的 model 字段。
     */
    private byte[] readModelRequestBody(HttpServletRequest request, HttpServletResponse response,
                                        String userId, ModelGatewayResolver.ResolvedModel model,
                                        String endpoint, String method, long start) {
        byte[] raw = readRequestBodyBytes(request, response, userId, endpoint, method, start);
        if (raw == null) {
            return null;
        }
        ObjectNode body = parseRequestBodyNode(raw, request, response, userId,
                endpoint, method, start);
        if (body == null) {
            return null;
        }
        return normalizeModelBody(body, model, request, response, userId, endpoint, method, start);
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
     *       首尾（lost-in-the-middle），所以沿用检索层最终排序，
     *       保留可选 Rerank 精排的优先级。</li>
     *   <li><b>按字符预算截断</b>而不是固定条数。检索片段是变长的，
     *       固定 8 条可能只有 2K 字符，也可能塞进 20K——后者会挤占对话历史
     *       与生成空间，甚至直接超窗口。</li>
     * </ul>
     *
     * @return 实际注入的片段（按注入顺序），同时用于 X-KB-Hits 与来源事件
     */
    private List<KbSearchItem> applyKnowledgeContext(ObjectNode body, String userId,
                                                     String kbName, Integer topK) {
        List<KbSearchItem> injectedItems = new ArrayList<KbSearchItem>();
        try {
            JsonNode messagesNode = body.path("messages");
            if (!messagesNode.isArray()) {
                return injectedItems;
            }
            ArrayNode messages = (ArrayNode) messagesNode;
            int userIndex = lastUserMessageIndex(messages);
            if (userIndex < 0) {
                return injectedItems;
            }
            String query = extractUserText(messages.get(userIndex));
            if (query == null || query.trim().isEmpty()) {
                return injectedItems;
            }
            if (query.length() > KB_QUERY_MAX_CHARS) {
                query = query.substring(0, KB_QUERY_MAX_CHARS);
            }

            Result<KbSearchResult> result = kbRagHolder.search(userId, query, kbName,
                    topK == null ? KB_DEFAULT_TOP_K : topK);
            List<KbSearchItem> items = result == null || result.getDatas() == null
                    ? null : result.getDatas().getItems();
            if (items == null || items.isEmpty()) {
                return injectedItems;
            }

            StringBuilder prompt = new StringBuilder()
                    .append("以下是知识库「").append(kbName)
                    .append("」中与用户问题最相关的检索片段（已按相关度排序）。回答时请优先依据片段内容，并在用到的地方标注「来源：文档名」；")
                    .append("若片段与问题无关，请忽略它们并按你自己的知识回答。\n");
            int used = 0;
            int injected = 0;
            for (KbSearchItem item : items) {
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
                injectedItems.add(item);
            }
            if (injected == 0) {
                return injectedItems;
            }

            ObjectNode context = MAPPER.createObjectNode();
            context.put("role", "system");
            context.put("content", prompt.toString());
            messages.insert(userIndex, context);
            return injectedItems;
        } catch (Exception e) {
            log.warn("[AiGateway] 知识库检索失败，降级为无上下文直连: {}", e.getMessage());
            return injectedItems;
        }
    }

    /**
     * 构造发给前端的来源 SSE 事件。字段与检索接口的 items 保持一致，
     * 前端复用同一套引用展示组件。
     */
    private byte[] buildKbSourcesEvent(List<KbSearchItem> items, String kbName) throws Exception {
        ArrayNode sources = MAPPER.createArrayNode();
        for (KbSearchItem item : items) {
            ObjectNode node = sources.addObject();
            node.put("docIid", item.getDocIid());
            node.put("docName", item.getDocName());
            node.put("kbName", item.getKbName() == null ? kbName : item.getKbName());
            node.put("chunkIndex", item.getChunkIndex());
            node.put("content", item.getContent());
            node.put("score", item.getScore());
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("items", sources);
        String frame = "event: kb_sources\ndata: " + MAPPER.writeValueAsString(payload) + "\n\n";
        return frame.getBytes(StandardCharsets.UTF_8);
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

    /** 是否是声明长度已超过 multipart 上限的上传请求（未声明长度的分块请求由下面的异常兜底） */
    private static boolean isOversizeUpload(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
            return false;
        }
        return request.getContentLengthLong() > MAX_MULTIPART_REQUEST_BYTES;
    }

    /**
     * OpenAI 兼容入口：{@code POST /v1/chat/completions}。
     *
     * <p><b>与站内直连网关的三点区别。</b>
     * <ol>
     *   <li>身份来自 Bearer 平台令牌（鉴权拦截器已校验并写进请求属性），
     *       不是浏览器登录态 —— 这样外部程序（脚本、IDE 插件、SDK）才能用；
     *       但落到网关内部仍以令牌归属的 uid 记账，限流、每日配额、审计一概不变。</li>
     *   <li>模型来自请求体的 {@code model} 字段，不是 URL 里的 iid ——
     *       所以这里要按名字解析，并且用令牌的白名单二次收敛，
     *       否则一个令牌就能调用平台上配置的所有模型。</li>
     *   <li>错误响应改成 OpenAI 协议形状，外部 SDK 才能按 {@code error.type} 正常抛错。</li>
     * </ol>
     *
     * <p>转发本身完全复用 {@link #doProxy}：SSE 逐块透传、usage 嗅探、
     * 中断计费、预扣结算这些行为与站内对话一模一样，不另写一份。
     */
    public void proxyOpenAiChat(HttpServletRequest request, HttpServletResponse response) {
        long start = System.currentTimeMillis();
        currentRequestId.set(resolveRequestId(request));
        firstTokenAt.set(null);
        openAiErrorShape.set(Boolean.TRUE);
        try {
            doProxyOpenAiChat(request, response, start);
        } finally {
            openAiErrorShape.remove();
            currentRequestId.remove();
            firstTokenAt.remove();
        }
    }

    private void doProxyOpenAiChat(HttpServletRequest request, HttpServletResponse response, long start) {
        String method = request.getMethod();
        if (!properties.isEnabled()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "gateway_disabled", "AI 网关已关闭");
            return;
        }
        // 令牌身份由拦截器写入；拿不到说明拦截器没生效或被绕过，一律按未认证处理
        AiApiTokenBean token = tokenOf(request);
        String userId = uidOf(request);
        if (token == null || userId == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "invalid_api_key", "无效的 API 密钥");
            return;
        }

        AiGatewayGuard.Decision decision =
                guard.check(userId, ModelGatewayResolver.MODULE_MODELS);
        if (!decision.isAllowed()) {
            int status = blockedHttpStatus(decision.getCode());
            writeError(response, status, decision.getCode(), decision.getMessage());
            record(userId, ModelGatewayResolver.MODULE_MODELS, OPENAI_CHAT_ENDPOINT,
                    method, "blocked", status, null, start, decision.getMessage(), clientIp(request));
            return;
        }

        byte[] raw = readRequestBodyBytes(request, response, userId, OPENAI_CHAT_ENDPOINT, method, start);
        if (raw == null) {
            return;
        }
        ObjectNode body = parseRequestBodyNode(raw, request, response, userId,
                OPENAI_CHAT_ENDPOINT, method, start);
        if (body == null) {
            return;
        }
        String requestedModel = body.path("model").asText("").trim();
        if (requestedModel.isEmpty()) {
            rejectOpenAiRequest(response, userId, method, start, request,
                    HttpServletResponse.SC_BAD_REQUEST, "model_required",
                    "缺少 model 字段，取值见 GET /v1/models");
            return;
        }
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.size() == 0) {
            rejectOpenAiRequest(response, userId, method, start, request,
                    HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
                    "messages 必须是非空数组");
            return;
        }
        ModelGatewayResolver.ResolvedModel model = modelGatewayResolver.resolveByName(requestedModel);
        if (model == null) {
            rejectOpenAiRequest(response, userId, method, start, request,
                    HttpServletResponse.SC_NOT_FOUND, "model_not_found",
                    "模型 \"" + requestedModel + "\" 不存在或不支持 OpenAI 协议直连");
            return;
        }
        if (!apiTokenHolder.allowsModel(token, requestedModel, model.getName(),
                model.getUpstreamModel(), model.getIid() == null ? null : String.valueOf(model.getIid()))) {
            rejectOpenAiRequest(response, userId, method, start, request,
                    HttpServletResponse.SC_FORBIDDEN, "model_not_allowed",
                    "该 API 密钥不允许调用模型 \"" + model.getName() + "\"");
            return;
        }

        byte[] presetBody = normalizeModelBody(body, model, request, response, userId,
                OPENAI_CHAT_ENDPOINT, method, start);
        if (presetBody == null) {
            return;
        }

        // 预扣放在请求体校验之后：非法请求不占用额度
        AiGatewayGuard.Reservation reservation =
                guard.reserve(userId, ModelGatewayResolver.MODULE_MODELS);
        if (reservation != null && reservation.isBlocked()) {
            int status = blockedHttpStatus(reservation.getBlockedCode());
            writeError(response, status, reservation.getBlockedCode(), reservation.getBlockedMessage());
            record(userId, ModelGatewayResolver.MODULE_MODELS, OPENAI_CHAT_ENDPOINT,
                    method, "blocked", status, null, start, reservation.getBlockedMessage(),
                    clientIp(request));
            return;
        }

        AiGatewayConfigResolver.ResolvedConfig config =
                new AiGatewayConfigResolver.ResolvedConfig(model.getBaseUrl(), model.getApiKey());
        doProxy(ModelGatewayResolver.MODULE_MODELS, userId, "chat/completions",
                OPENAI_CHAT_ENDPOINT, config, request, response, start, presetBody, reservation);
    }

    /**
     * OpenAI 兼容入口：{@code GET /v1/models}。
     * <p>
     * 只列「可直连」的模型，且按令牌白名单过滤——列表里的 id 必须能直接拿去调
     * {@code /v1/chat/completions}，否则外部程序按列表拼请求就会得到一串 403。
     */
    public void writeOpenAiModels(HttpServletRequest request, HttpServletResponse response) {
        currentRequestId.set(resolveRequestId(request));
        openAiErrorShape.set(Boolean.TRUE);
        try {
            doWriteOpenAiModels(request, response);
        } finally {
            openAiErrorShape.remove();
            currentRequestId.remove();
        }
    }

    private void doWriteOpenAiModels(HttpServletRequest request, HttpServletResponse response) {
        if (!properties.isEnabled()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "gateway_disabled", "AI 网关已关闭");
            return;
        }
        AiApiTokenBean token = tokenOf(request);
        if (token == null || uidOf(request) == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "invalid_api_key", "无效的 API 密钥");
            return;
        }
        ArrayNode data = MAPPER.createArrayNode();
        for (ModelsConfigBean config : modelGatewayResolver.directConfigs()) {
            String iid = config.getIid() == null ? null : String.valueOf(config.getIid());
            if (!apiTokenHolder.allowsModel(token, iid, config.getName(), config.getUpstreamModel())) {
                continue;
            }
            // id 用平台模型名：客户端把它原样填进 model 即可命中 resolveByName
            ObjectNode item = data.addObject();
            item.put("id", config.getName() == null || config.getName().trim().isEmpty()
                    ? iid : config.getName().trim());
            item.put("object", "model");
            item.put("created", config.getCreatetime() == null
                    ? 0L : config.getCreatetime().getTime() / 1000L);
            item.put("owned_by", "ai-platform");
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("object", "list");
        payload.set("data", data);
        writeJson(response, HttpServletResponse.SC_OK, payload);
    }

    /** v1 链路上的请求级拒绝：写 OpenAI 形状的错误并记一条失败审计 */
    private void rejectOpenAiRequest(HttpServletResponse response, String userId, String method,
                                     long start, HttpServletRequest request,
                                     int status, String code, String message) {
        writeError(response, status, code, message);
        record(userId, ModelGatewayResolver.MODULE_MODELS, OPENAI_CHAT_ENDPOINT,
                method, "failed", status, null, start, message, clientIp(request));
    }

    /** 鉴权拦截器写入的令牌记录；不是 AiApiTokenBean 就当没有 */
    private static AiApiTokenBean tokenOf(HttpServletRequest request) {
        Object attr = request.getAttribute(AiApiTokenHolder.ATTR_TOKEN);
        return attr instanceof AiApiTokenBean ? (AiApiTokenBean) attr : null;
    }

    private static String uidOf(HttpServletRequest request) {
        Object attr = request.getAttribute(AiApiTokenHolder.ATTR_UID);
        if (attr instanceof String) {
            String uid = ((String) attr).trim();
            return uid.isEmpty() ? null : uid;
        }
        return null;
    }

    private void writeJson(HttpServletResponse response, int status, Object body) {
        try {
            if (response.isCommitted()) {
                return;
            }
            response.reset();
            response.setStatus(status);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            String rid = currentRequestId.get();
            if (rid != null) {
                response.setHeader(REQUEST_ID_HEADER, rid);
            }
            response.getOutputStream().write(MAPPER.writeValueAsBytes(body));
            response.flushBuffer();
        } catch (Exception e) {
            log.warn("[AiGateway] 写 JSON 响应失败: {}", e.getMessage());
        }
    }

    private void doProxy(String module, String userId,
                         String upstreamEndpoint, String auditEndpoint,
                         AiGatewayConfigResolver.ResolvedConfig config,
                         HttpServletRequest request, HttpServletResponse response,
                         long start, byte[] presetBody,
                         AiGatewayGuard.Reservation reservation) {
        String method = request.getMethod();
        String clientIp = clientIp(request);
        HttpURLConnection conn = null;
        InputStream upstream = null;
        // 提升到 try 外：客户端中途断开时 catch 里要用它记审计，
        // 记录「上游当时到底回了什么状态」，而不是笼统的 502
        Integer upstreamStatus = null;
        // 成功且上游返回 usage 时的真实用量；finally 里据此结算预扣
        long actualTokens = 0L;
        boolean billable = false;
        // 已经转发给浏览器的字节数。客户端中断时靠它判断「这个回答其实已经真实消耗过上游」，
        // 而它必须在异常抛出后仍读得到，所以用持有者对象而不是局部变量。
        Relayed relayed = new Relayed();
        // 同样提到 try 外：中断时已解析到的模型名、会话 ID 和部分用量仍要记进审计
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        // 占位必须在上游调用之前取得；放进 try 是为了 acquire 自身抛异常时
        // 预扣也能走 finally 释放，而不是泄漏给 30 分钟后的回收任务
        AiIdempotencyGuard.Lease idempotencyLease = null;
        // 并发许可：同样在 try 内获取、finally 释放，保证任何异常路径都能归还
        GatewayConcurrencyGuard.Lease concurrencyLease = null;
        // 响应是否为 SSE，提到 try 外供 catch 里的计费判断使用
        boolean sseResponse = false;
        try {
            AiIdempotencyGuard.Acquisition idempotency =
                    idempotencyGuard.acquire(userId, module, request);
            if (idempotency.isRejected()) {
                writeError(response, idempotency.getHttpStatus(),
                        idempotency.getCode(), idempotency.getMessage());
                record(userId, module, auditEndpoint, method,
                        idempotency.getHttpStatus() == 409 ? "blocked" : "failed",
                        idempotency.getHttpStatus(), null, start, idempotency.getMessage(), clientIp);
                return;
            }
            idempotencyLease = idempotency.getLease();

            // 并发闸门放在幂等之后：被判定为重复的请求不该占用并发许可。
            // 非阻塞获取，拿不到就立刻拒绝，绝不在这里排队——排队会连 Web 线程一起占满。
            concurrencyLease = concurrencyGuard.tryAcquire(module);
            if (concurrencyLease == null) {
                String busy = "当前 AI 请求过多，请稍后重试";
                Map<String, String> retryAfter = new LinkedHashMap<String, String>();
                retryAfter.put("Retry-After", "2");
                writeError(response, 429, GatewayConcurrencyGuard.CODE_BUSY, busy, retryAfter);
                record(userId, module, auditEndpoint, method, "blocked", 429,
                        null, start, busy, clientIp);
                return;
            }
            String target = config.getApiUrl() + "/" + upstreamEndpoint;
            String query = request.getQueryString();
            if (isDifyChatModule(module)) {
                query = withDifyUser(query, userId);
            }
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
                relayRequestBody(request, conn, method, module, userId);
            }

            upstreamStatus = conn.getResponseCode();
            String upstreamType = conn.getContentType();
            upstream = upstreamStatus >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (upstream == null) {
                upstream = new java.io.ByteArrayInputStream(new byte[0]);
            }

            response.setStatus(upstreamStatus);
            copyResponseHeaders(conn, response);

            boolean sse = upstreamType != null
                    && upstreamType.toLowerCase().contains("text/event-stream");
            sseResponse = sse;
            if (sse) {
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("X-Accel-Buffering", "no");
            }

            // 先下发检索来源，再透传上游回答：前端拿到的是模型真正收到的片段
            if (sse && upstreamStatus < 400) {
                Object kbSourcesEvent = request.getAttribute(KB_SOURCES_ATTR);
                if (kbSourcesEvent instanceof byte[]) {
                    OutputStream eventOut = response.getOutputStream();
                    eventOut.write((byte[]) kbSourcesEvent);
                    eventOut.flush();
                }
            }

            long deadline = streamDeadline(start);
            int heartbeatMs = properties.getSseHeartbeatMs();
            if (sse) {
                // 把 socket 读超时缩短到心跳间隔：上游长时间不吐字时，读循环会定期醒来
                // 给浏览器写心跳，借这次写入发现「页面已经关了」。
                // 真正的「上游彻底没声了」仍按 readTimeoutMs 判定（见 streamSse）。
                if (heartbeatMs > 0) {
                    int poll = Math.max(100, heartbeatMs);
                    if (properties.getReadTimeoutMs() > 0) {
                        poll = Math.min(poll, properties.getReadTimeoutMs());
                    }
                    conn.setReadTimeout(poll);
                }
                streamSse(upstream, response.getOutputStream(), parser, relayed,
                        deadline, heartbeatMs, properties.getReadTimeoutMs());
            } else {
                relayBody(upstream, response.getOutputStream(), parser, relayed, deadline);
            }

            // 上游提前断流时只会看到 EOF：HTTP 200、没有 error 帧、也没有 usage。
            // 不把这种流判成失败，用户就能靠「断开上游」白拿已经生成的内容。
            boolean truncatedStream = sseResponse && !parser.isStreamCompleted()
                    && parser.getTotalTokens() == null && relayed.bytes > 0;
            boolean failed = upstreamStatus >= 400 || parser.getErrorMessage() != null
                    || truncatedStream;
            String status = failed ? "failed" : "success";
            String error = parser.getErrorMessage();
            if (failed && error == null) {
                error = truncatedStream
                        ? "上游流式响应提前结束" : "上游返回 HTTP " + upstreamStatus;
            }

            // 2026-10-29：失败请求不再计入配额。
            // 修复前这里完全没看 failed——只要上游响应里出现过 usage 字段就照扣，
            // 于是上游报错（限流、上下文超长、模型不可用）时用户额度照样被吃掉，
            // 而用户什么结果都没拿到。
            // 注意：正常响应里若带了 error 字段（部分模型会在 200 里塞错误信息），
            // 同样按失败处理，不计费。
            if (!failed && parser.getTotalTokens() != null) {
                actualTokens = parser.getTotalTokens();
                billable = true;
                // 已预扣的请求由 settle 按真实用量多退少补；
                // 未预扣（配额不限或 Redis 降级）才在这里直接累计
                if (reservation == null || !reservation.isActive()) {
                    guard.addTokens(userId, module, actualTokens);
                }
            } else if (!failed && parser.getTotalTokens() == null && !parser.isUsageSeen()
                    && relayed.bytes > 0 && reservation != null && reservation.isActive()) {
                // 上游正常结束但没给 usage（部分模型/供应商省略 usage 字段）。
                // 用户已经拿到完整回答，上游也确实生成过：不能按「没拿到用量」全额退款，
                // 否则这类供应商等于免费。usage 明确返回 0 的（isUsageSeen 为 true）不在此列。
                billable = true;
                actualTokens = reservation.getTokens();
                parser.markBilledTokens(actualTokens);
                log.warn("[AiGateway] 上游正常结束但未返回 usage，按预扣值计费: uid={}, module={}, relayedBytes={}, tokens={}",
                        userId, module, relayed.bytes, actualTokens);
            } else if (failed && parser.getTotalTokens() == null && sseResponse
                    && shouldBillPartialOutput(relayed, upstreamStatus, true, false)
                    && reservation != null && reservation.isActive()) {
                // 流已经吐出一部分正文后才失败（上游错误帧、连接被重置、超时）：
                // 上游确实生成过内容，不能因为「没拿到最终 usage」就全额退款。
                // 非 SSE 的 200 错误体不走这里——那种响应通常一个字都没生成。
                billable = true;
                actualTokens = reservation.getTokens();
                parser.markBilledTokens(actualTokens);
                log.info("[AiGateway] 上游中途失败，按预扣值计费: uid={}, module={}, relayedBytes={}, tokens={}",
                        userId, module, relayed.bytes, actualTokens);
            } else if (failed && parser.getTotalTokens() != null) {
                log.warn("[AiGateway] 上游失败，本次用量不计入配额: uid={}, module={}, tokens={}, httpStatus={}",
                        userId, module, parser.getTotalTokens(), upstreamStatus);
            }

            // 放在计费判断之后：按预扣值计费时，审计里的 tokens 必须等于真正扣掉的额度
            record(userId, module, auditEndpoint, method, status, upstreamStatus, parser,
                    start, error, clientIp);
        } catch (MaxUploadSizeExceededException e) {
            // 分块上传没有声明长度，躲得过上面的预检，只能在这里接住。
            // 关键是不能让它落到兜底 catch 写成 502「上游错误」：
            // 请求根本没出网关，超限的是浏览器传上来的文件。
            int tooLarge = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE;
            String message = "上传内容超过 " + (MAX_MULTIPART_REQUEST_BYTES / (1024 * 1024))
                    + "MB，请拆分文件或减少一次提交的文件数";
            writeError(response, tooLarge, "payload_too_large", message);
            record(userId, module, auditEndpoint, method, "failed", tooLarge,
                    null, start, message, clientIp);
        } catch (ResponseStatusException e) {
            int status = e.getStatus().value();
            writeError(response, status, "invalid_request", e.getReason());
            record(userId, module, auditEndpoint, method, "failed", status,
                    null, start, e.getReason(), clientIp);
        } catch (Exception e) {
            String message = rootMessage(e);
            boolean aborted = isClientAbort(e);
            boolean timedOut = e instanceof StreamDeadlineException;
            String status = aborted ? "aborted" : "failed";
            String error = aborted ? "客户端已断开连接" : message;
            boolean billedByReserve = false;

            if (!response.isCommitted()) {
                writeError(response,
                        timedOut ? HttpServletResponse.SC_GATEWAY_TIMEOUT : HttpServletResponse.SC_BAD_GATEWAY,
                        timedOut ? "stream_timeout" : "upstream_error",
                        timedOut ? message : "AI 服务调用失败：" + message);
            } else {
                if (timedOut && sseResponse) {
                    // 流已经吐了一部分，没法再改 HTTP 状态码，补一帧 SSE 错误让前端
                    // 知道「是到时限被网关断的」，而不是把半截回答当成正常结束
                    appendSseError(response, module, message);
                }
                log.warn("[AiGateway] 透传中断, uid={}, module={}, endpoint={}, aborted={}, timedOut={}: {}",
                        userId, module, auditEndpoint, aborted, timedOut, message);
            }

            // 2026-11-01：中断不再全额退额度。
            // 修复前这里一律 settle(0)：用户点「停止」就一分不扣，于是可以反复
            // 「让它说完再停」白拿回答——上游那半截输出是真花了钱的。
            // SSE 的 usage 只在最后一帧（Dify 的 message_end）里给，中断时拿不到真实值，
            // 因此按预扣值结算：多退少补都省掉，至少不退回零。
            // 一个字节都没转发出去就断开的（比如刚连上就关页面）仍然全额释放。
            // 2026-11-05：同一规则扩展到「非客户端原因的中途失败」——上游 accepted
            // 之后连接重置/读超时，只要已有正文送出，同样是真实消耗，不能全额退款。
            if (!billable && shouldBillPartialOutput(relayed, upstreamStatus, sseResponse, aborted)
                    && reservation != null && reservation.isActive()) {
                billable = true;
                actualTokens = reservation.getTokens();
                parser.markBilledTokens(actualTokens);
                billedByReserve = true;
                log.info("[AiGateway] 响应中途失败，按预扣值计费: uid={}, module={}, aborted={}, relayedBytes={}, tokens={}",
                        userId, module, aborted, relayed.bytes, actualTokens);
            }

            // 只有「按预扣值计费」这一条路径把 parser 写进审计：此刻它的 totalTokens 就是我们真正扣掉的额度。
            // 其他异常分支传 null——上游没给完整 usage，写进去会让看板显示的用量和实际扣掉的额度对不上。
            Integer auditStatus;
            if (aborted) {
                auditStatus = upstreamStatus;
            } else if (timedOut) {
                auditStatus = HttpServletResponse.SC_GATEWAY_TIMEOUT;
            } else {
                auditStatus = HttpServletResponse.SC_BAD_GATEWAY;
            }
            record(userId, module, auditEndpoint, method, status,
                    auditStatus, billedByReserve ? parser : null, start, error, clientIp);
        } finally {
            // 先还并发许可再结算：结算要访问 Redis，不该让下一个请求陪着等
            if (concurrencyLease != null) {
                concurrencyLease.release();
            }
            // 成功按真实 usage 结算；上游失败或「一个字节都没送出去就断开」全额释放；
            // 已经送出内容的客户端中断按预扣值结算（见上面 catch 里的说明）
            if (reservation != null && reservation.isActive()) {
                guard.settle(reservation, billable ? actualTokens : 0L);
            }
            if (idempotencyLease != null) {
                // 上游已受理（哪怕没拿到 usage）就保留幂等键，防止调用方重试造成重复执行；
                // 只有请求根本没被上游成功受理时才释放，允许用同一个键重试。
                boolean effectAchieved = billable
                        || (upstreamStatus != null && upstreamStatus < 400
                        && parser.getErrorMessage() == null);
                if (effectAchieved) {
                    idempotencyGuard.complete(idempotencyLease);
                } else {
                    idempotencyGuard.release(idempotencyLease);
                }
            }
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

    /**
     * 响应中断时是否应按预扣值计费。
     *
     * <p>四个条件缺一不可：已经向上游发出请求并收到接受响应（&lt;400）、确实有字节
     * 转发给了浏览器、不是「一个字节都没送出去」的空中断，以及（非客户端主动断开时）
     * SSE 至少已经出现带正文的事件——只有心跳/role 帧不算模型产出。</p>
     */
    private boolean shouldBillPartialOutput(Relayed relayed, Integer upstreamStatus,
                                            boolean sse, boolean aborted) {
        if (relayed.bytes <= 0) {
            return false;
        }
        if (upstreamStatus == null || upstreamStatus >= 400) {
            return false;
        }
        if (aborted) {
            return true;
        }
        return !sse || firstTokenAt.get() != null;
    }

    private void relayRequestBody(HttpServletRequest request, HttpURLConnection conn,
                                  String method, String module, String userId) throws Exception {
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
            relayMultipart(request, conn, isDifyChatModule(module) ? userId : null);
            return;
        }
        long contentLength = request.getContentLengthLong();
        boolean chunked = "chunked".equalsIgnoreCase(request.getHeader("Transfer-Encoding"));
        // 仅对 Dify 对话类模块注入 user=uid：Dify 的 /chat-messages 依赖请求体里的 user
        // 做会话隔离，网关若不注入，所有平台用户共享同一应用密钥，会话仅靠客户端自报的
        // conversation_id 隔离，存在越权读取/续写他人对话的风险。注入后以服务端登录身份
        // 强制隔离。models 直连为无状态 OpenAI 协议，不在此处理（由 readModelRequestBody 收敛）。
        if (isDifyChatModule(module)) {
            if (contentType != null && !contentType.toLowerCase().startsWith("application/json")) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "对话请求体必须是 JSON");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int read;
            try (InputStream in = request.getInputStream()) {
                while ((read = in.read(chunk)) != -1) {
                    if (buffer.size() + read > MAX_JSON_BUFFER) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "请求体超过 2MB");
                    }
                    buffer.write(chunk, 0, read);
                }
            }
            byte[] body = injectUserField(buffer.toByteArray(), userId);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            return;
        }
        if (contentLength <= 0 && !chunked) {
            return;
        }
        // 其他模块（file 等）或非 Dify 对话模块：原样流式透传
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

    /** 是否需要对请求体注入 user 的 Dify 对话模块 */
    private static boolean isDifyChatModule(String module) {
        return AiGatewayConfigResolver.MODULE_CHAT.equals(module)
                || AiGatewayConfigResolver.MODULE_KNOWLEDGE_CHAT.equals(module);
    }

    /**
     * 对话应用的元数据接口是否属于「辅助接口」。
     * <p>
     * parameters / conversations / messages/{id}/suggested / feedbacks 只做配置与会话
     * 管理，不触发模型推理、不消耗 token。把它们写进 aicalllogbean 会把看板的
     * 「调用次数」「成功率」算歪：一次正常对话会连带 2~3 条辅助记录，
     * 而 suggested 在应用未开启「推荐问题」时固定返回 400，还会被统计成失败调用。
     * <p>
     * 因此辅助接口不写审计、不计入统计；失败只走 warn 日志，排查时按 requestId 检索。
     */
    private static boolean isAuxiliaryEndpoint(String module, String endpoint) {
        if (!isDifyChatModule(module) || endpoint == null) {
            return false;
        }
        String path = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint;
        if ("parameters".equals(path) || "conversations".equals(path)
                || path.startsWith("conversations/")) {
            return true;
        }
        return path.startsWith("messages/")
                && (path.endsWith("/suggested") || path.endsWith("/feedbacks"));
    }

    private String withDifyUser(String query, String userId) throws IOException {
        List<String> parameters = new ArrayList<String>();
        if (query != null && !query.isEmpty()) {
            for (String parameter : query.split("&")) {
                int separator = parameter.indexOf('=');
                String key = separator < 0 ? parameter : parameter.substring(0, separator);
                try {
                    if (!"user".equals(URLDecoder.decode(key, StandardCharsets.UTF_8.name()))) {
                        parameters.add(parameter);
                    }
                } catch (IllegalArgumentException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询参数编码无效");
                }
            }
        }
        parameters.add("user=" + URLEncoder.encode(userId, StandardCharsets.UTF_8.name()));
        return String.join("&", parameters);
    }

    private byte[] injectUserField(byte[] body, String userId) {
        try {
            JsonNode root = body.length == 0 ? MAPPER.createObjectNode()
                    : MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (root == null || !root.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体必须是 JSON 对象");
            }
            ((ObjectNode) root).put("user", userId);
            return MAPPER.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体必须是有效的 JSON 对象");
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
    private void relayMultipart(HttpServletRequest request, HttpURLConnection conn, String userId) throws Exception {
        String boundary = "----AiGatewayBoundary"
                + UUID.randomUUID().toString().replace("-", "");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(8192);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        Collection<Part> parts = request.getParts();
        try (OutputStream out = conn.getOutputStream()) {
            for (Part part : parts) {
                if (userId != null && "user".equals(part.getName())) {
                    continue;
                }
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
            if (userId != null) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"user\"\r\n\r\n"
                        + userId + "\r\n").getBytes(StandardCharsets.UTF_8));
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
     *
     * <p>首 token 延迟按「第一行带正文的 SSE」计时：上游常见先发 role 帧、
     * usage 帧或心跳注释，按首字节计时会把准备时间算成模型延迟。</p>
     */
    /**
     * 已转发给浏览器的字节数。
     * <p>
     * 之所以是对象而不是 long：客户端中断时转发循环会直接抛异常，局部变量的累计值随栈一起没了，
     * 而计费判断需要在 catch 里读到「断开之前到底送出去多少」。
     */
    private static final class Relayed {
        private long bytes;
    }

    private void streamSse(InputStream in, OutputStream out, AiGatewayUsageParser parser,
                           Relayed relayed, long deadline, int heartbeatMs, int idleTimeoutMs)
            throws IOException {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        long lastUpstreamAt = System.currentTimeMillis();
        while (true) {
            int read;
            try {
                read = in.read(buffer);
            } catch (SocketTimeoutException poll) {
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    throw new StreamDeadlineException(streamTimeoutMessage());
                }
                // 上游没吐字不代表浏览器还在：写一帧心跳，写失败就是页面已关，
                // 异常会沿原有链路走到 catch，把审计记为 aborted 并关闭上游
                if (heartbeatMs <= 0
                        || (idleTimeoutMs > 0 && now - lastUpstreamAt >= idleTimeoutMs)) {
                    throw poll;
                }
                out.write(SSE_HEARTBEAT);
                out.flush();
                continue;
            }
            if (read == -1) {
                break;
            }
            lastUpstreamAt = System.currentTimeMillis();
            out.write(buffer, 0, read);
            out.flush();
            relayed.bytes += read;
            for (int i = 0; i < read; i++) {
                byte value = buffer[i];
                if (value == '\n') {
                    markFirstTokenIfAnswer(parser, new String(line.toByteArray(), StandardCharsets.UTF_8));
                    line.reset();
                } else if (value != '\r' && line.size() < MAX_SSE_LINE) {
                    line.write(value);
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                // 先转发并解析再判定：已经读到的内容不能丢，到点后由 catch 按预扣值结算
                throw new StreamDeadlineException(streamTimeoutMessage());
            }
        }
        if (line.size() > 0) {
            markFirstTokenIfAnswer(parser, new String(line.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private void markFirstTokenIfAnswer(AiGatewayUsageParser parser, String sseLine) {
        boolean carriesAnswer = parser.acceptSseLine(sseLine);
        if (carriesAnswer && firstTokenAt.get() == null) {
            firstTokenAt.set(System.currentTimeMillis());
        }
    }

    /**
     * 非 SSE 响应：边读边写给浏览器（不缓冲整包，保证大响应也能正常透传），
     * 同时把响应体累积到解析缓冲（上限 MAX_JSON_PARSE）用于提取 usage。
     * <p>
     * 旧实现在大响应（>2MB）时直接放弃解析 usage，导致该次调用完全不计入每日配额、
     * 可被单条大响应绕过限额。这里改为：无论响应多大，都对已读取的字节做 usage 嗅探；
     * 在 MAX_JSON_PARSE 以内的响应可精确解析，超出则对头部 best-effort（仍优于完全漏记）。
     */
    private void relayBody(InputStream in, OutputStream out, AiGatewayUsageParser parser,
                           Relayed relayed, long deadline) throws IOException {
        ByteArrayOutputStream parseBuf = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        boolean parseSpilled = false;
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
            out.flush();
            relayed.bytes += read;
            if (!parseSpilled) {
                if (parseBuf.size() + read > MAX_JSON_PARSE) {
                    parseSpilled = true;
                } else {
                    parseBuf.write(chunk, 0, read);
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                // 非流式响应同样要有总时长上限：一个持续缓慢吐字节的响应
                // 能把 Web 线程和预扣额度占用到天荒地老
                throw new StreamDeadlineException(streamTimeoutMessage());
            }
        }
        parser.acceptJson(new String(parseBuf.toByteArray(), StandardCharsets.UTF_8));
    }

    /** 总时长护栏的绝对截止时刻；未配置上限时退化为「不限制」 */
    private long streamDeadline(long start) {
        int maxStreamMs = properties.getMaxStreamMs();
        return maxStreamMs > 0 ? start + maxStreamMs : Long.MAX_VALUE;
    }

    private String streamTimeoutMessage() {
        long seconds = Math.max(1L, properties.getMaxStreamMs() / 1000L);
        return "AI 响应超过总时长限制（" + seconds + " 秒），已断开";
    }

    /**
     * 已提交的 SSE 响应无法再改状态码，补一帧协议对应的 error 事件。
     * Dify 与 OpenAI 兼容端点的事件形状不同，按 module 区分。
     */
    private void appendSseError(HttpServletResponse response, String module, String message) {
        try {
            String payload = ModelGatewayResolver.MODULE_MODELS.equals(module)
                    ? "{\"error\":{\"message\":\"" + escapeQuoted(message) + "\"}}"
                    : "{\"event\":\"error\",\"message\":\"" + escapeQuoted(message) + "\"}";
            OutputStream out = response.getOutputStream();
            out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
            // 客户端可能已经断开，补发失败不影响审计与结算
        }
    }

    /** 流式/透传响应超过总时长上限；单独成类是为了在 catch 里和普通 IO 异常区分 */
    private static final class StreamDeadlineException extends IOException {
        StreamDeadlineException(String message) {
            super(message);
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
        if (isAuxiliaryEndpoint(module, endpoint)) {
            if (!"success".equals(status)) {
                log.warn("[AiGateway] 辅助接口调用失败(未计入审计): uid={}, endpoint={}, status={}, requestId={}, error={}",
                        userId, endpoint, status, currentRequestId.get(), error);
            }
            return;
        }
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
     * 折算本次调用的成本（单位：毫分，1 分 = 1000 毫分）。
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

    /**
     * 拦截类响应的状态码：额度/频率超限是 429，治理依赖本身不可用是 503。
     * 两者都要限流语义的话前端会分不清「我该等一会儿」和「管理员得去看看 Redis」。
     */
    private static int blockedHttpStatus(String code) {
        return AiGatewayGuard.CODE_GUARD_UNAVAILABLE.equals(code)
                ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
                : 429;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) {
        writeError(response, status, code, message, null);
    }

    private void writeError(HttpServletResponse response, int status, String code, String message,
                            Map<String, String> extraHeaders) {
        try {
            if (response.isCommitted()) {
                return;
            }
            response.reset();
            response.setStatus(status);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            // reset 会清掉之前设置的头，所以附加头必须在 reset 之后写
            if (extraHeaders != null) {
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    response.setHeader(header.getKey(), header.getValue());
                }
            }
            // 错误响应也带上请求 ID：用户报障时把这个 ID 给管理员，
            // 管理员能直接捞出完整链路，不用靠「大概几点」猜
            String rid = currentRequestId.get();
            if (rid != null) {
                response.setHeader(REQUEST_ID_HEADER, rid);
            }
            Object body = Boolean.TRUE.equals(openAiErrorShape.get())
                    ? openAiErrorBody(code, message, status, rid)
                    : platformErrorBody(code, message, status, rid);
            response.getOutputStream().write(
                    MAPPER.writeValueAsBytes(body));
            response.flushBuffer();
        } catch (Exception e) {
            log.warn("[AiGateway] 写错误响应失败: {}", e.getMessage());
        }
    }

    /** 站内网关的错误信封：前端 axios 按 code 分支 */
    private static Map<String, Object> platformErrorBody(String code, String message,
                                                         int status, String rid) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("message", message);
        body.put("status", status);
        body.put("requestId", rid);
        return body;
    }

    /**
     * OpenAI 协议的错误信封。
     * <p>
     * {@code error.code} 保留我们的机器码（OpenAI 允许自定义字符串），
     * {@code error.type} 按协议分类，SDK 靠它选异常类型；
     * 再额外带上 {@code error.request_id}，用户报障时不用猜时间。
     */
    private static ObjectNode openAiErrorBody(String code, String message, int status, String rid) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("message", message);
        error.put("type", openAiErrorType(code, status));
        error.put("code", code);
        error.put("request_id", rid);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("error", error);
        return root;
    }

    /** 我们的错误码 → OpenAI 的 error.type */
    private static String openAiErrorType(String code, int status) {
        if ("invalid_api_key".equals(code) || "unauthorized".equals(code)) {
            return "authentication_error";
        }
        if ("model_not_allowed".equals(code) || "access_denied".equals(code)) {
            return "permission_error";
        }
        if ("rate_limited".equals(code) || "quota_exceeded".equals(code)) {
            return "rate_limit_exceeded";
        }
        if ("upstream_error".equals(code) || "gateway_disabled".equals(code)
                || AiGatewayGuard.CODE_GUARD_UNAVAILABLE.equals(code)) {
            return "api_error";
        }
        // model_not_found / invalid_request / payload_too_large / 未知码：都归为请求本身的问题
        return "invalid_request_error";
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
