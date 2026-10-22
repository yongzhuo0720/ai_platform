package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = AiUserQuotaBean.class, tablename = "aiuserquotabean")
public interface AiUserQuotaBeanMapper<T> extends SqlMapperInterface {

}
