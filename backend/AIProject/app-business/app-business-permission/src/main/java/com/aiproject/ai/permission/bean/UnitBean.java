package com.aiproject.ai.permission.bean;

import blxt.qjava.autovalue.inter.SelectLike;
import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 功能描述: 部门管理.
 *
 * @author jiaoyu
 * @date 2023/2/20 15:03
 **/
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "部门管理", description = "部门管理")
public class UnitBean extends DbEntityBase {

    @SqlColumn
    @ApiModelProperty(value = "部门编号")
    String unitsno;

    @SelectLike
    @SqlColumn
    @ApiModelProperty(value = "部门名称")
    String unitname;

    @SqlColumn
    @ApiModelProperty(value = "上级部门编号,顶级时为0")
    String parentsno;

    @SqlColumn
    @ApiModelProperty(value = "部门电话")
    String unittel;

    @SqlColumn
    @ApiModelProperty(value = "备注")
    String remark;

    @ApiModelProperty(value = "下级部门集合")
    List<UnitBean> childList;

}
