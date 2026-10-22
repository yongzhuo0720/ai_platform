package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "Dify配置管理", description = "Dify配置管理")
public class DifyConfigBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty(value = "模块名称")
    String moduleName;

    @SqlColumn()
    @ApiModelProperty(value = "Dify API URL")
    String apiUrl;

    @SqlColumn()
    @ApiModelProperty(value = "Dify API Key")
    String apiKey;

    @SqlColumn()
    @ApiModelProperty(value = "描述")
    String description;
}
