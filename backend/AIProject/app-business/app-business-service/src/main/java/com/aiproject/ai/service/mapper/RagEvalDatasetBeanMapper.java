package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.RagEvalDatasetBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = RagEvalDatasetBean.class, tablename = "ragevaldatasetbean")
public interface RagEvalDatasetBeanMapper<T> extends SqlMapperInterface {

}
