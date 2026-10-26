package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.ChatShareBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = ChatShareBean.class, tablename = "chatsharebean")
public interface ChatShareBeanMapper<T> extends SqlMapperInterface {

}
