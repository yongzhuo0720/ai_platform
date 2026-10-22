package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

@Data
public class AiGatewayModelStat {
    private String model;
    private long calls;
    private long tokens;
    private long failed;
    private long avgLatencyMs;
}
