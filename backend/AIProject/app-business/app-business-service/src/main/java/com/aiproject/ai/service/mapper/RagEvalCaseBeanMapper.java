package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.RagEvalCaseBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = RagEvalCaseBean.class, tablename = "ragevalcasebean")
public interface RagEvalCaseBeanMapper<T> extends SqlMapperInterface {

}
