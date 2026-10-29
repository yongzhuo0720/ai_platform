package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.LableChildBean;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签子项写接口的管理员校验测试（报告 P0-3）。
 *
 * <p>与标签组同属全平台共享基础数据，写操作必须限管理员。
 * 2026-10-29 修复前 install / delete / update 完全没有鉴权。</p>
 */
@ExtendWith(MockitoExtension.class)
class LableChildBeanAuthTest {

    @Mock
    LableChildBeanMapper<LableChildBean> lableChildBeanMapper;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @InjectMocks
    LableChildBeanHolder holder;

    private LableChildBean data() {
        LableChildBean bean = new LableChildBean();
        bean.setName("测试子项");
        return bean;
    }

    private void assertRejected(Result<Boolean> r) {
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "非管理员的写操作必须被拒绝");
    }

    @Test
    void install_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.install("u1", data()));
        verify(lableChildBeanMapper, never()).install(any(LableChildBean.class));
    }

    @Test
    void install_nullUid_shouldBeRejected() {
        assertRejected(holder.install(null, data()));
        verify(lableChildBeanMapper, never()).install(any(LableChildBean.class));
    }

    @Test
    void install_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(lableChildBeanMapper.install(any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.install("admin", data()).getDatas()),
                "管理员建标签子项应当成功");
    }

    @Test
    void delete_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.delete("u1", data()));
        verify(lableChildBeanMapper, never()).delete(any(LableChildBean.class));
    }

    @Test
    void delete_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(lableChildBeanMapper.delete(any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.delete("admin", data()).getDatas()),
                "管理员删标签子项应当成功");
    }

    @Test
    void update_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.update("u1", data(), new LableChildBean()));
        verify(lableChildBeanMapper, never()).update(any(LableChildBean.class), any(LableChildBean.class));
    }
}
