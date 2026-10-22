package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SelectLike;
import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @author jiaoyu
 * @ClassName CapabilitiesBean
 * @description: TODO
 * @date 2026年05月13日
 * @version: 1.0
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "能力广场", description = "能力广场")
public class CapabilitiesBean extends DbEntityBase {
    @SelectLike
    @SqlColumn()
    @ApiModelProperty(value = "能力名称")
    String title;

    @SqlColumn()
    @ApiModelProperty(value = "能力描述")
    String description;

    @SqlColumn()
    @ApiModelProperty(value = "能力标签/分类")
    String label;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "能力图标（base64 或图片 URL，为空时前端显示默认图）")
    String icon;

//    @SqlColumn(defaultValue = "false")
//    @ApiModelProperty(value = "是否需要单点登录")
//    Boolean needSso;

    @SqlColumn()
    @ApiModelProperty(value = "链接")
    String difyUrl;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "权限id组")
    String roleiids;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "部门编号组")
    String unitsnos;
}
