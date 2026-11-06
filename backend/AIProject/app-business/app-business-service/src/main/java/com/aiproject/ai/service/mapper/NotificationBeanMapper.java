package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.NotificationBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = NotificationBean.class, tablename = "notificationbean")
public interface NotificationBeanMapper<T> extends SqlMapperInterface {

}
