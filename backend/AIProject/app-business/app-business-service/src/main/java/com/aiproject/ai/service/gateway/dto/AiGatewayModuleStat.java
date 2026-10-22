package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

@Data
public class AiGatewayModuleStat {
    private String module;
    private long calls;
    private long tokens;
    private long failed;
}
