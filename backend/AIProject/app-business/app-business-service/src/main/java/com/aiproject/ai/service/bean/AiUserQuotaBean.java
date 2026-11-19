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

    /**
     * 每日成本预算，单位「分」（不是毫分！）。
     * <p>审计日志里的 costCents 是毫分（1 分 = 1000 毫分），
     * 预算用「分」是为了让管理员填直观数字（如 100 分 = 1 元）。
     * 告警时把当日累计毫分 ÷ 1000 换成分再与预算比较。
     * null 或 0 表示不设成本预算（只按 token 配额限流）。</p>
     */
    @SqlColumn()
    @ApiModelProperty("每日成本预算（分），null或0=不限制")
    Long dailyCostBudgetCents;

    /**
     * 告警阈值百分比：当日成本达到预算的这个比例时发 warning 通知。
     * 默认 80，取 1-100；100 表示只在超预算时才告警。
     */
    @SqlColumn()
    @ApiModelProperty("告警阈值百分比，默认80")
    Integer alertThresholdPct;

    @SqlColumn()
    @ApiModelProperty("备注")
    String remark;
}
