package com.aiproject.app.start.config;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.business.account.service.RoleCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoginAuthInterceptor 的门禁语义。
 *
 * <p>这里守的是一条结构性的底线：只要 uid 解析不出来，请求就不该进到 Controller。
 * 之前框架的 {@code @LoginUserId} 解析器未登录时返回 null 而不是拒绝，
 * {@code /uaccess/logoutById} 又没有自查，实测不带 token 发
 * {@code {"uid":"..."}} 就能把任意账号踢下线。
 */
class LoginAuthInterceptorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RoleCheckService roleCheckService;
    private MyUserBaseDbHolder userHolder;
    private LoginAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        roleCheckService = mock(RoleCheckService.class);
        userHolder = mock(MyUserBaseDbHolder.class);
        interceptor = new LoginAuthInterceptor(provider(roleCheckService), provider(userHolder));
    }

    @Test
    void optionsPreflight_shouldPassWithoutLookingUpLoginState() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/knowledgeBaseBean/query");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(roleCheckService, never()).getUidFromAccount(any());
    }

    @Test
    void loginEndpoints_shouldStayAnonymous() throws Exception {
        for (String path : new String[]{
                "/uaccess/logintest", "/uaccess/login", "/uaccess/loginToken",
                "/uaccess/getKaptcha", "/uaccess/getKaptchaToken"}) {
            assertTrue(interceptor.preHandle(post(path), new MockHttpServletResponse(), new Object()),
                    "登录入口被误拦：" + path);
        }
        verify(roleCheckService, never()).getUidFromAccount(any());
    }

    @Test
    void deliberatelyPublicBusinessEndpoints_shouldPass() throws Exception {
        // 访客打开分享链接、SSO 平台 302 回来，这两趟都没有本站登录态
        assertTrue(interceptor.preHandle(post("/api/chatShare/get"), new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(post("/api/ssoTokenBean/callback"), new MockHttpServletResponse(), new Object()));
        verify(roleCheckService, never()).getUidFromAccount(any());
    }

    @Test
    void protectedEndpointWithoutToken_shouldBeRejectedWithLoginCode() throws Exception {
        when(roleCheckService.getUidFromAccount(any())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(post("/uaccess/logoutById"), response, new Object()));

        JsonNode body = readBody(response);
        assertEquals(20102, body.get("resp_code").asInt());
        // 前端 axios 拦截器靠这个码 + 「先登录」文案跳登录页，两个都不能少
        assertTrue(body.get("resp_msg").asText().contains("先登录"), body.get("resp_msg").asText());
        assertEquals(200, response.getStatus());
    }

    @Test
    void agentStream_shouldRequirePlatformLogin() throws Exception {
        when(roleCheckService.getUidFromAccount(any())).thenReturn(null);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(post("/api/agentBean/streamRun"), rejected, new Object()));
        assertEquals(20102, readBody(rejected).get("resp_code").asInt());

        when(roleCheckService.getUidFromAccount(any())).thenReturn("20001");
        assertTrue(interceptor.preHandle(post("/api/agentBean/streamRun"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void blankUid_shouldBeTreatedAsNotLoggedIn() throws Exception {
        // 框架解析器在未登录时可能返回空串而不是 null，同样不能放行
        when(roleCheckService.getUidFromAccount(any())).thenReturn("   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(post("/api/chatConversation/list"), response, new Object()));
        assertEquals(20102, readBody(response).get("resp_code").asInt());
    }

    @Test
    void forcedLogout_shouldRequireAdmin() throws Exception {
        when(roleCheckService.getUidFromAccount(any())).thenReturn("20001");
        when(userHolder.isAdmin("20001")).thenReturn(false);

        for (String path : new String[]{"/uaccess/logoutById", "/uaccess/logoutByAccess"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(post(path), response, new Object()), "普通用户越权强制登出：" + path);

            JsonNode body = readBody(response);
            assertFalse(body.get("resp_code").asInt() == 0, path);
            assertTrue(body.get("resp_msg").asText().contains("管理员"), body.get("resp_msg").asText());
        }
    }

    @Test
    void adminForcedLogout_shouldPass() throws Exception {
        when(roleCheckService.getUidFromAccount(any())).thenReturn("10001");
        when(userHolder.isAdmin("10001")).thenReturn(true);

        assertTrue(interceptor.preHandle(post("/uaccess/logoutById"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void siteWideOperationLog_shouldRequireAdmin() throws Exception {
        // Admin 变体不按 uid 过滤，普通账号读到的却是全站操作记录（uaccount/action/params/res/clientip）
        when(roleCheckService.getUidFromAccount(any())).thenReturn("20001");
        when(userHolder.isAdmin("20001")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
                post("/userControllerlog/getControllerLogByTimeAdmin"), response, new Object()));

        JsonNode body = readBody(response);
        assertFalse(body.get("resp_code").asInt() == 0);
        assertTrue(body.get("resp_msg").asText().contains("管理员"), body.get("resp_msg").asText());
    }

    @Test
    void admin_shouldReadSiteWideOperationLog() throws Exception {
        when(roleCheckService.getUidFromAccount(any())).thenReturn("10001");
        when(userHolder.isAdmin("10001")).thenReturn(true);

        assertTrue(interceptor.preHandle(
                post("/userControllerlog/getControllerLogByTimeAdmin"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void ownOperationLog_shouldStayAvailableToLoggedInUser() throws Exception {
        // 非 Admin 变体会先按 uid 收窄成「只查自己」，把它一起收紧会把普通用户的功能打死
        when(roleCheckService.getUidFromAccount(any())).thenReturn("20001");

        assertTrue(interceptor.preHandle(
                post("/userControllerlog/getControllerLogByTime"), new MockHttpServletResponse(), new Object()));
        verify(userHolder, never()).isAdmin(any());
    }

    @Test
    void loggedInUser_shouldPassOrdinaryBusinessEndpoint() throws Exception {
        when(roleCheckService.getUidFromAccount(any())).thenReturn("20001");

        assertTrue(interceptor.preHandle(post("/api/chatConversation/list"), new MockHttpServletResponse(), new Object()));
        // 普通业务接口不该多打一次「是否管理员」的库
        verify(userHolder, never()).isAdmin(any());
    }

    @Test
    void missingRoleCheckService_shouldFailClosed() throws Exception {
        LoginAuthInterceptor broken = new LoginAuthInterceptor(
                provider(null), provider(userHolder));
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 解析器都拿不到时绝不能放行
        assertFalse(broken.preHandle(post("/api/knowledgeBaseBean/query"), response, new Object()));
        assertEquals(20102, readBody(response).get("resp_code").asInt());
    }

    @Test
    void resolverThrowing_shouldRejectInsteadOfPropagate() throws Exception {
        when(roleCheckService.getUidFromAccount(any())).thenThrow(new IllegalStateException("sa-token context lost"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(post("/api/datasetsBean/query"), response, new Object()));
        assertEquals(20102, readBody(response).get("resp_code").asInt());
    }

    private MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        // 拦截器优先读 MVC 已经算好的「context path 之内」路径，测试里显式设置即可
        request.setAttribute(org.springframework.web.servlet.HandlerMapping
                .PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, path);
        return request;
    }

    private JsonNode readBody(MockHttpServletResponse response) throws Exception {
        return MAPPER.readTree(response.getContentAsString());
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject() {
                if (instance == null) {
                    throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("");
                }
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }
}
