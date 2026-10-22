package com.aiproject.ai.service.config;

import cn.hutool.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSO OIDC 配置属性读取 + 启动时 OpenID 自动发现
 *
 * @author
 * @date 2026/08/11
 */
@Slf4j
@Component
public class SsoProperties {

    private final OkHttpClient httpClient = new OkHttpClient();

    // ============ 从配置文件读取 ============

    /**
     * SSO 开关：true=启用，false=停用
     */
    @Value("${sso.enabled}")
    @Getter
    private boolean enabled;

    /**
     * SSO 平台 OIDC 服务地址（Issuer URI），用于 OpenID 自动发现
     */
    @Value("${sso.issuer-uri}")
    private String issuerUri;

    /**
     * 在 SSO 平台注册应用后分配的应用标识
     */
    @Value("${sso.client-id}")
    @Getter
    private String clientId;

    /**
     * 在 SSO 平台注册应用后分配的应用密钥
     */
    @Value("${sso.client-secret}")
    @Getter
    private String clientSecret;

    /**
     * SSO 授权回调地址（本系统地址），需与 SSO 平台注册的 Redirect Uri 完全一致
     */
    @Value("${sso.callback-url}")
    @Getter
    private String callbackUrl;

    // ============ 从 OpenID 发现端点获取并缓存 ============

    /**
     * 认证授权入口地址，例如 /oauth/login
     */
    @Getter
    private String authorizationEndpoint;

    /**
     * Token 兑换地址，例如 /oauth/code/token
     */
    @Getter
    private String tokenEndpoint;

    /**
     * JWT 验证公钥地址，例如 /oauth/jwks
     */
    @Getter
    private String jwksUri;

    /**
     * 发现端点返回的全部元数据
     */
    private JSONObject discoveryMetadata;

    // ============ 启动时调用发现端点 ============

    /**
     * 注意：此处的 @PostConstruct 曾被注释掉，导致 init() 永不执行，
     * authorizationEndpoint / tokenEndpoint 始终为 null。
     * 后果：一旦 sso.enabled=true，调用 /api/ssoTokenBean/status 时
     * buildSsoAuthUrl() 会 new StringBuilder(null) 抛 NPE，
     * exchangeCodeForToken() 也会 OkHttp url(null) 抛 NPE，接口直接 500。
     * 现恢复注解；发现失败时仅记录日志，不阻断启动。
     */
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("SSO 未启用，跳过 OpenID 自动发现");
            return;
        }
        if (issuerUri == null || issuerUri.isEmpty()) {
            log.warn("SSO 已启用但 issuer-uri 未配置，跳过 OpenID 自动发现");
            return;
        }

        String discoveryUrl = issuerUri + "/.well-known/openid-configuration";
        log.info("SSO OpenID 自动发现: {}", discoveryUrl);

        try {
            Request request = new Request.Builder()
                    .url(discoveryUrl)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : null;
                log.info("返回结果: {}", body);
                if (body == null) {
                    log.error("OpenID 发现端点返回为空: {}", discoveryUrl);
                    return;
                }
                JSONObject resultJson = new JSONObject(body);

                this.discoveryMetadata = resultJson;

                // 提取关键端点
                this.authorizationEndpoint = resultJson.getStr("authorization_endpoint");
                this.tokenEndpoint = resultJson.getStr("token_endpoint");
                this.jwksUri = resultJson.getStr("jwks_uri");

                log.info("SSO OpenID 发现完成:");
                log.info("  authorization_endpoint = {}", authorizationEndpoint);
                log.info("  token_endpoint         = {}", tokenEndpoint);
                log.info("  jwks_uri               = {}", jwksUri);

                // 校验必要端点是否存在
                if (authorizationEndpoint == null || tokenEndpoint == null) {
                    log.error("OpenID 发现端点缺少必要字段，请检查 issuer-uri 是否正确");
                }
            }
        } catch (Exception e) {
            log.error("OpenID 自动发现失败: {}，错误: {}", discoveryUrl, e.getMessage(), e);
        }
    }

}
