package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.mapper.AgentBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 配置数据层的安全边界。
 *
 * <p>重点关注三类：① 不接受前端传入的归属与主键；② update/delete 拒绝空条件
 * （否则会命中整张表）；③ 所有查询与修改条件都带 lastuser。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentConfigHolderTest {

    private static final String UID = "u1";

    @Mock
    AgentBeanMapper<AgentBean> configMapper;

    @InjectMocks
    AgentConfigHolder holder;

    private AgentBean request(String name) {
        AgentBean data = new AgentBean();
        data.setName(name);
        data.setSystemPrompt("你是知识库助手");
        return data;
    }

    @Test
    void create_shouldForceOwnerAndGenerateAgentKey() {
        when(configMapper.install(any(AgentBean.class))).thenReturn(1);
        AgentBean data = request("报销助手");
        data.setIid(999L);              // 前端伪造主键
        data.setAgentKey("fake-key");   // 前端伪造业务键
        data.setLastuser("someone-else");

        Result<AgentBean> result = holder.create(UID, data);

        assertNotNull(result.getDatas());
        assertEquals(UID, data.getLastuser());
        assertNull(data.getIid(), "新建不能沿用前端传来的 iid");
        assertFalse("fake-key".equals(data.getAgentKey()), "业务键必须由服务端生成");
        assertNotNull(data.getAgentKey());
        assertEquals(Integer.valueOf(8), data.getMaxIterations());
        assertEquals(Long.valueOf(20000L), data.getTokenBudget());
        assertEquals("only_me", data.getPermissionType());
    }

    @Test
    void create_shouldRejectBlankName() {
        Result<AgentBean> result = holder.create(UID, request("  "));
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("名称"));
        verify(configMapper, never()).install(any(AgentBean.class));
    }

    @Test
    void create_shouldRejectTooLongPrompt() {
        AgentBean data = request("助手");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20001; i++) {
            sb.append('x');
        }
        data.setSystemPrompt(sb.toString());
        Result<AgentBean> result = holder.create(UID, data);
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("System Prompt"));
        verify(configMapper, never()).install(any(AgentBean.class));
    }

    @Test
    void update_shouldRejectMissingIid() {
        Result<Boolean> result = holder.update(UID, request("助手"));
        assertEquals(Boolean.FALSE, result.getDatas());
        assertTrue(result.getResp_msg().contains("主键"));
        verify(configMapper, never()).update(any(AgentBean.class), any(AgentBean.class));
    }

    @Test
    void update_shouldRejectOtherUsersAgent() {
        when(configMapper.select(any(), any(AgentBean.class)))
                .thenReturn(Collections.<AgentBean>emptyList());
        AgentBean data = request("助手");
        data.setIid(7L);
        Result<Boolean> result = holder.update(UID, data);
        assertEquals(Boolean.FALSE, result.getDatas());
        assertTrue(result.getResp_msg().contains("不属于当前用户"));
        verify(configMapper, never()).update(any(AgentBean.class), any(AgentBean.class));
    }

    @Test
    void update_shouldScopeWhereByOwnerAndNeverTouchAgentKey() {
        AgentBean existing = new AgentBean();
        existing.setIid(7L);
        when(configMapper.select(any(), any(AgentBean.class)))
                .thenReturn(Collections.singletonList(existing));
        when(configMapper.update(any(AgentBean.class), any(AgentBean.class))).thenReturn(1);

        AgentBean data = request("新名字");
        data.setIid(7L);
        Result<Boolean> result = holder.update(UID, data);
        assertEquals(Boolean.TRUE, result.getDatas());

        ArgumentCaptor<AgentBean> patch = ArgumentCaptor.forClass(AgentBean.class);
        ArgumentCaptor<AgentBean> where = ArgumentCaptor.forClass(AgentBean.class);
        verify(configMapper).update(patch.capture(), where.capture());
        assertEquals(UID, where.getValue().getLastuser());
        assertEquals(Long.valueOf(7L), where.getValue().getIid());
        assertNull(patch.getValue().getAgentKey(), "业务键不允许被 update 修改");
        assertNull(patch.getValue().getIid(), "iid 不能进入 SET 子句");
        assertEquals("新名字", patch.getValue().getName());
    }

    @Test
    void delete_shouldRejectMissingIid() {
        Result<Boolean> result = holder.delete(UID, null);
        assertEquals(Boolean.FALSE, result.getDatas());
        verify(configMapper, never()).delete(any(AgentBean.class));
    }

    @Test
    void list_shouldScopeByOwner() {
        when(configMapper.select(any(PageRequest.class), any(AgentBean.class)))
                .thenReturn(new ArrayList<AgentBean>());
        holder.list(UID);
        ArgumentCaptor<AgentBean> where = ArgumentCaptor.forClass(AgentBean.class);
        verify(configMapper).select(any(PageRequest.class), where.capture());
        assertEquals(UID, where.getValue().getLastuser());
    }

    @Test
    void create_shouldRejectUnknownPermissionType() {
        AgentBean data = request("助手");
        data.setPermissionType("everyone_can_see");
        Result<AgentBean> result = holder.create(UID, data);
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("可见性"));
        verify(configMapper, never()).install(any(AgentBean.class));
    }

    @Test
    void create_shouldRejectTooManyIterations() {
        AgentBean data = request("助手");
        data.setMaxIterations(999);
        Result<AgentBean> result = holder.create(UID, data);
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("迭代步数"));
    }

    @Test
    void update_shouldRejectHugeTokenBudgetBeforeTouchingDb() {
        AgentBean data = request("助手");
        data.setIid(7L);
        data.setTokenBudget(99999999L);
        Result<Boolean> result = holder.update(UID, data);
        assertEquals(Boolean.FALSE, result.getDatas());
        assertTrue(result.getResp_msg().contains("Token 预算"));
        verify(configMapper, never()).select(any(), any(AgentBean.class));
    }

    @Test
    void anonymous_shouldBeRejectedOnEveryEntry() {
        assertNull(holder.list(null).getDatas());
        assertNull(holder.create(null, request("助手")).getDatas());
        assertEquals(Boolean.FALSE, holder.update(null, request("助手")).getDatas());
        assertEquals(Boolean.FALSE, holder.delete(null, 1L).getDatas());
        verify(configMapper, never()).install(any(AgentBean.class));
        verify(configMapper, never()).update(any(AgentBean.class), any(AgentBean.class));
        verify(configMapper, never()).delete(any(AgentBean.class));
    }
}
