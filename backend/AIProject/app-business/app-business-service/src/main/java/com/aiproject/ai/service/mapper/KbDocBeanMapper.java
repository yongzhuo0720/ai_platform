package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.KbDocBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = KbDocBean.class, tablename = "kbdocbean")
public interface KbDocBeanMapper<T> extends SqlMapperInterface {

}
