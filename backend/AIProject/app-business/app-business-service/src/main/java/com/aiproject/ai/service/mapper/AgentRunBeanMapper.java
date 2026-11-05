package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.AgentRunBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = AgentRunBean.class, tablename = "agentrunbean")
public interface AgentRunBeanMapper<T> extends SqlMapperInterface {

}
