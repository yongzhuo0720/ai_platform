package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = AiCallLogBean.class, tablename = "aicalllogbean")
public interface AiCallLogBeanMapper<T> extends SqlMapperInterface {

}
