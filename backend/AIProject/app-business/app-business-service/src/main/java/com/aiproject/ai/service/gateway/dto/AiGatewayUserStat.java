package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

@Data
public class AiGatewayUserStat {
    private String userId;
    private String userName;
    private long calls;
    private long tokens;
}
