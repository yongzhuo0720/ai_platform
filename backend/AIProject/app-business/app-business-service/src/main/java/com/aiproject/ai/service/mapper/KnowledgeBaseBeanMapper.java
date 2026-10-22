package com.aiproject.ai.service.mapper;

import com.heneng.hnsp.common.inter.HnMapper;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;

@HnMapper(classes = KnowledgeBaseBean.class, tablename = "knowledgebasebean")
public interface KnowledgeBaseBeanMapper<T> extends SqlMapperInterface {

}
