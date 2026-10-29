package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.LableChildBean;
import com.aiproject.ai.permission.mapper.LableChildBeanMapper;
import com.aiproject.ai.permission.spi.LableBindingResourceGuard;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 标签绑定表（lableChildBean）的权限测试。
 *
 * <p>这张表不是「标签子项」而是「资源 ←→ 标签组」的绑定：
 * hash = 资源主键，type = 模块，lableiid = 标签组。
 * 写操作必须校验资源归属：超管可维护全部绑定，普通用户只能维护
 * 自己名下知识库的标签（知识库详情页/广场依赖此能力）。</p>
 */
@ExtendWith(MockitoExtension.class)
class LableChildBeanAuthTest {

    @Mock
    LableChildBeanMapper<LableChildBean> lableChildBeanMapper;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @Mock
    LableBindingResourceGuard resourceGuard;

    @InjectMocks
    LableChildBeanHolder holder;

    private LableChildBean binding(String type, String hash) {
        LableChildBean bean = new LableChildBean();
        bean.setType(type);
        bean.setHash(hash);
        bean.setLableiid(1L);
        return bean;
    }

    @Test
    void install_kbOwner_shouldSucceed() {
        when(resourceGuard.type()).thenReturn("knowledge_base");
        when(resourceGuard.canManage("u1", "kb-1")).thenReturn(true);
        when(lableChildBeanMapper.install(any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.install("u1", binding("knowledge_base", "kb-1")).getDatas()),
                "普通用户给自己名下的知识库打标签必须可用（知识库详情页/广场依赖此接口）");
    }

    @Test
    void install_kbNotOwned_shouldBeRejected_andNotTouchDb() {
        when(resourceGuard.type()).thenReturn("knowledge_base");
        when(resourceGuard.canManage("u1", "kb-other")).thenReturn(false);

        Result<Boolean> r = holder.install("u1", binding("knowledge_base", "kb-other"));
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "不能给他人知识库打标签");
        verify(lableChildBeanMapper, never()).install(any(LableChildBean.class));
    }

    @Test
    void install_nonKnowledgeBaseType_shouldBeRejectedForNormalUser() {
        when(resourceGuard.type()).thenReturn("knowledge_base");

        Result<Boolean> r = holder.install("u1", binding("models", "9"));
        assertFalse(Boolean.TRUE.equals(r.getDatas()),
                "模型/数据集/能力等平台级资源只有管理员能维护标签");
        verify(resourceGuard, never()).canManage(any(), any());
        verify(lableChildBeanMapper, never()).install(any(LableChildBean.class));
    }

    @Test
    void install_missingTypeOrHash_shouldBeRejected_andNotTouchDb() {
        Result<Boolean> r = holder.install("u1", binding(null, null));
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "无法判定归属的绑定请求必须拒绝");
        verify(lableChildBeanMapper, never()).install(any(LableChildBean.class));
    }

    @Test
    void install_admin_shouldSucceedWithoutOwnershipCheck() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(lableChildBeanMapper.install(any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.install("admin", binding("models", "9")).getDatas()));
        verifyNoInteractions(resourceGuard);
    }

    @Test
    void install_nullUid_shouldBeRejected_andNotTouchDb() {
        Result<Boolean> r = holder.install(null, binding("knowledge_base", "kb-1"));
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "未登录的写操作必须被拒绝");
        verify(lableChildBeanMapper, never()).install(any(LableChildBean.class));
    }

    @Test
    void delete_kbOwner_shouldSucceed() {
        when(resourceGuard.type()).thenReturn("knowledge_base");
        when(resourceGuard.canManage("u1", "kb-1")).thenReturn(true);
        when(lableChildBeanMapper.delete(any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.delete("u1", binding("knowledge_base", "kb-1")).getDatas()),
                "普通用户取消自己知识库的标签必须可用");
    }

    @Test
    void delete_kbNotOwned_shouldBeRejected_andNotTouchDb() {
        when(resourceGuard.type()).thenReturn("knowledge_base");
        when(resourceGuard.canManage("u1", "kb-other")).thenReturn(false);

        Result<Boolean> r = holder.delete("u1", binding("knowledge_base", "kb-other"));
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "不能取消他人知识库的标签");
        verify(lableChildBeanMapper, never()).delete(any(LableChildBean.class));
    }

    @Test
    void delete_nullUid_shouldBeRejected_andNotTouchDb() {
        Result<Boolean> r = holder.delete("", binding("knowledge_base", "kb-1"));
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "未登录的删除必须被拒绝");
        verify(lableChildBeanMapper, never()).delete(any(LableChildBean.class));
    }

    @Test
    void update_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        Result<Boolean> r = holder.update("u1", binding("knowledge_base", "kb-1"), new LableChildBean());
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "整行修改没有合法用户调用方，非管理员必须被拒绝");
        verify(lableChildBeanMapper, never()).update(any(LableChildBean.class), any(LableChildBean.class));
    }

    @Test
    void update_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(lableChildBeanMapper.update(any(LableChildBean.class), any(LableChildBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.update("admin", binding("knowledge_base", "kb-1"),
                new LableChildBean()).getDatas()));
    }
}
