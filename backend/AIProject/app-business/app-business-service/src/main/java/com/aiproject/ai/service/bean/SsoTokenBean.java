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
 * @ClassName SsoTokenBean
 * @description: TODO
 * @date 2026年05月13日
 * @version: 1.0
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "SSO 单点登录 Token 存储", description = "SSO 单点登录 Token 存储")
public class SsoTokenBean extends DbEntityBase {
    @SqlColumn()
    @ApiModelProperty(value = "关联本地用户唯一标识")
    String uid;

    @SqlColumn()
    @ApiModelProperty(value = "SSO 平台签发的访问令牌（Bearer Token）")
    String access_token;

    @SqlColumn()
    @ApiModelProperty(value = "SSO 平台签发的刷新令牌，用于 access_token 过期后续期")
    String refresh_token;

    @SqlColumn()
    @ApiModelProperty(value = "access_token 过期时间戳（毫秒）")
    Long expires_at;

}
