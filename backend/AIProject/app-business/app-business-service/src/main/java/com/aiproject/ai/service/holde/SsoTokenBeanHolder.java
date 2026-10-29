package com.aiproject.ai.service.holde;

import blxt.qjava.utils.check.CheckUtils;
import cn.hutool.json.JSONObject;
import com.aiproject.ai.service.bean.SsoTokenBean;
import com.aiproject.ai.service.config.SsoProperties;
import com.aiproject.ai.service.mapper.SsoTokenBeanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/08/11 14:24:30
 */
@Service
@Slf4j
public class SsoTokenBeanHolder {
    /**
     * 数据库Mapper.
     */
    @Autowired
    SsoTokenBeanMapper<SsoTokenBean> ssoTokenBeanMapper = null;

    @Autowired
    private SsoProperties ssoProperties;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 功能描述: 增加.
     *
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/08/11 14:24:30
     **/

    public Result<Boolean> install(SsoTokenBean data) {

        if (ssoTokenBeanMapper.install(data) <= 0) {
            return Result.failed(false, "增加失败");
        }
        return Result.succeed(true, "增加成功");
    }

    /**
     * 功能描述: 删除.
     *
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/08/11 14:24:30
     **/

    public Result<Boolean> delete(SsoTokenBean data) {

        if (ssoTokenBeanMapper.delete(data) <= 0) {
            return Result.failed(false, "删除失败");
        }
        return Result.succeed(true, "删除成功");
    }

    /**
     * 功能描述: 修改.
     *
     * @param uid   用户id
     * @param data  数据
     * @param where 条件
     * @return Boolean
     * @author heneng
     * @date 2026/08/11 14:24:30
     **/

    public Result<Boolean> update(String uid, SsoTokenBean data, SsoTokenBean where) {

        data.setLastuser(uid);
        if (ssoTokenBeanMapper.update(data, where) <= 0) {
            return Result.failed(false, "修改失败");
        }
        return Result.succeed(true, "修改成功");
    }

    /**
     * 功能描述: 查询单个.
     *
     * @param data 数据
     * @return 对象
     * @author heneng
     * @date 2026/08/11 14:24:30
     **/

    public SsoTokenBean query(SsoTokenBean data) {

        List<SsoTokenBean> list = ssoTokenBeanMapper.select(null, data);
        if (list == null || list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

    /**
     * 功能描述: 查询集合.
     *
     * @param data        数据
     * @param pageRequest 分页条件
     * @return List
     * @author heneng
     * @date 2026/08/11 14:24:30
     **/

    public List<SsoTokenBean> queryList(SsoTokenBean data, PageRequest pageRequest) {

        return ssoTokenBeanMapper.select(pageRequest, data);
    }

    /**
     * 功能描述: 分页查询.
     *
     * @param data        数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/08/11 14:24:30
     **/

    public Result<PageResult<SsoTokenBean>> queryPage(SsoTokenBean data, PageRequest pageRequest) {

        return ssoTokenBeanMapper.selectPageLike(pageRequest, data);
    }

    // ==================== SSO 业务方法 ====================

    /**
     * 根据用户 uid 查询 SSO Token 记录
     */
    public SsoTokenBean queryByUid(String uid) {
        SsoTokenBean probe = new SsoTokenBean();
        probe.setUid(uid);
        return query(probe);
    }

    /**
     * 保存或更新 SSO Token（按 uid 唯一）
     *
     * @param uid          用户 uid
     * @param accessToken  SSO 访问令牌
     * @param refreshToken SSO 刷新令牌
     * @param expiresAt    access_token 过期时间戳（毫秒）
     */
    public void saveOrUpdate(String uid, String accessToken, String refreshToken, Long expiresAt) {
        SsoTokenBean existing = queryByUid(uid);
        if (existing != null) {
            /*
             * refresh_token 轮换保护。
             *
             * OAuth 2.0 的 refresh_token 轮换不是强制的：很多 IdP 在续期响应里
             * 只回新的 access_token，不重新发 refresh_token。修复前这里无条件
             * setRefresh_token(refreshToken)，新值为 null 时会把库里原有的
             * refresh_token 覆盖成 null——下次 access_token 过期就再也续不了，
             * 用户只能重新走一遍 SSO 授权。
             *
             * 现在：新值为空时保留原值。
             */
            String effectiveRefresh = (refreshToken == null || refreshToken.trim().isEmpty())
                    ? existing.getRefresh_token() : refreshToken;

            SsoTokenBean data = new SsoTokenBean();
            data.setAccess_token(accessToken);
            data.setRefresh_token(effectiveRefresh);
            data.setExpires_at(expiresAt);

            SsoTokenBean where = new SsoTokenBean();
            where.setIid(existing.getIid());
            ssoTokenBeanMapper.update(data, where);
            log.info("SSO Token 已更新, uid={}", uid);
        } else {
            SsoTokenBean data = new SsoTokenBean();
            data.setUid(uid);
            data.setAccess_token(accessToken);
            data.setRefresh_token(refreshToken);
            data.setExpires_at(expiresAt);
            ssoTokenBeanMapper.install(data);
            log.info("SSO Token 已新增, uid={}", uid);
        }
    }

    /**
     * 判断当前用户是否有有效的 SSO 会话
     * <p>
     * 1. 无记录 → 无会话
     * 2. access_token 未过期 → 有会话
     * 3. access_token 已过期但有 refresh_token → 尝试续期
     * 4. 续期也失败 → 无会话
     */
    public boolean hasValidSession(String uid) {
        SsoTokenBean record = queryByUid(uid);
        if (record == null) {
            return false;
        }

        // access_token 未过期
        if (!isExpired(record.getExpires_at())) {
            return true;
        }

        // 已过期，尝试用 refresh_token 续期
        if (record.getRefresh_token() != null && !record.getRefresh_token().isEmpty()) {
            return refreshAccessToken(uid, record.getRefresh_token());
        }

        return false;
    }

    /**
     * 获取有效的 access_token（自动续期）
     *
     * @return access_token，无有效会话时返回 null
     */
    public String getValidAccessToken(String uid) {
        if (!hasValidSession(uid)) {
            return null;
        }
        SsoTokenBean record = queryByUid(uid);
        return record != null ? record.getAccess_token() : null;
    }

    /**
     * 用授权码换取 Token（供 Controller 调用）
     * <p>
     * 请求格式: application/x-www-form-urlencoded
     * <p>
     * 授权码来源: 用户授权登录后，SSO 平台 302 重定向到 callback 地址时附带 code 参数:
     * HTTP/1.1 302 Found
     * Location: https://example.com/callback?code=8f2bcad1f2ac&state=abc123
     * <p>
     * SSO 返回平铺格式:
     * {"access_token":"...","expires_in":1731999999000,"id_token":"...","token_type":"Bearer","refresh_token":"...","scope":["openid"]}
     */
    public JSONObject exchangeCodeForToken(String code) {
        String tokenUrl = ssoProperties.getTokenEndpoint();
        log.info("用授权码换取 Token, url={}", tokenUrl);

        FormBody body = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", ssoProperties.getCallbackUrl())
                .add("client_id", ssoProperties.getClientId())
                .add("client_secret", ssoProperties.getClientSecret())
                .build();

        Request request = new Request.Builder()
                .url(tokenUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : null;
            if (respBody == null) {
                throw new RuntimeException("换取 Token 响应为空");
            }
            JSONObject resultJson = new JSONObject(respBody);
            if (CheckUtils.isEmpty(resultJson.getStr("access_token"))) {
                throw new RuntimeException("换取 Token 失败，响应中无 access_token");
            }
            return resultJson;
        } catch (IOException e) {
            throw new RuntimeException("换取 Token 请求失败", e);
        }
    }

    /**
     * 返回给 Controller 的 SSO 缓存结果
     */
    public void cacheToken(String uid, JSONObject tokenData) {
        String accessToken = tokenData.getStr("access_token");
        String refreshToken = tokenData.getStr("refresh_token");

        // 优先取过期时间戳 expires_at（毫秒）。
        // 退而求其次用 expires_in：OIDC 标准里 expires_in 是「相对秒数」，
        // 但部分平台实现会直接返回毫秒时间戳，两种口径都要兼容，
        // 否则取值被当成 1970 年的时间戳，Token 会被判定为立即过期而反复续期。
        Object expireField = tokenData.get("expires_at") != null
                ? tokenData.get("expires_at")
                : tokenData.get("expires_in");
        Long expiresAt = parseExpireValue(expireField);

        saveOrUpdate(uid, accessToken, refreshToken, expiresAt);
    }

    /**
     * 解析过期时间字段，同时兼容两种口径：
     * - 绝对过期时间戳（毫秒），如 1763633925835
     * - 相对有效期（秒），OIDC 标准语义，如 3600
     * 小于该阈值的数值一律视为相对秒数（阈值约为 2001 年的毫秒时间戳）。
     */
    private static final long TIMESTAMP_THRESHOLD = 1000000000000L;

    private Long parseExpireValue(Object obj) {
        if (obj == null) {
            return System.currentTimeMillis() + 30 * 60 * 1000L;
        }
        long value;
        if (obj instanceof Number) {
            value = ((Number) obj).longValue();
        } else {
            try {
                value = Long.parseLong(obj.toString());
            } catch (NumberFormatException e) {
                log.warn("无法解析过期时间字段: {}", obj);
                return System.currentTimeMillis() + 30 * 60 * 1000L;
            }
        }
        if (value <= 0) {
            return System.currentTimeMillis() + 30 * 60 * 1000L;
        }
        // 数值过小 → 判定为「相对秒数」，换算成绝对时间戳
        return value < TIMESTAMP_THRESHOLD
                ? System.currentTimeMillis() + value * 1000L
                : value;
    }

    /**
     * 调用 SSO 平台刷新 Token
     * <p>
     * 请求格式: application/json
     * <p>
     * 返回格式: {"code":0, "data":{"access_token":"...","refresh_token":"...","expires_at":"1763633925835"}, "msg":""}
     *
     * @param uid          用户 ID
     * @param refreshToken 刷新令牌
     * @return true=续期成功，false=续期失败（需重新登录）
     */
    private boolean refreshAccessToken(String uid, String refreshToken) {
        // 从 discovery 获取的 tokenEndpoint 可能是 /oauth/token 或 /oauth/code/token
        // refresh 端点统一为 /oauth/code/token/refresh
        String tokenEndpoint = ssoProperties.getTokenEndpoint();
        String refreshUrl = tokenEndpoint.contains("/oauth/")
                ? tokenEndpoint.replaceAll("/oauth/.*", "/oauth/code/token/refresh")
                : tokenEndpoint + "/code/token/refresh";
        log.info("续期 SSO Token, uid={}, url={}", uid, refreshUrl);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("client_id", ssoProperties.getClientId());
        params.put("client_secret", ssoProperties.getClientSecret());
        params.put("refresh_token", refreshToken);

        try {
            String jsonBody = objectMapper.writeValueAsString(params);
            RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), jsonBody);

            Request request = new Request.Builder()
                    .url(refreshUrl)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : null;
                if (respBody == null) {
                    log.warn("SSO Token 续期响应为空, uid={}", uid);
                    return false;
                }
                JSONObject resultJson = new JSONObject(respBody);
                // 注意：不能直接 int code = getInt("code")。
                // 响应缺少 code 字段时 hutool 返回 null，自动拆箱会抛 NPE，
                // 而外层 catch 只捕获 IOException，NPE 会一路冒泡导致接口 500。
                Integer codeObj = resultJson.getInt("code");
                if (codeObj == null || codeObj != 0) {
                    log.warn("SSO Token 续期失败, uid={}, msg={}", uid, resultJson.getStr("msg"));
                    return false;
                }

                JSONObject data = resultJson.getJSONObject("data");
                if (data == null || data.getStr("access_token") == null) {
                    log.warn("SSO Token 续期返回 data 为空, uid={}", uid);
                    return false;
                }

                String newAccessToken = data.getStr("access_token");
                String newRefreshToken = data.getStr("refresh_token");

                // expires_at 在响应中是 String 类型，如 "1763633925835"
                Long expiresAt = parseExpiresAt(data.getStr("expires_at"));

                saveOrUpdate(uid, newAccessToken, newRefreshToken, expiresAt);
                log.info("SSO Token 续期成功, uid={}", uid);
                return true;
            }
        } catch (IOException e) {
            log.warn("SSO Token 续期请求异常, uid={}, 原因: {}", uid, e.getMessage());
            return false;
        } catch (Exception e) {
            // 兜底：SSO 平台返回结构与预期不符时不应让异常冒泡成 500
            log.warn("SSO Token 续期处理异常, uid={}, 原因: {}", uid, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 解析 expires_at，支持 Number 和 String 类型
     */
    private Long parseExpiresAt(Object obj) {
        if (obj == null) {
            return System.currentTimeMillis() + 30 * 60 * 1000L;
        }
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            log.warn("无法解析 expires_at: {}", obj);
            return System.currentTimeMillis() + 30 * 60 * 1000L;
        }
    }

    /**
     * 判断时间戳是否已过期
     */
    private boolean isExpired(Long expiresAt) {
        if (expiresAt == null) {
            return true;
        }
        return System.currentTimeMillis() >= expiresAt;
    }

}
