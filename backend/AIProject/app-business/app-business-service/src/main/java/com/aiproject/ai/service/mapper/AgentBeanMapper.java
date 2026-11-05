package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.AgentBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = AgentBean.class, tablename = "agentbean")
public interface AgentBeanMapper<T> extends SqlMapperInterface {

}
