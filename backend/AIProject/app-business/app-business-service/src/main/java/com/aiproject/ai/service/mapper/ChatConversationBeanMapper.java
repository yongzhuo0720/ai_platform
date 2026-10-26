package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.ChatConversationBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = ChatConversationBean.class, tablename = "chatconversationbean")
public interface ChatConversationBeanMapper<T> extends SqlMapperInterface {

}
