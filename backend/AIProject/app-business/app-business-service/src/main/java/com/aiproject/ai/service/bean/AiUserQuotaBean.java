package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 用户 AI 每日 Token 配额覆盖。
 * <p>
 * 没有对应记录时使用配置文件里的默认配额；
 * dailyTokenQuota 为 0 表示该用户不限制。
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "AI用户配额", description = "用户每日Token配额")
public class AiUserQuotaBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("用户uid")
    String userId;

    @SqlColumn()
    @ApiModelProperty("每日Token配额，0=不限制")
    Long dailyTokenQuota;

    @SqlColumn()
    @ApiModelProperty("备注")
    String remark;
}
