package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.AgentToolBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = AgentToolBean.class, tablename = "agenttoolbean")
public interface AgentToolBeanMapper<T> extends SqlMapperInterface {

}
