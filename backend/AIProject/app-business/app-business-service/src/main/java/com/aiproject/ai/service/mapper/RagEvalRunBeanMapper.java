package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.RagEvalRunBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = RagEvalRunBean.class, tablename = "ragevalrunbean")
public interface RagEvalRunBeanMapper<T> extends SqlMapperInterface {

}
