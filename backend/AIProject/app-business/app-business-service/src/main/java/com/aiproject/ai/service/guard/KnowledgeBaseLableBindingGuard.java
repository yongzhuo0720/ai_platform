package com.aiproject.ai.service.guard;

import com.aiproject.ai.permission.spi.LableBindingResourceGuard;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.holde.KnowledgeBaseBeanHolder;
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
        return record != null && uid.equals(record.getOwnerUid());
    }
}
