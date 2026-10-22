package com.aiproject.ai.permission.bean;

import blxt.qjava.autovalue.enumbean.JoinEnum;
import blxt.qjava.autovalue.inter.SelectLink;
import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 标签
 *
 * @author zhangjialei
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "标签子项", description = "标签子项")
public class LableChildBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty(value = "标签组iid")
    Long lableiid;

    @SelectLink(
            joinTableB = "lablebean",
            joinKeyA = "lableiid",
            joinKeyB = "iid",
            joinColumB = "name",
            join = JoinEnum.PG_LEFT_OUTER_JOIN
    )
    @ApiModelProperty(value = "标签名称")
    String name;

    @SqlColumn()
    @ApiModelProperty(value = "标签类型，对应模块")
    String type;

    @SqlColumn()
    @ApiModelProperty(value = "hash主键，被引用的数据主键")
    String hash;

    @ApiModelProperty(value = "标签组集合")
    List<LableBean> lableBeans;
}
