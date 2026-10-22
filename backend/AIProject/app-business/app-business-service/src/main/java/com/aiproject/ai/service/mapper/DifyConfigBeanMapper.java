package com.aiproject.ai.service.mapper;

import com.heneng.hnsp.common.inter.HnMapper;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.aiproject.ai.service.bean.DifyConfigBean;

@HnMapper(classes = DifyConfigBean.class, tablename = "difyconfigbean")
public interface DifyConfigBeanMapper<T> extends SqlMapperInterface {

}
