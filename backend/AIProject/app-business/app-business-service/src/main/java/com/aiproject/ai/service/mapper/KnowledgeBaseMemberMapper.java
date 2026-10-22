package com.aiproject.ai.service.mapper;

import com.heneng.hnsp.common.inter.HnMapper;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.aiproject.ai.service.bean.KnowledgeBaseMemberBean;

@HnMapper(classes = KnowledgeBaseMemberBean.class, tablename = "knowledgememberbean")
public interface KnowledgeBaseMemberMapper<T> extends SqlMapperInterface {
}
