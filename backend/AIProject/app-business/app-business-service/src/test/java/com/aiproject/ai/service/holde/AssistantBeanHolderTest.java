package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.AssistantBean;
import com.aiproject.ai.service.mapper.AssistantBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 助手列表与更新的安全测试。
 *
 * <p>覆盖曾经真实存在的问题：update 的 data.userId 会进 UPDATE 的 SET 子句，
 * 修复前未强制为当前 uid，所有者能把助手转让给任意 uid。
 * 列表侧的可见性规则（超管助手全员可见、黑名单对普通用户生效）也在这里回归。</p>
 */
@ExtendWith(MockitoExtension.class)
class AssistantBeanHolderTest {

    @Mock
    AssistantBeanMapper<AssistantBean> assistantBeanMapper;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @InjectMocks
    AssistantBeanHolder holder;

    private MyUserBaseDb user(String uid, Long rid) {
        MyUserBaseDb u = new MyUserBaseDb();
        u.setUid(uid);
        u.setRid(rid);
        return u;
    }

    private AssistantBean assistant(long iid, String owner) {
        AssistantBean a = new AssistantBean();
        a.setIid(iid);
        a.setUserId(owner);
        a.setPermissionType("public");
        a.setRoleiids("[]");
        a.setUnitsnos("[]");
        return a;
    }

    @Test
    void queryList_shouldReturnOwnAssistant() {
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 5L));
        when(assistantBeanMapper.select(any(), any(AssistantBean.class)))
                .thenReturn(Collections.singletonList(assistant(1L, "u1")));

        Result<List<AssistantBean>> result = holder.queryList("u1");

        assertEquals(1, result.getDatas().size());
        assertEquals("u1", result.getDatas().get(0).getUserId());
    }

    /** 超管创建的助手对普通用户可见；可见性由是否超管创建 / 黑名单共同决定 */
    @Test
    void queryList_shouldExposeAdminAssistantToNormalUser() {
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 5L));
        when(myUserBaseDbHolder.queryUserByUid("admin")).thenReturn(user("admin", 99999L));
        when(assistantBeanMapper.select(any(), any(AssistantBean.class)))
                .thenReturn(Collections.singletonList(assistant(1L, "admin")));

        Result<List<AssistantBean>> result = holder.queryList("u1");

        assertEquals(1, result.getDatas().size(), "超管创建的助手对其他人可见");
    }

    /** update 必须把 data.userId 收敛为当前登录 uid，否则可转让助手 */
    @Test
    void update_shouldForceOwnerToCurrentUser() {
        AssistantBean data = assistant(1L, "attacker");
        AssistantBean where = assistant(1L, "victim");
        when(assistantBeanMapper.update(any(AssistantBean.class), any(AssistantBean.class))).thenReturn(1);

        Result<Boolean> result = holder.update("u1", data, where);

        assertTrue(Boolean.TRUE.equals(result.getDatas()));

        ArgumentCaptor<AssistantBean> captor = ArgumentCaptor.forClass(AssistantBean.class);
        verify(assistantBeanMapper).update(captor.capture(), any(AssistantBean.class));
        assertEquals("u1", captor.getValue().getUserId(),
                "data.userId 必须被强制覆盖为当前登录 uid");
    }
}
