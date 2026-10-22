package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.mapper.MyUserBaseDbMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 超管判定 isAdmin() 的单元测试。
 *
 * 为什么先测这个：
 * 它是本项目所有「平台级目录写操作鉴权」的唯一入口
 * （能力广场 / 数据集 / 模型 / 应用 / Dify 配置 / 模型配置，以及改他人信息等）。
 * 一旦它误判，越权防护全部失效。
 *
 * 早期版本在这里直接 userBaseDb.getRid().equals(99999L)，
 * 账号不存在或 rid 为空时会 NPE 导致接口 500；现在的写法把这些边界都判定为「非管理员」。
 */
@ExtendWith(MockitoExtension.class)
class MyUserBaseDbHolderTest {

    @Mock
    MyUserBaseDbMapper<MyUserBaseDb> userbaseMapper;

    @InjectMocks
    MyUserBaseDbHolder holder;

    private MyUserBaseDb userWithRid(Long rid) {
        MyUserBaseDb user = new MyUserBaseDb();
        user.setUid("u1");
        user.setRid(rid);
        return user;
    }

    /** rid = 99999 是超级管理员 */
    @Test
    void isAdmin_shouldBeTrue_whenRidIsSuperAdmin() {
        when(userbaseMapper.selectById("u1")).thenReturn(userWithRid(99999L));
        assertTrue(holder.isAdmin("u1"));
    }

    /** 普通角色不是管理员 */
    @Test
    void isAdmin_shouldBeFalse_whenRidIsNormalRole() {
        when(userbaseMapper.selectById("u1")).thenReturn(userWithRid(1L));
        assertFalse(holder.isAdmin("u1"));
    }

    /** rid 为空：不能误判为管理员，也不能抛 NPE */
    @Test
    void isAdmin_shouldBeFalse_whenRidIsNull() {
        when(userbaseMapper.selectById("u1")).thenReturn(userWithRid(null));
        assertFalse(holder.isAdmin("u1"));
    }

    /** 账号不存在：按非管理员处理 */
    @Test
    void isAdmin_shouldBeFalse_whenUserNotFound() {
        when(userbaseMapper.selectById("u1")).thenReturn(null);
        assertFalse(holder.isAdmin("u1"));
    }

    /** uid 为空（未登录）：直接拒绝，且不查库 */
    @Test
    void isAdmin_shouldBeFalse_whenUidIsNull() {
        assertFalse(holder.isAdmin(null));
    }
}
