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
 * @ClassName ModelsBean
 * @description: TODO
 * @date 2026年05月13日
 * @version: 1.0
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "模型仓库", description = "模型仓库")
public class ModelsBean extends DbEntityBase {
    @SelectLike
    @SqlColumn()
    @ApiModelProperty(value = "模型名称")
    String name;

    @SqlColumn()
    @ApiModelProperty(value = "模型分类")
    String category;

    @SqlColumn()
    @ApiModelProperty(value = "模型描述")
    String description;

    @SqlColumn()
    @ApiModelProperty(value = "模型大小")
    String size;

    @SqlColumn()
    @ApiModelProperty(value = "提供商")
    String provider;

    @SqlColumn()
    @ApiModelProperty(value = "标签数组")
    String tags;

    @SqlColumn()
    @ApiModelProperty(value = "下载链接")
    String downloadUrl;

    @SqlColumn(installEnable = false, function = "(select count(1) from modelsdownloadsbean modelsdownloadsbean_t where modelsdownloadsbean_t.moudelsiid=modelsbean_t.iid) as downloads")
    @ApiModelProperty(value = "下载次数")
    Integer downloads;

}
