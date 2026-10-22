package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiGatewaySummary {
    private long totalCalls;
    private long successCalls;
    private long failedCalls;
    private long blockedCalls;
    private long totalTokens;
    private long avgLatencyMs;
    /** 百分比，0-100 */
    private double successRate;
    /** 统计是否因为行数上限而截断 */
    private boolean truncated;
    private List<AiGatewayTrendPoint> trend = new ArrayList<AiGatewayTrendPoint>();
    private List<AiGatewayModuleStat> modules = new ArrayList<AiGatewayModuleStat>();
    private List<AiGatewayUserStat> topUsers = new ArrayList<AiGatewayUserStat>();
}
