package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.AiApiTokenBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = AiApiTokenBean.class, tablename = "aiapitokenbean")
public interface AiApiTokenBeanMapper<T> extends SqlMapperInterface {

}
