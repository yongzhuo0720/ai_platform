package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.MyRoleBean;
import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.mapper.MyRoleBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色查询的「按调用者收敛」测试（2026-10-29 新增）。
 *
 * <p>角色表里的 urlgroupids 就是「这个角色能访问哪些接口」的清单。
 * 修复前 {@code query(bean, pageRequest)} 不校验身份，任意登录用户可以不传条件
 * 或传任意 iid 把整张表读走，等于泄露平台权限地图。</p>
 *
 * <p>但也不能简单地要求管理员：<b>MainLayout（普通用户主布局）依赖这个接口按自己的 rid
 * 查 viewjson 渲染菜单</b>，加管理员校验会让所有普通用户菜单打不开。
 * 所以正确做法是收敛到「只能查自己所属的角色」。</p>
 */
@ExtendWith(MockitoExtension.class)
class MyRoleQueryScopeTest {

    @Mock
    MyRoleBeanMapper<MyRoleBean> roleBeanMapper;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @InjectMocks
    MyRoleBeanHolder holder;

    private MyUserBaseDb user(String uid, Long rid) {
        MyUserBaseDb u = new MyUserBaseDb();
        u.setUid(uid);
        u.setRid(rid);
        return u;
    }

    private PageRequest page() {
        PageRequest p = new PageRequest();
        p.setPageNum(1);
        p.setPageSize(10);
        return p;
    }

    /** 约定：被拒绝时 Result 的 datas 为 null（Result.failed(null, msg)） */
    private void assertRejected(Result<PageResult<MyRoleBean>> r) {
        assertFalse(r.getDatas() != null, "越权/异常查询必须被拒绝");
    }

    /** 放行时把实际传给 mapper 的条件抓出来 */
    private MyRoleBean captureQueryBean() {
        ArgumentCaptor<MyRoleBean> captor = ArgumentCaptor.forClass(MyRoleBean.class);
        verify(roleBeanMapper).selectPageLike(any(PageRequest.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void nonAdmin_withoutIid_shouldBeScopedToOwnRole() {
        MyRoleBean bean = new MyRoleBean();
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 7L));
        when(roleBeanMapper.selectPageLike(any(PageRequest.class), any(MyRoleBean.class)))
                .thenReturn(new Result<PageResult<MyRoleBean>>());

        holder.queryForCaller("u1", bean, page());

        assertEquals(7L, captureQueryBean().getIid(),
                "非管理员未指定 iid 时，必须被强制收敛到自己所属的角色");
    }

    @Test
    void nonAdmin_withOwnIid_shouldBeAllowed() {
        MyRoleBean bean = new MyRoleBean();
        bean.setIid(7L);
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 7L));
        when(roleBeanMapper.selectPageLike(any(PageRequest.class), any(MyRoleBean.class)))
                .thenReturn(new Result<PageResult<MyRoleBean>>());

        holder.queryForCaller("u1", bean, page());

        assertEquals(7L, captureQueryBean().getIid());
    }

    @Test
    void nonAdmin_withOtherRoleIid_shouldBeRejected_andNotTouchDb() {
        MyRoleBean bean = new MyRoleBean();
        bean.setIid(99999L);   // 别人的角色
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 7L));

        assertRejected(holder.queryForCaller("u1", bean, page()));
        verify(roleBeanMapper, never()).selectPageLike(any(PageRequest.class), any(MyRoleBean.class));
    }

    @Test
    void admin_canQueryAnyRole() {
        MyRoleBean bean = new MyRoleBean();
        bean.setIid(99999L);
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(roleBeanMapper.selectPageLike(any(PageRequest.class), any(MyRoleBean.class)))
                .thenReturn(new Result<PageResult<MyRoleBean>>());

        holder.queryForCaller("admin", bean, page());

        assertEquals(99999L, captureQueryBean().getIid(),
                "管理员应当可以查询任意角色");
    }

    @Test
    void nullUid_shouldBeRejected() {
        assertRejected(holder.queryForCaller(null, new MyRoleBean(), page()));
        verify(roleBeanMapper, never()).selectPageLike(any(PageRequest.class), any(MyRoleBean.class));
    }

    @Test
    void userWithoutRole_shouldBeRejected() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", null));

        assertRejected(holder.queryForCaller("u1", new MyRoleBean(), page()));
        verify(roleBeanMapper, never()).selectPageLike(any(PageRequest.class), any(MyRoleBean.class));
    }

    @Test
    void adminPath_shouldNotLeakOwnRoleOverwrite() {
        // 管理员路径下不应改写调用方传入的 iid（避免误伤管理后台的筛选条件）
        MyRoleBean bean = new MyRoleBean();
        bean.setIid(5L);
        when(myUserBaseDbHolder.isAdmin("admin")).thenReturn(true);
        when(roleBeanMapper.selectPageLike(any(PageRequest.class), any(MyRoleBean.class)))
                .thenReturn(new Result<PageResult<MyRoleBean>>());

        holder.queryForCaller("admin", bean, page());

        assertNotNull(captureQueryBean());
        assertEquals(5L, captureQueryBean().getIid());
    }
}
