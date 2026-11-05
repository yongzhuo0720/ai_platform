package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.RagEvalCaseResultBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = RagEvalCaseResultBean.class, tablename = "ragevalcaseresultbean")
public interface RagEvalCaseResultBeanMapper<T> extends SqlMapperInterface {

}
