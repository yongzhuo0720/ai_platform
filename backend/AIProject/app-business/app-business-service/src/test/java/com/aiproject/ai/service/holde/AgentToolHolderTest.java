package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.AgentToolBean;
import com.aiproject.ai.service.mapper.AgentBeanMapper;
import com.aiproject.ai.service.mapper.AgentToolBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工具注册的安全边界：Schema 必填、类型白名单、Agent 归属、空条件拒绝。 */
@ExtendWith(MockitoExtension.class)
class AgentToolHolderTest {

    private static final String UID = "u1";

    @Mock
    AgentToolBeanMapper<AgentToolBean> toolMapper;

    @Mock
    AgentBeanMapper<AgentBean> configMapper;

    @InjectMocks
    AgentToolHolder holder;

    private AgentToolBean request() {
        AgentToolBean data = new AgentToolBean();
        data.setAgentIid(7L);
        data.setName("kb_search");
        data.setJsonSchema("{\"type\":\"object\"}");
        data.setToolType("kb_search");
        return data;
    }

    private void stubOwnedAgent() {
        when(configMapper.select(any(), any(AgentBean.class)))
                .thenReturn(Collections.singletonList(new AgentBean()));
    }

    @Test
    void save_shouldRejectMissingJsonSchema() {
        AgentToolBean data = request();
        data.setJsonSchema(null);
        Result<AgentToolBean> result = holder.save(UID, data);
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("Schema"));
        verify(toolMapper, never()).install(any(AgentToolBean.class));
    }

    @Test
    void save_shouldRejectUnknownToolType() {
        AgentToolBean data = request();
        data.setToolType("shell_exec");
        Result<AgentToolBean> result = holder.save(UID, data);
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("不支持的工具类型"));
        verify(toolMapper, never()).install(any(AgentToolBean.class));
    }

    @Test
    void save_shouldRejectBlankName() {
        AgentToolBean data = request();
        data.setName("  ");
        Result<AgentToolBean> result = holder.save(UID, data);
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("工具名称"));
    }

    @Test
    void save_shouldRejectOtherUsersAgent() {
        when(configMapper.select(any(), any(AgentBean.class)))
                .thenReturn(Collections.<AgentBean>emptyList());
        Result<AgentToolBean> result = holder.save(UID, request());
        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("不属于当前用户"));
        verify(toolMapper, never()).install(any(AgentToolBean.class));
    }

    @Test
    void save_shouldCreateWithDefaultsAndOwner() {
        stubOwnedAgent();
        when(toolMapper.install(any(AgentToolBean.class))).thenReturn(1);
        AgentToolBean data = request();
        data.setLastuser("attacker");

        Result<AgentToolBean> result = holder.save(UID, data);

        assertNotNull(result.getDatas());
        assertEquals(UID, data.getLastuser());
        assertEquals(Integer.valueOf(8000), data.getTimeoutMs());
        assertEquals(Integer.valueOf(0), data.getMaxRetries());
        assertEquals("[]", data.getRoleiids());
    }

    @Test
    void save_shouldScopeUpdateByOwner() {
        stubOwnedAgent();
        when(toolMapper.select(any(), any(AgentToolBean.class)))
                .thenReturn(Collections.singletonList(new AgentToolBean()));
        when(toolMapper.update(any(AgentToolBean.class), any(AgentToolBean.class))).thenReturn(1);
        AgentToolBean data = request();
        data.setIid(11L);

        Result<AgentToolBean> result = holder.save(UID, data);
        assertNotNull(result.getDatas());

        ArgumentCaptor<AgentToolBean> patch = ArgumentCaptor.forClass(AgentToolBean.class);
        ArgumentCaptor<AgentToolBean> where = ArgumentCaptor.forClass(AgentToolBean.class);
        verify(toolMapper).update(patch.capture(), where.capture());
        assertEquals(UID, where.getValue().getLastuser());
        assertEquals(Long.valueOf(11L), where.getValue().getIid());
        assertNull(patch.getValue().getIid(), "iid 不能进入 SET 子句");
    }

    @Test
    void list_shouldRequireAgentIid() {
        Result<?> result = holder.list(UID, null);
        assertNull(result.getDatas());
        verify(toolMapper, never()).select(any(), any(AgentToolBean.class));
    }

    @Test
    void delete_shouldRequireIidAndScopeByOwner() {
        assertEquals(Boolean.FALSE, holder.delete(UID, null).getDatas());
        verify(toolMapper, never()).delete(any(AgentToolBean.class));

        when(toolMapper.delete(any(AgentToolBean.class))).thenReturn(1);
        assertEquals(Boolean.TRUE, holder.delete(UID, 5L).getDatas());
        ArgumentCaptor<AgentToolBean> where = ArgumentCaptor.forClass(AgentToolBean.class);
        verify(toolMapper).delete(where.capture());
        assertEquals(UID, where.getValue().getLastuser());
        assertEquals(Long.valueOf(5L), where.getValue().getIid());
    }

    @Test
    void anonymous_shouldBeRejected() {
        assertNull(holder.list(null, 7L).getDatas());
        assertNull(holder.save(null, request()).getDatas());
        assertEquals(Boolean.FALSE, holder.delete(null, 1L).getDatas());
        verify(toolMapper, never()).install(any(AgentToolBean.class));
    }
}
