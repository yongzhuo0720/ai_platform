package com.aiproject.ai.service.mapper;

import com.heneng.hnsp.common.inter.HnMapper;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.aiproject.ai.service.bean.AssistantBean;

@HnMapper(classes = AssistantBean.class, tablename = "assistantbean")
public interface AssistantBeanMapper<T> extends SqlMapperInterface {

}
