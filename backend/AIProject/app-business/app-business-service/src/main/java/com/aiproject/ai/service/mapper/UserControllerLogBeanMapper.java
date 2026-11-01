package com.aiproject.ai.service.mapper;

import com.aiproject.ai.service.bean.UserControllerLogBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;

/**
 * 操作日志查询。
 * <p>
 * {@code tablename} 必须显式写：类名默认推导出来的表名是
 * {@code usercontrollerlogbean}，而这张表是框架建的 {@code usercontrollerlog}。
 * 这里也没有 {@code createTable}——表和数据都由框架维护，本项目不建表、不写表。
 */
@HnMapper(classes = UserControllerLogBean.class, tablename = "usercontrollerlog")
public interface UserControllerLogBeanMapper<T> extends SqlMapperInterface {

}
