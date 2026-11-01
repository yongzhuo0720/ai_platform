package com.aiproject.app.start.config;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.business.account.service.RoleCheckService;
import com.heneng.hnsp.common.croe.model.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 统一登录门禁。
 *
 * <p><b>为什么必须由项目自己做。</b>框架注入给 Controller 的 {@code @LoginUserId String uid}
 * 由 {@code TokenArgumentResolver} 解析，而它的实现只是
 * {@code roleCheckService.getUidFromAccount(request)}，后者把
 * {@code StpUtil.getLoginIdAsString()} 整个包在 try/catch 里、未登录时<b>返回 null</b>。
 * 也就是说框架只负责「把 uid 递进来」，从不负责「没登录就不让进」。
 * {@code WebConfig.addInterceptors} 里原本承担这一职责的两个框架拦截器又是注释掉的，
 * 于是所有接口的鉴权都退化成「Holder 里恰好写了 if (uid == null) 就拒绝」——
 * 而 {@code /uaccess/logoutById} 连这一句都没有：
 * 不带任何 token、只发 body {@code {"uid":"..."}} 就能把任意账号（含超管）踢下线。
 *
 * <p>所以这里默认拒绝：命中受保护前缀且解析不出 uid，就直接返回未登录，
 * 请求根本进不到 Controller。这样新写的接口即使忘了判 uid，也不会变成匿名可访问。
 *
 * <p>uid 的解析刻意复用框架的 {@link RoleCheckService}，和 Controller 里
 * {@code @LoginUserId} 拿到的是同一个值，避免出现两套登录态判定标准。
 */
@Component
public class LoginAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginAuthInterceptor.class);

    /** 与框架 CodeEnum.FAILED_USER_TOKEN_PAST 一致；前端 axios 拦截器按这个码跳登录页。 */
    private static final int NOT_LOGIN_CODE = 20102;

    private static final String NOT_LOGIN_MSG = "请先登录, 错误码:" + NOT_LOGIN_CODE;

    /**
     * 只允许超管调用的框架接口。
     * <ul>
     *   <li>{@code /uaccess/logoutById}、{@code /uaccess/logoutByAccess}：
     *       「按 uid / uaccess 强制登出」的运维能力，普通用户拿到就能踢任何人下线。</li>
     *   <li>{@code /userControllerlog/getControllerLogByTimeAdmin}：框架里唯一「不按 uid 过滤」
     *       的日志查询——同名非 Admin 版本会先 {@code bean.setUaccount(uid)} 只查自己，
     *       Admin 版本直接把条件透传，实测一个普通用户账号能读出全站 1.1 万条操作记录
     *       （含 uaccount、action、params、res、url、clientip），等于全站行为审计库对外敞开。
     *       前端唯一的调用方是后台「操作日志」页，而该页的登录流程本身就只放行 rid=99999
     *       （LoginView 里非超管直接「暂无权限」并清空 admin_* cookie），
     *       所以后端补上这道判定是补齐「只有前端做了鉴权」的缺口，不改变任何合法用法。</li>
     * </ul>
     */
    private static final Set<String> ADMIN_ONLY_PATHS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "/uaccess/logoutById",
                    "/uaccess/logoutByAccess",
                    "/userControllerlog/getControllerLogByTimeAdmin")));

    /** 框架自身的免登录入口：登录、验证码。 */
    private static final Set<String> ANONYMOUS_PATHS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "/uaccess/logintest",
                    "/uaccess/login",
                    "/uaccess/loginToken",
                    "/uaccess/getKaptcha",
                    "/uaccess/getKaptchaToken"
            )));

    /**
     * 业务侧有意免登录的入口。
     * <ul>
     *   <li>{@code /api/chatShare/get}：访客打开分享链接，本来就没有本站登录态。</li>
     *   <li>{@code /api/ssoTokenBean/callback}：SSO 平台 302 回来的一趟浏览器跳转，
     *       身份由带签名的 state 参数承载（Controller 内已校验）。</li>
     * </ul>
     * 新增这一类接口时必须同步登记到这里，否则会被本拦截器挡掉。
     */
    private static final Set<String> PUBLIC_BUSINESS_PATHS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "/api/chatShare/get",
                    "/api/ssoTokenBean/callback"
            )));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<RoleCheckService> roleCheckServiceProvider;

    private final ObjectProvider<MyUserBaseDbHolder> userHolderProvider;

    public LoginAuthInterceptor(ObjectProvider<RoleCheckService> roleCheckServiceProvider,
                                ObjectProvider<MyUserBaseDbHolder> userHolderProvider) {
        // 用 ObjectProvider 延迟取：WebMvcConfigurer 装配时机早于普通业务 Bean，
        // 直接构造注入会把 Holder 及其 Mapper 依赖链提前拉起来。
        this.roleCheckServiceProvider = roleCheckServiceProvider;
        this.userHolderProvider = userHolderProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检不带自定义头，也不该被鉴权拦下
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = pathWithinApplication(request);
        if (isAnonymous(path)) {
            return true;
        }

        String uid = currentUid(request);
        if (uid == null) {
            reject(response, NOT_LOGIN_CODE, NOT_LOGIN_MSG, path, null);
            return false;
        }

        if (ADMIN_ONLY_PATHS.contains(path) && !isAdmin(uid)) {
            reject(response, -1, "仅管理员可执行该操作", path, uid);
            return false;
        }

        return true;
    }

    /**
     * 解析当前登录 uid；未登录返回 null。
     *
     * <p>解析器本身不可用属于部署问题，此时一律按未登录处理（fail-closed），
     * 绝不能因为拿不到 uid 就放行。
     */
    private String currentUid(HttpServletRequest request) {
        RoleCheckService roleCheckService = roleCheckServiceProvider.getIfAvailable();
        if (roleCheckService == null) {
            log.error("[LoginAuthInterceptor] 容器中没有 RoleCheckService，按未登录处理");
            return null;
        }
        try {
            String uid = roleCheckService.getUidFromAccount(request);
            return uid == null || uid.trim().isEmpty() ? null : uid;
        } catch (Exception e) {
            log.warn("[LoginAuthInterceptor] 解析登录态失败: {}", e.toString());
            return null;
        }
    }

    /**
     * 超管判定复用 {@link MyUserBaseDbHolder#isAdmin(String)}，
     * 与各 Holder 里已有的管理员校验保持同一个标准（rid = 99999）。
     */
    private boolean isAdmin(String uid) {
        MyUserBaseDbHolder holder = userHolderProvider.getIfAvailable();
        return holder != null && holder.isAdmin(uid);
    }

    private boolean isAnonymous(String path) {
        return ANONYMOUS_PATHS.contains(path) || PUBLIC_BUSINESS_PATHS.contains(path);
    }

    /**
     * 取 context path 之后的路径。拦截器的路径匹配用的就是这个值，
     * 便于直接和上面的常量路径做精确比较。
     */
    private String pathWithinApplication(HttpServletRequest request) {
        Object matched = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (matched instanceof String) {
            return (String) matched;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    /**
     * 返回 HTTP 200 + 框架 Result 信封，而不是 401/403：
     * 全站约定「业务失败也走 200」，前端 axios 只在 {@code resp_code} 上分支，
     * 改成 401 反而会绕开既有的跳登录逻辑。
     */
    private void reject(HttpServletResponse response, int respCode, String respMsg, String path, String uid)
            throws IOException {
        log.warn("[LoginAuthInterceptor] 拒绝请求, path={}, uid={}", path, uid);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        MAPPER.writeValue(response.getWriter(), new Result<>(respCode, null, null, respMsg));
    }
}
