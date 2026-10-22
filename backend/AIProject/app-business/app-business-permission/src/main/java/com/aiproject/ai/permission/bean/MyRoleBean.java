package com.aiproject.ai.permission.bean;

import blxt.qjava.autovalue.inter.SelectLike;
import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.sql.Timestamp;

@Data
@SqlBean(keyId="iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "角色", description = "角色")
public class MyRoleBean extends DbEntityBase {

    public static String TableName;

    @SelectLike
    @SqlColumn()
    @ApiModelProperty(value = "名称")
    String name;

    @SqlColumn()
    @ApiModelProperty(value = "描述")
    String describe;

    @SqlColumn()
    @ApiModelProperty(value = "租户")
    String tenant;

    @SqlColumn()
    @ApiModelProperty(value = "接口权限组id, 删除请入参 0 " , example = "00")
    Long[] urlgroupids;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "视图json")
    String viewjson;

    @SqlColumn(defaultValue = "now()")
    @ApiModelProperty(value = "结束时间")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    Timestamp endtime;

}
