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
    /** 95 分位耗时毫秒 */
    private long p95LatencyMs;
    /** 百分比，0-100 */
    private double successRate;
    /** 统计是否因为行数上限而截断 */
    private boolean truncated;
    private List<AiGatewayTrendPoint> trend = new ArrayList<AiGatewayTrendPoint>();
    private List<AiGatewayModuleStat> modules = new ArrayList<AiGatewayModuleStat>();
    /** 按模型细分的调用统计（modelName 为空的记为「未知模型」） */
    private List<AiGatewayModelStat> models = new ArrayList<AiGatewayModelStat>();
    private List<AiGatewayUserStat> topUsers = new ArrayList<AiGatewayUserStat>();
}
