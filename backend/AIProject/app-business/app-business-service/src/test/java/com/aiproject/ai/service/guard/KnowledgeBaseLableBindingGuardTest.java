package com.aiproject.ai.service.guard;

import com.aiproject.ai.service.bean.KnowledgeBaseBean;
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
        assertFalse(guard.canManage("u1", "kb-404"));
    }

    @Test
    void nullUid_shouldNotManage() {
        assertFalse(guard.canManage(null, "kb-1"));
    }
}
