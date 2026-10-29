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

    /**
     * 仅用于列表响应：该模块是否已配置密钥。
     * <p>查询接口会把 apiKey 脱敏为空，前端据此展示「已配置 / 未配置」。
     * 不加 @SqlColumn，SQL 读写与建表都会忽略该字段。</p>
     */
    @ApiModelProperty(value = "是否已配置 API Key（密钥本身不下发）")
    Boolean apiKeyConfigured;

    @SqlColumn()
    @ApiModelProperty(value = "描述")
    String description;
}
