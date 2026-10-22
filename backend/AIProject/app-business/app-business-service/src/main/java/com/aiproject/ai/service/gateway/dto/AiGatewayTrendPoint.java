package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

@Data
public class AiGatewayTrendPoint {
    private String date;
    private long calls;
    private long tokens;
    private long failed;
}
