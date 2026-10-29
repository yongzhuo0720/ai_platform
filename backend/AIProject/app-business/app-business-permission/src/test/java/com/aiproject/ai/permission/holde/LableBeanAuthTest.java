package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.LableBean;
import com.aiproject.ai.permission.bean.LableChildBean;
import com.aiproject.ai.permission.mapper.LableBeanMapper;
import com.aiproject.ai.permission.mapper.LableChildBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签组写接口的管理员校验测试（报告 P0-3）。
 *
 * <p>标签组是全平台共享的基础数据，各业务资源都通过它打标签。
 * 2026-10-29 修复前，install / delete / update 完全没有鉴权，
 * 任意已登录用户都能增删改全平台标签组。</p>
 */
@ExtendWith(MockitoExtension.class)
class LableBeanAuthTest {

    @Mock
    LableBeanMapper<LableBean> lableBeanMapper;

    @Mock
    LableChildBeanMapper<LableChildBean> lableChildBeanMapper;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @InjectMocks
    LableBeanHolder holder;

    private LableBean data() {
        LableBean bean = new LableBean();
        bean.setName("测试标签");
        bean.setType("kb");
        return bean;
    }

    private void assertRejected(Result<Boolean> r) {
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "非管理员的写操作必须被拒绝");
    }

    @Test
    void install_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.install("u1", data()));
        verify(lableBeanMapper, never()).install(any(LableBean.class));
    }

    @Test
    void install_nullUid_shouldBeRejected() {
        assertRejected(holder.install(null, data()));
        verify(lableBeanMapper, never()).install(any(LableBean.class));
    }

    @Test
    void install_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        // 同名查不到 → 允许新建
        when(lableBeanMapper.select(isNull(), any(LableBean.class))).thenReturn(null);
        when(lableBeanMapper.install(any(LableBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.install("admin", data()).getDatas()),
                "管理员建标签组应当成功");
    }

    @Test
    void delete_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.delete("u1", data()));
        verify(lableBeanMapper, never()).delete(any(LableBean.class));
        // 顺带确认子项也没被连带删除
        verify(lableChildBeanMapper, never()).delete(any(LableChildBean.class));
    }

    @Test
    void delete_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(lableBeanMapper.delete(any(LableBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.delete("admin", data()).getDatas()),
                "管理员删标签组应当成功");
    }

    @Test
    void update_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.update("u1", data(), new LableBean()));
        verify(lableBeanMapper, never()).update(any(LableBean.class), any(LableBean.class));
    }

    @Test
    void update_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(lableBeanMapper.update(any(LableBean.class), any(LableBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.update("admin", data(), new LableBean()).getDatas()),
                "管理员改标签组应当成功");
    }
}
