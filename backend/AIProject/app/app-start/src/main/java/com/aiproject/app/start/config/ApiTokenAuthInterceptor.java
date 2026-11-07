package com.aiproject.app.start.config;

import com.aiproject.ai.service.bean.AiApiTokenBean;
import com.aiproject.ai.service.holde.AiApiTokenHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * OpenAI 兼容端点（{@code /v1/**}）的令牌门禁。
 *
 * <p><b>为什么单独一个拦截器。</b>站内接口靠 {@link LoginAuthInterceptor} 认浏览器登录态
 * （Sa-Token），而 {@code /v1/**} 的调用方是外部程序：脚本、IDE 插件、OpenAI SDK。
 * 它们没有 Sa-Token，只带一个 {@code Authorization: Bearer sk-…}。
 * 这条前缀也不在 {@code WebConfig.PROTECTED_PATTERNS} 里，
 * 所以必须由本拦截器自己把门——不能靠 Controller 记得判空。</p>
 *
 * <p><b>fail-closed。</b>令牌缺失、格式不对、校验异常、Holder 装配不上，一律拒绝。
 * 特别是后两种：鉴权代码出错时如果放行，等于把网关和用户的额度大门敞开，
 * 而日志里只会留下一条看不懂的异常。</p>
 *
 * <p><b>响应形状与站内不同。</b>全站约定「业务失败也返回 HTTP 200 + Result 信封」，
 * 但 OpenAI SDK 按 HTTP 状态码决定是否抛异常，返回 200 会被它当成成功解析并报错。
 * 因此这里给真实的 401/503 和 {@code {"error":{...}}}，
 * 与 {@code AiGatewayService} 在 v1 链路上的错误形状保持一致。</p>
 */
@Component
public class ApiTokenAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<AiApiTokenHolder> tokenHolderProvider;

    public ApiTokenAuthInterceptor(ObjectProvider<AiApiTokenHolder> tokenHolderProvider) {
        // 用 ObjectProvider 延迟取：WebMvcConfigurer 的装配时机早于普通业务 Bean，
        // 直接构造注入会把 Holder 及其 Mapper 依赖链提前拉起来。
        this.tokenHolderProvider = tokenHolderProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 异步派发只是转发结束后的收尾，不会再次执行 Controller；
        // 初始请求已经验过密钥，这里跳过可避免长流结束后再写一个 401。
        if (request.getDispatcherType() == javax.servlet.DispatcherType.ASYNC) {
            return true;
        }
        // CORS 预检不带自定义头，也不该被鉴权拦下
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String plaintext = bearerToken(request);
        if (plaintext == null) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_api_key",
                    "authentication_error",
                    "缺少或格式错误的 Authorization 头，需要 Bearer <平台 API 密钥>");
            return false;
        }
        AiApiTokenHolder holder = tokenHolderProvider.getIfAvailable();
        if (holder == null) {
            log.error("[ApiTokenAuth] 容器中没有 AiApiTokenHolder，按未认证处理");
            reject(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "service_unavailable", "api_error", "API 密钥校验服务不可用，请联系管理员");
            return false;
        }
        AiApiTokenBean token;
        try {
            token = holder.verify(plaintext);
        } catch (Exception e) {
            log.warn("[ApiTokenAuth] 校验令牌异常: {}", e.toString());
            token = null;
        }
        if (token == null) {
            // 不区分「不存在 / 已停用 / 已过期」，具体原因只在 Holder 的日志里
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_api_key",
                    "authentication_error", "API 密钥无效、已停用或已过期");
            return false;
        }
        request.setAttribute(AiApiTokenHolder.ATTR_TOKEN, token);
        request.setAttribute(AiApiTokenHolder.ATTR_UID, token.getUserId());
        return true;
    }

    /** 取 Bearer 令牌；前缀大小写按 RFC 7235 不敏感，令牌本身敏感所以只 trim 外层空白 */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.length() <= BEARER_PREFIX.length()
                || !trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        int status, String code, String type, String message) throws IOException {
        String requestId = UUID.randomUUID().toString();
        log.warn("[ApiTokenAuth] 拒绝请求, path={}, status={}, code={}, requestId={}",
                request.getRequestURI(), status, code, requestId);
        ObjectNode error = MAPPER.createObjectNode();
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        error.put("request_id", requestId);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("error", error);

        response.setStatus(status);
        response.setHeader("X-Request-Id", requestId);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        MAPPER.writeValue(response.getWriter(), root);
        response.flushBuffer();
    }
}
