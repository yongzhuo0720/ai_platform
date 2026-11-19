package com.aiproject.ai.permission.utils;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 统一超管校验：钉住"null=通过、非null=拒绝"的返回约定与原文案。 */
@ExtendWith(MockitoExtension.class)
class AdminGuardTest {

    @Mock
    MyUserBaseDbHolder userHolder;

    @Test
    void admin_passes() {
        when(userHolder.isAdmin("admin")).thenReturn(true);
        assertNull(AdminGuard.requireAdmin("admin", userHolder, "模型配置"));
    }

    @Test
    void nullUid_rejected() {
        Result<Boolean> r = AdminGuard.requireAdmin(null, userHolder, "模型配置");
        assertNotNull(r);
        assertTrue(r.getResp_msg().contains("请先登录"));
    }

    @Test
    void blankUid_rejected() {
        Result<Boolean> r = AdminGuard.requireAdmin("   ", userHolder, "模型配置");
        assertNotNull(r);
        assertTrue(r.getResp_msg().contains("请先登录"));
    }

    @Test
    void nonAdmin_rejectedWithResourceLabel() {
        when(userHolder.isAdmin("u1")).thenReturn(false);
        Result<Boolean> r = AdminGuard.requireAdmin("u1", userHolder, "模型配置");
        assertNotNull(r);
        assertEquals("仅管理员可维护模型配置", r.getResp_msg());
    }

    @Test
    void nullHolder_failClosed() {
        Result<Boolean> r = AdminGuard.requireAdmin("admin", null, "模型配置");
        assertNotNull(r, "Holder 缺失时按拒绝处理，不能 fail-open");
    }
}
