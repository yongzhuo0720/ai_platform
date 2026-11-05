package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.AgentStepBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = AgentStepBean.class, tablename = "agentstepbean")
public interface AgentStepBeanMapper<T> extends SqlMapperInterface {

}
