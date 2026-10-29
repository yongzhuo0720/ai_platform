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
 * 标签绑定表（lableChildBean）的权限测试。
 *
 * <p>这张表不是「标签子项」而是「资源 ←→ 标签组」的绑定：
 * hash = 资源主键，type = 模块，lableiid = 标签组。
 * 普通用户给自己名下的知识库打/取消标签走的正是 install / delete，
 * 因此这两个方法只能要求登录；只有整行 update 保持管理员兜底。</p>
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
        bean.setName("测试绑定");
        return bean;
    }

    @Test
    void install_loggedInUser_shouldSucceed() {
        when(lableChildBeanMapper.install(any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.install("u1", data()).getDatas()),
                "普通用户给自己名下的资源打标签必须可用（知识库详情页/广场依赖此接口）");
        verify(myUserBaseDbHolder, never()).isAdmin(any());
    }

    @Test
    void install_nullUid_shouldBeRejected_andNotTouchDb() {
        Result<Boolean> r = holder.install(null, data());
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "未登录的写操作必须被拒绝");
        verify(lableChildBeanMapper, never()).install(any(LableChildBean.class));
    }

    @Test
    void delete_loggedInUser_shouldSucceed() {
        when(lableChildBeanMapper.delete(any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.delete("u1", data()).getDatas()),
                "普通用户取消自己资源的标签必须可用");
        verify(myUserBaseDbHolder, never()).isAdmin(any());
    }

    @Test
    void delete_nullUid_shouldBeRejected_andNotTouchDb() {
        Result<Boolean> r = holder.delete("", data());
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "未登录的删除必须被拒绝");
        verify(lableChildBeanMapper, never()).delete(any(LableChildBean.class));
    }

    @Test
    void update_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        Result<Boolean> r = holder.update("u1", data(), new LableChildBean());
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "整行修改没有合法用户调用方，非管理员必须被拒绝");
        verify(lableChildBeanMapper, never()).update(any(LableChildBean.class), any(LableChildBean.class));
    }

    @Test
    void update_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(lableChildBeanMapper.update(any(LableChildBean.class), any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.update("admin", data(), new LableChildBean()).getDatas()));
    }
}
