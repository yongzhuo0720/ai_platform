package com.aiproject.ai.permission.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 标签
 *
 * @author zhangjialei
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "标签组", description = "标签组")
public class LableBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty(value = "标签名称")
    String name;

    @SqlColumn()
    @ApiModelProperty(value = "标签类型，对应模块")
    String type;

    @SqlColumn()
    @ApiModelProperty(value = "描述")
    String describe;

    @SqlColumn(installEnable = false,function = "(select count(1) from lablechildbean where lableiid=lablebean_t.iid) as count ")
    @ApiModelProperty(value = "引用数量")
    Integer count;

}
