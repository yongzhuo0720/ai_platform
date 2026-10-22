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
    private String remark;
    private boolean custom;
}
