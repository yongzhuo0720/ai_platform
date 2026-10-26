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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 助手列表与更新的安全测试。
 *
 * <p>覆盖两类曾经真实存在的问题：</p>
 * <ol>
 *   <li>authToken 泄露——超管创建的助手对所有登录用户可见，
 *       修复前列表接口把 authToken 一起返回，任何人都能拿到他人的接口 Bearer Token；</li>
 *   <li>update 可改 owner——data.userId 会进 UPDATE 的 SET 子句，
 *       修复前未强制为当前 uid，所有者能把助手转让给任意 uid。</li>
 * </ol>
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

    private AssistantBean assistant(long iid, String owner, String authToken) {
        AssistantBean a = new AssistantBean();
        a.setIid(iid);
        a.setUserId(owner);
        a.setAuthToken(authToken);
        a.setPermissionType("public");
        a.setRoleiids("[]");
        a.setUnitsnos("[]");
        return a;
    }

    /** 自己的助手：authToken 要留着，否则前端调不了智能体 */
    @Test
    void queryList_shouldKeepAuthTokenForOwner() {
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 5L));
        when(assistantBeanMapper.select(any(), any(AssistantBean.class)))
                .thenReturn(Collections.<AssistantBean>singletonList(assistant(1L, "u1", "secret-token")));

        Result<List<AssistantBean>> result = holder.queryList("u1");

        assertEquals(1, result.getDatas().size());
        assertEquals("secret-token", result.getDatas().get(0).getAuthToken());
    }

    /**
     * 他人的助手：authToken 必须被抹掉。
     * 「可见」不等于「可拿走凭据」。
     */
    @Test
    void queryList_shouldStripAuthTokenForNonOwner() {
        // 调用者是普通用户；助手由超管创建，因此对所有人可见
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 5L));
        when(myUserBaseDbHolder.queryUserByUid("admin")).thenReturn(user("admin", 99999L));
        when(assistantBeanMapper.select(any(), any(AssistantBean.class)))
                .thenReturn(Collections.<AssistantBean>singletonList(assistant(1L, "admin", "admin-secret")));

        Result<List<AssistantBean>> result = holder.queryList("u1");

        assertEquals(1, result.getDatas().size(), "超管创建的助手对其他人可见");
        assertNull(result.getDatas().get(0).getAuthToken(),
                "非所有者绝不能拿到他人的 authToken");
    }

    /** update 必须把 data.userId 收敛为当前登录 uid，否则可转让助手 */
    @Test
    void update_shouldForceOwnerToCurrentUser() {
        AssistantBean data = assistant(1L, "attacker", "token");
        AssistantBean where = assistant(1L, "victim", null);
        when(assistantBeanMapper.update(any(AssistantBean.class), any(AssistantBean.class))).thenReturn(1);

        Result<Boolean> result = holder.update("u1", data, where);

        assertTrue(Boolean.TRUE.equals(result.getDatas()));

        ArgumentCaptor<AssistantBean> captor = ArgumentCaptor.forClass(AssistantBean.class);
        verify(assistantBeanMapper).update(captor.capture(), any(AssistantBean.class));
        assertEquals("u1", captor.getValue().getUserId(),
                "data.userId 必须被强制覆盖为当前登录 uid");
    }
}
