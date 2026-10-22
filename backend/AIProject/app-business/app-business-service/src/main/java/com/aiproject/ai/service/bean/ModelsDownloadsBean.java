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
 * @ClassName ModelsDownloadsBean
 * @description: TODO
 * @date 2026年05月13日
 * @version: 1.0
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "模型仓库下载记录", description = "模型仓库下载记录")
public class ModelsDownloadsBean extends DbEntityBase {
    @SelectLike
    @SqlColumn()
    @ApiModelProperty(value = "模型表主键")
    Long moudelsiid;

    @SqlColumn()
    @ApiModelProperty(value = "下载人uid")
    String user;

}
