package com.aiproject.ai.permission.mapper;

import com.aiproject.ai.permission.bean.UnitBean;
import com.heneng.hnsp.common.db.model.SqlMapperInterface;
import com.heneng.hnsp.common.inter.HnMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author openJL
 * @date 2023/02/22 15:24:56
 * 此代码由[HSNP]快速开发框架自动生成
 */
@HnMapper(classes = UnitBean.class, tablename = "unit")
public interface UnitBeanMapper<T> extends SqlMapperInterface {

    /**
     * 查询指定父级下的最大部门编号
     * @param parentsno 父级部门编号
     * @return 最大部门编号
     */
    @Select("SELECT MAX(unitsno) FROM unit WHERE parentsno = #{parentsno}")
    String queryMaxSno(@Param("parentsno") String parentsno);

}
