package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.KbChunkBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = KbChunkBean.class, tablename = "kbchunkbean")
public interface KbChunkBeanMapper<T> extends SqlMapperInterface {

}
