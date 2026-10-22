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

import java.io.UnsupportedEncodingException;
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
        // 从 state 解析 uid 并校验签名；不通过一律按无效处理
        String uid = parseUidFromState(state);
        if (uid == null || uid.isEmpty()) {
            return Result.failed(false, "state 参数无效或校验不通过，无法识别用户");
        }

        // 注意不要打印 code：授权码是一次性凭据，进日志等于留痕
        log.info("SSO 回调, uid={}", uid);

        try {
            // 1. 用授权码换取 Token
            JSONObject tokenData = ssoTokenBeanHolder.exchangeCodeForToken(code);

            // 2. 缓存 Token 到数据库
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
     * 构造带签名的 state：{uid}.{hmac}
     * <p>
     * 修复前 state 就是裸 uid，回调时原样当 uid 用。攻击者拿自己的 code 配上受害者的 uid
     * 请求一次回调，就能把令牌写到受害者账号上（Token 错绑 / 会话固定）。
     * 签名密钥复用 clientSecret —— 它每个部署不同、且本来就是敏感配置，不会随前端暴露。
     * <p>
     * 包级可见是为了让同包测试类直接验证签名逻辑（见 SsoStateSigningTest）。
     */
    String buildSignedState(String uid) {
        return uid + "." + signState(uid);
    }

    /** 对 uid 做 HmacSHA256 签名，返回 URL 安全的 Base64（无 padding） */
    String signState(String uid) {
        String secret = ssoProperties.getClientSecret();
        if (secret == null || secret.isEmpty()) {
            throw new RuntimeException("SSO clientSecret 未配置，无法签名 state");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(uid.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("state 签名失败", e);
        }
    }

    /**
     * 从 state 参数解析出 uid，并校验签名。
     * <p>
     * 校验不通过一律返回 null（按无效处理），不会把不可信的输入当 uid 用。
     *
     * @param state 形如 {uid}.{hmac}
     * @return 校验通过的 uid；不通过返回 null
     * <p>
     * 包级可见，理由同 buildSignedState。
     */
    String parseUidFromState(String state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        int sep = state.lastIndexOf('.');
        if (sep <= 0 || sep == state.length() - 1) {
            return null;
        }
        String uid = state.substring(0, sep);
        String sig = state.substring(sep + 1);
        String expect = signState(uid);
        // 用常量时间比较，避免时序侧信道
        return MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8)) ? uid : null;
    }

}
