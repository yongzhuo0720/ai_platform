package com.aiproject.ai.service.controller;

import cn.hutool.json.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.croe.model.Result;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import blxt.qjava.utils.check.CheckUtils;
import com.heneng.hnsp.common.inter.ControllerLog;
import com.heneng.hnsp.common.inter.LoginUserId;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.UnsupportedEncodingException;
import java.util.UUID;
import java.lang.String;
import java.net.URLEncoder;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.model.CodeEnum;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.aiproject.ai.service.bean.SsoSessionStatusResp;
import com.aiproject.ai.service.bean.SsoTokenBean;
import com.aiproject.ai.service.config.SsoProperties;
import com.aiproject.ai.service.holde.SsoTokenBeanHolder;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/08/11 14:24:30
 */
@RequestMapping("/api/ssoTokenBean")
@Slf4j
@RestController
@Api(value = "SSO 单点登录", tags = "SSO 单点登录")
public class SsoTokenBeanController {
    /**
     * 数据操作执行器.
     */
    @Autowired
    SsoTokenBeanHolder ssoTokenBeanHolder = null;

    /**
     * SSO 配置.
     */
    @Autowired
    SsoProperties ssoProperties = null;

    /**
     * 用于 SSO state 的一次性 nonce 校验（2026-11-19 安全加固）。
     * 强制依赖：SSO 登录态本身存 Redis，Redis 不可用时用户本就没有登录态、
     * 走不到授权入口；callback 阶段也按 fail-closed 直接拒绝，不放过重放风险。
     */
    @Autowired
    StringRedisTemplate stringRedisTemplate = null;

    /** state 有效期 10 分钟，与授权码的短时效匹配 */
    private static final long STATE_TTL_SECONDS = 600L;
    /** state nonce 的 Redis key 前缀 */
    private static final String STATE_KEY_PREFIX = "sso:state:";

    // ==================== SSO 单点登录接口 ====================

    /**
     * 检查当前用户的 SSO 会话状态.
     * <p>
     * 前端点击"查看"按钮时调用.
     * - 有有效会话 → 返回 hasSession=true
     * - 无有效会话 → 返回 hasSession=false + authUrl，前端将浏览器重定向到 SSO 登录页
     *
     * @param uid 当前登录用户 ID
     * @return {hasSession: boolean, authUrl?: string}
     */
    @ApiOperation(value = "检查 SSO 会话状态", httpMethod = "GET", notes = "检查当前用户是否有有效的 SSO 会话，若无则返回授权登录地址")
    @ControllerLog(value = "检查 SSO 会话状态", user = "uid", target = "")
    @GetMapping("/status")
    public Result<SsoSessionStatusResp> status(@LoginUserId String uid) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");

        SsoSessionStatusResp result = new SsoSessionStatusResp();

        // SSO 未启用，直接返回有会话
        if (!ssoProperties.isEnabled()) {
            result.setHasSession(true);
            return Result.succeed(result);
        }

        // 检查是否已有有效 SSO Token
        if (ssoTokenBeanHolder.hasValidSession(uid)) {
            log.info("当前用户token有效");
            result.setHasSession(true);
            return Result.succeed(result);
        }

        // 构造 SSO 授权登录地址（state 即 uid，callback 时解析）
        result.setHasSession(false);
        //构造 SSO 授权登录 URL
        String url = buildSsoAuthUrl(uid);
        log.info("构造 SSO 授权登录 URL:{}", url);
        result.setAuthUrl(url);
        return Result.succeed(result);
    }

    /**
     * SSO 平台授权回调（无需登录态，uid 从 state 参数解析）.
     * <p>
     * 用户在 SSO 平台完成授权登录后，SSO 平台 302 重定向到该地址：
     * GET /api/ssoTokenBean/callback?code=8f2bcad1f2ac&state=uid
     * <p>
     * 服务端用授权码换取 Token 并存储到数据库.
     *
     * @param code  授权码（SSO 平台回调携带）
     * @param state 状态参数，即 uid（SSO 平台回调原样返回）
     * @return 操作结果
     */
    @ApiOperation(value = "SSO 授权回调", httpMethod = "GET", notes = "SSO 平台授权登录后的回调地址，用授权码换取 Token")
    @GetMapping("/callback")
    public Result<Boolean> callback(@ApiParam(value = "授权码", required = true) @RequestParam String code,
                                    @ApiParam(value = "状态参数") @RequestParam(required = false) String state) {
        // 从 state 解析 uid 并校验签名 + 时间窗口；不通过一律按无效处理
        String uid = parseUidFromState(state);
        if (uid == null || uid.isEmpty()) {
            return Result.failed(false, "state 参数无效或校验不通过，无法识别用户");
        }

        // 2026-11-19 安全加固：nonce 一次性消费，防重放。
        // delete(key) 返回 Boolean：true 表示存在并删除（首次消费），false/null 表示已用过或已过期。
        // state 已通过签名校验，其中的 nonce 可信；Redis 不可用（异常或 null）时按失败处理。
        String nonce = parseNonceFromState(state);
        String stateKey = STATE_KEY_PREFIX + uid + ":" + nonce;
        Boolean removed;
        try {
            removed = stringRedisTemplate.delete(stateKey);
        } catch (Exception e) {
            log.warn("[SSO] state nonce 校验时 Redis 异常，按失败处理, uid={}: {}", uid, e.getMessage());
            return Result.failed(false, "授权校验暂不可用，请重试");
        }
        if (!Boolean.TRUE.equals(removed)) {
            log.warn("[SSO] 拒绝重放或过期的 state, uid={}", uid);
            return Result.failed(false, "授权请求已过期或已使用，请重新发起登录");
        }

        // 注意不要打印 code：授权码是一次性凭据，进日志等于留痕
        log.info("SSO 回调, uid={}", uid);

        try {
            // 1. 用授权码换取 Token
            JSONObject tokenData = ssoTokenBeanHolder.exchangeCodeForToken(code);

            // 2. 校验 Token 里的用户标识与 state 的 uid 一致（防 Token 错绑）
            //    若 IdP 未返回可识别的用户标识字段，则跳过此项（由 code↔state 绑定兜底）
            String tokenUser = extractTokenUser(tokenData);
            if (tokenUser != null && !tokenUser.isEmpty() && !uid.equals(tokenUser)) {
                log.warn("[SSO] Token 用户标识与 state 不一致，拒绝错绑, stateUid={}, tokenUser={}", uid, tokenUser);
                return Result.failed(false, "授权用户信息不匹配");
            }

            // 3. 缓存 Token 到数据库
            ssoTokenBeanHolder.cacheToken(uid, tokenData);
            log.info("SSO Token 存储成功, uid={}", uid);
        } catch (Exception e) {
            // 这个地址是 SSO 平台 302 回来的，异常直接冒泡会变成 500 错误页，
            // 用户看不到任何有效信息。这里兜住并返回明确结果。
            log.error("SSO 回调处理失败, uid={}", uid, e);
            return Result.failed(false, "授权失败，请重试");
        }

        return Result.succeed(true, "授权成功");
    }

    /**
     * 构造 SSO 授权登录 URL.
     *
     * @param uid 当前登录用户 ID
     * @return SSO 授权页完整 URL
     */
    private String buildSsoAuthUrl(String uid) {
        // 授权端点未就绪时给明确错误，而不是让后面 new StringBuilder(null) 抛 NPE
        if (ssoProperties.getAuthorizationEndpoint() == null
                || ssoProperties.getAuthorizationEndpoint().isEmpty()) {
            throw new RuntimeException("SSO 授权端点未配置（OpenID 发现可能失败），无法构造登录地址");
        }

        StringBuilder url = new StringBuilder(ssoProperties.getAuthorizationEndpoint());
        url.append("?response_type=code");
        try {
            url.append("&client_id=").append(URLEncoder.encode(ssoProperties.getClientId(), "UTF-8"));
            url.append("&redirect_uri=").append(URLEncoder.encode(ssoProperties.getCallbackUrl(), "UTF-8"));
            url.append("&scope=openid");
            // state 带上签名，回调时校验，防止伪造 uid
            url.append("&state=").append(URLEncoder.encode(buildSignedState(uid), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }

        return url.toString();
    }

    /**
     * 构造带签名的一次性 state：{uid}.{timestampMs}.{nonce}.{hmac}
     * <p>
     * 演进：
     * <ul>
     *   <li>最初 state = 裸 uid，可任意伪造 → 改为 HMAC 签名防伪造；</li>
     *   <li>但 {uid}.{HMAC(uid)} 是确定性的、永不过期，截获后可无限重放 →
     *       2026-11-19 追加时间戳（10 分钟窗口）+ 随机 nonce（Redis 一次性消费）。</li>
     * </ul>
     * 签名覆盖全部三段（uid+ts+nonce），任何一段被改都验签失败。
     * 签名密钥复用 clientSecret（每部署不同、敏感配置，不随前端暴露）。
     * <p>
     * 包级可见是为了让同包测试类直接验证签名逻辑（见 SsoStateSigningTest）。
     */
    String buildSignedState(String uid) {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = uid + "." + ts + "." + nonce;
        String signed = payload + "." + signState(payload);
        // 把 nonce 存入 Redis 供 callback 一次性消费；TTL 与 state 时间窗口一致
        try {
            stringRedisTemplate.opsForValue().set(
                    STATE_KEY_PREFIX + uid + ":" + nonce, "1",
                    java.time.Duration.ofSeconds(STATE_TTL_SECONDS));
        } catch (Exception e) {
            // fail-closed：nonce 存不进去，callback 必然拒绝，等于这次授权流程走不通。
            // 抛异常让 status 接口返回明确错误，而不是发一个注定失败的授权 URL。
            throw new RuntimeException("SSO state 暂存失败，请检查 Redis 可用性", e);
        }
        return signed;
    }

    /** 对 payload 做 HmacSHA256 签名，返回 URL 安全的 Base64（无 padding） */
    String signState(String payload) {
        String secret = ssoProperties.getClientSecret();
        if (secret == null || secret.isEmpty()) {
            throw new RuntimeException("SSO clientSecret 未配置，无法签名 state");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("state 签名失败", e);
        }
    }

    /**
     * 从 state 解析 uid：验签 + 时间窗口校验。
     * <p>
     * state 形如 {uid}.{timestampMs}.{nonce}.{hmac}（hmac 无 '.'，故从后往前定位三段）。
     * 校验不通过一律返回 null（按无效处理），不会把不可信的输入当 uid 用。
     */
    String parseUidFromState(String state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        // 从后往前切：最后一段是签名，倒数第二段是 nonce，倒数第三段是时间戳，剩下是 uid
        int sigSep = state.lastIndexOf('.');
        if (sigSep <= 0) {
            return null;
        }
        String sig = state.substring(sigSep + 1);
        String rest = state.substring(0, sigSep);
        int nonceSep = rest.lastIndexOf('.');
        if (nonceSep <= 0) {
            return null;
        }
        String nonce = rest.substring(nonceSep + 1);
        String beforeNonce = rest.substring(0, nonceSep);
        int tsSep = beforeNonce.lastIndexOf('.');
        if (tsSep <= 0) {
            return null;
        }
        String uid = beforeNonce.substring(0, tsSep);
        String ts = beforeNonce.substring(tsSep + 1);
        if (uid.isEmpty() || ts.isEmpty() || nonce.isEmpty()) {
            return null;
        }
        // 时间窗口：过期或时间戳非法都拒绝（防重放的第一道）
        long issuedAt;
        try {
            issuedAt = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (issuedAt > now + 60_000L || now - issuedAt > STATE_TTL_SECONDS * 1000L) {
            return null;
        }
        // 验签覆盖 uid+ts+nonce 三段
        String payload = uid + "." + ts + "." + nonce;
        String expect = signState(payload);
        // 用常量时间比较，避免时序侧信道
        return MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8)) ? uid : null;
    }

    /** 从已验签的 state 中提取 nonce（供 callback 做一次性消费） */
    String parseNonceFromState(String state) {
        if (state == null) {
            return null;
        }
        int sigSep = state.lastIndexOf('.');
        if (sigSep <= 0) {
            return null;
        }
        String rest = state.substring(0, sigSep);
        int nonceSep = rest.lastIndexOf('.');
        if (nonceSep <= 0 || nonceSep == rest.length() - 1) {
            return null;
        }
        return rest.substring(nonceSep + 1);
    }

    /**
     * 从 Token 响应里提取用户标识，用于校验"这个 token 确实属于 state 里的 uid"。
     * 兼容常见 OIDC 字段；IdP 未返回任何可识别字段时返回 null（跳过此项校验，由 code↔state 绑定兜底）。
     */
    private String extractTokenUser(JSONObject tokenData) {
        if (tokenData == null) {
            return null;
        }
        String[] keys = {"sub", "user_id", "userId", "uid", "preferred_username"};
        for (String key : keys) {
            String v = tokenData.getStr(key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        // id_token 是 JWT，payload 里的 sub 才可信；这里做轻量提取，不做签名校验
        // （token 本身来自可信的 tokenEndpoint HTTPS 响应，签名校验由 IdP 侧完成）
        String idToken = tokenData.getStr("id_token");
        if (idToken != null && idToken.contains(".")) {
            try {
                String payload = idToken.split("\\.")[1];
                String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
                JSONObject claims = new JSONObject(json);
                return claims.getStr("sub");
            } catch (Exception e) {
                log.debug("[SSO] id_token payload 解析失败，跳过用户标识校验: {}", e.getMessage());
            }
        }
        return null;
    }

}
