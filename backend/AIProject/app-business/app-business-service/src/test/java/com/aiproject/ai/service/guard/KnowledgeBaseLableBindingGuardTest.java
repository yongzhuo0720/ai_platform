package com.aiproject.ai.service.guard;

import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.aiproject.ai.service.holde.KnowledgeBaseBeanHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseLableBindingGuardTest {

    @Mock
    KnowledgeBaseBeanHolder knowledgeBaseBeanHolder;

    @Mock
    KbRagHolder kbRagHolder;

    @InjectMocks
    KnowledgeBaseLableBindingGuard guard;

    private KnowledgeBaseBean kb(String ownerUid) {
        KnowledgeBaseBean bean = new KnowledgeBaseBean();
        bean.setKbId("kb-1");
        bean.setOwnerUid(ownerUid);
        return bean;
    }

    @Test
    void owner_shouldManage() {
        when(knowledgeBaseBeanHolder.query(any(KnowledgeBaseBean.class))).thenReturn(kb("u1"));
        assertTrue(guard.canManage("u1", "kb-1"));
    }

    @Test
    void nonOwner_shouldNotManage() {
        when(knowledgeBaseBeanHolder.query(any(KnowledgeBaseBean.class))).thenReturn(kb("u2"));
        assertFalse(guard.canManage("u1", "kb-1"));
    }

    @Test
    void unknownKb_shouldNotManage() {
        when(knowledgeBaseBeanHolder.query(any(KnowledgeBaseBean.class))).thenReturn(null);
        // 本地回退也判否（mock 默认返回 false）
        assertFalse(guard.canManage("u1", "kb-404"));
    }

    @Test
    void localKb_ownerCanManage() {
        // knowledgebasebean 无记录 → 回退本地 RAG，用户拥有该 kbName 下文档
        when(knowledgeBaseBeanHolder.query(any(KnowledgeBaseBean.class))).thenReturn(null);
        when(kbRagHolder.isLocalKbOwner("u1", "产品文档库")).thenReturn(true);
        assertTrue(guard.canManage("u1", "产品文档库"),
                "本地知识库所有者应能管理标签绑定");
    }

    @Test
    void localKb_nonOwnerCannotManage() {
        when(knowledgeBaseBeanHolder.query(any(KnowledgeBaseBean.class))).thenReturn(null);
        when(kbRagHolder.isLocalKbOwner("u2", "产品文档库")).thenReturn(false);
        assertFalse(guard.canManage("u2", "产品文档库"),
                "非所有者不能管理他人的本地知识库标签");
    }

    @Test
    void nullUid_shouldNotManage() {
        assertFalse(guard.canManage(null, "kb-1"));
    }
}
