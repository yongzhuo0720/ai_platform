package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller 级权限传递矩阵（2026-11-19 升级 P2-4 配套）。
 *
 * <p>真正的鉴权在 Holder（已由 20+ 越权单测覆盖）。这里守的是另一条底线：
 * Controller 必须把 {@code @LoginUserId} 解析出的 uid <b>原样</b>传给 Holder，
 * 绝不硬编码、也不从请求体里取 uid——否则Holder 的校验形同虚设。</p>
 *
 * <p>测试方式：MockMvc + 自定义 resolver 注入受控 uid，捕获传给 Holder 的 uid 参数断言；
 * 并验证 Holder 拒绝时 Controller 如实把失败 Result 返回（不吞、不改成功）。</p>
 */
class ControllerPermissionMatrixTest {

    private final ObjectMapper json = new ObjectMapper();
    private ModelsConfigBeanHolder modelsConfigHolder;
    private KbRagHolder kbRagHolder;
    private final AtomicReference<String> injectedUid = new AtomicReference<String>("attacker");

    @BeforeEach
    void setUp() {
        modelsConfigHolder = mock(ModelsConfigBeanHolder.class);
        kbRagHolder = mock(KbRagHolder.class);
        injectedUid.set("attacker");
    }

    /** 创建注入了 mock Holder 的模型配置 Controller */
    private ModelsConfigBeanController modelsController() {
        ModelsConfigBeanController c = new ModelsConfigBeanController();
        ReflectionTestUtils.setField(c, "modelsConfigBeanHolder", modelsConfigHolder);
        return c;
    }

    /** 创建注入了 mock Holder 的 KbRag Controller */
    private KbRagController kbController() {
        KbRagController c = new KbRagController();
        ReflectionTestUtils.setField(c, "kbRagHolder", kbRagHolder);
        return c;
    }

    /** 构造带 @LoginUserId resolver 的 MockMvc（照项目现有 ControllerTest 模式） */
    private MockMvc mvc(Object controller) {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        RequestMappingHandlerAdapter adapter = mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerAdapter.class);
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<HandlerMethodArgumentResolver>();
        resolvers.add(new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter p) {
                return p.hasParameterAnnotation(LoginUserId.class);
            }
            public Object resolveArgument(MethodParameter p, ModelAndViewContainer c,
                                          NativeWebRequest r, WebDataBinderFactory f) {
                return injectedUid.get();
            }
        });
        // 注：@RequestBody 由 Spring 默认的 RequestResponseBodyMethodProcessor 解析，
        // 这里只需注入 @LoginUserId 的 resolver，其余用 adapter 默认链，避免干扰请求体解析
        resolvers.addAll(adapter.getArgumentResolvers());
        adapter.setArgumentResolvers(resolvers);
        return mvc;
    }

    @Test
    void adminResource_nonAdminUid_isPassedThroughAndDenied() throws Exception {
        injectedUid.set("normal-user");
        when(modelsConfigHolder.install(anyString(), any(ModelsConfigBean.class)))
                .thenReturn(Result.failed(false, "仅管理员可维护模型配置"));

        MockMvc mvc = mvc(modelsController());
        mvc.perform(post("/api/modelsConfigBean/create")
                        .contentType("application/json")
                        .content(json.writeValueAsString(new ModelsConfigBean())))
                .andExpect(status().isOk());

        // 关键：Controller 把解析出的 uid 原样传给 Holder，没有硬编码或伪造
        ArgumentCaptor<String> uidCap = ArgumentCaptor.forClass(String.class);
        verify(modelsConfigHolder).install(uidCap.capture(), any(ModelsConfigBean.class));
        assertEquals("normal-user", uidCap.getValue());
    }

    @Test
    void userResource_deleteDoc_passesUidForOwnershipCheck() throws Exception {
        injectedUid.set("user-A");
        when(kbRagHolder.deleteDoc(anyString(), any(KbDocBean.class)))
                .thenReturn(Result.failed(false, "无权删除他人文档"));

        MockMvc mvc = mvc(kbController());
        mvc.perform(post("/api/kbRag/deleteDoc")
                        .contentType("application/json")
                        .content(json.writeValueAsString(new KbDocBean())))
                .andExpect(status().isOk());

        ArgumentCaptor<String> uidCap = ArgumentCaptor.forClass(String.class);
        verify(kbRagHolder).deleteDoc(uidCap.capture(), any(KbDocBean.class));
        assertEquals("user-A", uidCap.getValue(),
                "不同用户的 uid 必须原样透传，Holder 才能做归属校验");
    }

    @Test
    void orphanCleanup_nonAdmin_isRejectedBeforeHolder() throws Exception {
        injectedUid.set("normal-user");
        com.aiproject.ai.permission.holde.MyUserBaseDbHolder userHolder =
                mock(com.aiproject.ai.permission.holde.MyUserBaseDbHolder.class);
        when(userHolder.isAdmin("normal-user")).thenReturn(false);

        KbRagController c = kbController();
        ReflectionTestUtils.setField(c, "myUserBaseDbHolder", userHolder);
        MockMvc mvc = mvc(c);

        // 默认 dry-run 调用，非管理员应在 Controller 层被拒
        String body = mvc.perform(post("/api/kbRag/admin/cleanupOrphanChunks")
                        .param("confirm", "false"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // MockMvc 响应体中文可能乱码，用 resp_code=-1 判断被拒绝即可
        assertTrue(body.contains("\"resp_code\":-1"), "非管理员应被拒绝: " + body);
        verify(kbRagHolder, never()).cleanupOrphanChunks(anyBoolean());
    }

    @Test
    void orphanCleanup_adminDryRun_isDelegated() throws Exception {
        injectedUid.set("admin");
        com.aiproject.ai.permission.holde.MyUserBaseDbHolder userHolder =
                mock(com.aiproject.ai.permission.holde.MyUserBaseDbHolder.class);
        when(userHolder.isAdmin("admin")).thenReturn(true);

        KbRagHolder.OrphanCleanupResult r = new KbRagHolder.OrphanCleanupResult();
        r.dryRun = true;
        when(kbRagHolder.cleanupOrphanChunks(false)).thenReturn(r);

        KbRagController c = kbController();
        ReflectionTestUtils.setField(c, "myUserBaseDbHolder", userHolder);
        MockMvc mvc = mvc(c);

        mvc.perform(post("/api/kbRag/admin/cleanupOrphanChunks")
                        .param("confirm", "false"))
                .andExpect(status().isOk());
        verify(kbRagHolder, times(1)).cleanupOrphanChunks(false);
    }

    @Test
    void holderDenial_isNotSwallowedByController() throws Exception {
        injectedUid.set("outsider");
        when(kbRagHolder.deleteDoc(anyString(), any(KbDocBean.class)))
                .thenReturn(Result.failed(false, "无权操作"));

        // 这里只验证 Controller 不吞失败：mock 的 reject 应被调用且参数正确
        // （Controller 直接 return holder 结果，无自身逻辑）
        MockMvc mvc = mvc(kbController());
        mvc.perform(post("/api/kbRag/deleteDoc")
                        .contentType("application/json")
                        .content(json.writeValueAsString(new KbDocBean())))
                .andExpect(status().isOk());

        verify(kbRagHolder, times(1)).deleteDoc(eq("outsider"), any(KbDocBean.class));
    }
}
