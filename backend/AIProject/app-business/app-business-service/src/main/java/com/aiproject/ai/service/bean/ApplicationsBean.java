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
 * @ClassName ApplicationsBean
 * @description: TODO
 * @date 2026年05月13日
 * @version: 1.0
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "应用构建", description = "应用构建")
public class ApplicationsBean extends DbEntityBase {
    @SelectLike
    @SqlColumn()
    @ApiModelProperty(value = "应用名称")
    String title;

    @SqlColumn()
    @ApiModelProperty(value = "应用详细说明")
    String detail;

    @SqlColumn()
    @ApiModelProperty(value = "运行状态（运行中/测试中）")
    String status;

    @SqlColumn()
    @ApiModelProperty(value = "使用的AI模型")
    String model;

    @SqlColumn()
    @ApiModelProperty(value = "平台链接")
    String difyUrl;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "权限id组")
    String roleiids;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "部门编号组")
    String unitsnos;

}
