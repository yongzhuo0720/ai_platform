package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

@Data
public class AiGatewayModelStat {
    private String model;
    private long calls;
    private long tokens;
    private long failed;
    private long avgLatencyMs;
    /** 该模型累计成本，单位「毫分」（1 分 = 1000 毫分） */
    private long costCents;
    /** 该模型是否配置了单价；false 时 costCents 为 0 且不代表真实成本 */
    private boolean priced;
}
