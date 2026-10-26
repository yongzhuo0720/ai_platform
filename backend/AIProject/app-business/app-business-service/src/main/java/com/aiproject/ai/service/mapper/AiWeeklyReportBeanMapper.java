package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.AiWeeklyReportBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

@HnMapper(classes = AiWeeklyReportBean.class, tablename = "aiweeklyreportbean")
public interface AiWeeklyReportBeanMapper<T> extends SqlMapperInterface {

}
