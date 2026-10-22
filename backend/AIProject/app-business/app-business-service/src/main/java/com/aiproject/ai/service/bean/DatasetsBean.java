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
 * @ClassName DatasetsBean
 * @description: TODO
 * @date 2026年05月13日
 * @version: 1.0
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "数据集仓库", description = "数据集仓库")
public class DatasetsBean extends DbEntityBase {
    @SelectLike
    @SqlColumn()
    @ApiModelProperty(value = "数据集名称")
    String name;

    @SqlColumn()
    @ApiModelProperty(value = "数据类型（音频/文本）")
    String type;

    @SqlColumn()
    @ApiModelProperty(value = "数据集描述")
    String description;

    @SqlColumn()
    @ApiModelProperty(value = "文件大小")
    String size;

    @SqlColumn()
    @ApiModelProperty(value = "数据量")
    String volume;

    @SqlColumn()
    @ApiModelProperty(value = "下载链接")
    String downloadUrl;

//    @SqlColumn()
//    @ApiModelProperty(value = "指标数据数组")
//    String metrics;
}
