package com.aiproject.ai.service.guard;

import com.aiproject.ai.permission.spi.LableBindingResourceGuard;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.holde.KnowledgeBaseBeanHolder;
import com.aiproject.ai.service.holde.KbRagHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 知识库标签绑定的归属判定：绑定记录 hash 存的是 Dify 知识库 ID，
 * 对应 knowledgebasebean.kbId，所有者字段为 ownerUid。
 * <p>
 * 前端知识库详情页/广场只对所有者与超管展示标签操作，这里在服务端做同样约束，
 * 避免任意登录用户凭他人 kbId 直接调用 /api/lableChildBean/create|delete。
 */
@Component
public class KnowledgeBaseLableBindingGuard implements LableBindingResourceGuard {

    public static final String TYPE = "knowledge_base";

    @Autowired
    KnowledgeBaseBeanHolder knowledgeBaseBeanHolder;

    /**
     * 本地 RAG 知识库归属判定（2026-11-20）。
     * 本地知识库按文档的 kbName 分组，没有 knowledgebasebean 记录，
     * 查不到时回退到 kbdocbean.lastuser 判定所有者。
     */
    @Autowired(required = false)
    KbRagHolder kbRagHolder;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean canManage(String uid, String hash) {
        if (uid == null) {
            return false;
        }
        KnowledgeBaseBean where = new KnowledgeBaseBean();
        where.setKbId(hash);
        KnowledgeBaseBean record = knowledgeBaseBeanHolder.query(where);
        if (record != null) {
            return uid.equals(record.getOwnerUid());
        }
        // 本地 RAG 知识库：knowledgebasebean 无记录，hash 即 kbName。
        // 回退判定：该 kbName 下是否存在属于当前 uid 的文档。
        return kbRagHolder != null && kbRagHolder.isLocalKbOwner(uid, hash);
    }
}
