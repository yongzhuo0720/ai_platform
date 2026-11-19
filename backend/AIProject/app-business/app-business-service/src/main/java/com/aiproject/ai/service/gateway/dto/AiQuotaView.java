package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

@Data
public class AiQuotaView {
    private String userId;
    private String userName;
    /** 用户覆盖值，null 表示使用全局默认 */
    private Long dailyTokenQuota;
    private long effectiveQuota;
    private long usedToday;
    /** 每日成本预算（分），null/0=不限制 */
    private Long dailyCostBudgetCents;
    /** 告警阈值百分比 */
    private Integer alertThresholdPct;
    private String remark;
    private boolean custom;
}
