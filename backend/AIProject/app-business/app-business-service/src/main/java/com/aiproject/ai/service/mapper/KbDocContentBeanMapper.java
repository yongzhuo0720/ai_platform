package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.KbDocContentBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = KbDocContentBean.class, tablename = "kbdoccontentbean")
public interface KbDocContentBeanMapper<T> extends SqlMapperInterface {

}
