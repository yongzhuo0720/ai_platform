package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.UnitBean;
import com.aiproject.ai.permission.mapper.UnitBeanMapper;
import com.heneng.hnsp.business.user.server.UserBaseHolder;
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
 * 部门（unit）写接口的管理员校验测试。
 *
 * <p>为什么单独为它写测试：部门表是 RBAC 的支柱 —— 用户表的 gid 指向 unit.unitsno，
 * 资源可见性过滤（ResourceVisibility）依赖 gid。任意已登录用户若能新建/删除/篡改部门，
 * 等于可以撬动全平台资源可见性，这是本次审查里危害最大的一条（报告 P0-3）。</p>
 *
 * <p>2026-10-29 修复前：install / delete / update 三个写方法完全没有鉴权，
 * Controller 里只判了「uid 非空」。这里覆盖修复后的行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class UnitBeanAuthTest {

    @Mock
    UnitBeanMapper<UnitBean> unitBeanMapper;

    @Mock
    UserBaseHolder userBaseHolder;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @InjectMocks
    UnitBeanHolder holder;

    private UnitBean data() {
        UnitBean bean = new UnitBean();
        bean.setUnitname("测试部门");
        return bean;
    }

    /** Result.failed(false, msg) 的 datas 是 false，这里统一用「不为 TRUE」判定被拒绝 */
    private void assertRejected(Result<Boolean> r) {
        assertFalse(Boolean.TRUE.equals(r.getDatas()), "非管理员的写操作必须被拒绝");
    }

    // ---------- install ----------

    @Test
    void install_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.install("u1", data()));
        verify(unitBeanMapper, never()).install(any(UnitBean.class));
    }

    @Test
    void install_nullUid_shouldBeRejected() {
        assertRejected(holder.install(null, data()));
        verify(unitBeanMapper, never()).install(any(UnitBean.class));
    }

    @Test
    void install_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(unitBeanMapper.queryMaxSno("")).thenReturn(null);
        when(unitBeanMapper.install(any(UnitBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.install("admin", data()).getDatas()),
                "管理员建部门应当成功");
    }

    // ---------- delete ----------

    @Test
    void delete_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.delete("u1", data()));
        verify(unitBeanMapper, never()).delete(any(UnitBean.class));
    }

    @Test
    void delete_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        // queryChild 内部先查该部门：查不到即视为「没有下级部门」，允许删除
        when(unitBeanMapper.select(isNull(), any(UnitBean.class))).thenReturn(null);
        when(unitBeanMapper.delete(any(UnitBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.delete("admin", data()).getDatas()),
                "管理员删部门应当成功");
    }

    // ---------- update ----------

    @Test
    void update_nonAdmin_shouldBeRejected_andNotTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertRejected(holder.update("u1", data(), new UnitBean()));
        verify(unitBeanMapper, never()).update(any(UnitBean.class), any(UnitBean.class));
    }

    @Test
    void update_admin_shouldSucceed() {
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(unitBeanMapper.update(any(UnitBean.class), any(UnitBean.class))).thenReturn(1);

        assertTrue(Boolean.TRUE.equals(holder.update("admin", data(), new UnitBean()).getDatas()),
                "管理员改部门应当成功");
    }
}
