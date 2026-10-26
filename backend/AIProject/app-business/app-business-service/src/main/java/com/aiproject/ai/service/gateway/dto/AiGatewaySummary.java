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

    /** 平均首字耗时（毫秒）。流式对话体验的核心指标：用户等待第一个字的时间 */
    private long avgFirstTokenMs;
    /** 百分比，0-100 */
    private double successRate;
    /** 统计是否因为行数上限而截断 */
    private boolean truncated;
    /**
     * 总成本，单位「毫分」（1 分 = 1000 毫分）。
     *
     * <p>存分不存元：分是整数，累加不会因浮点误差失真，
     * 前端展示时除以 100 格式化成元即可。</p>
     */
    private long totalCostCents;
    /**
     * 是否存在「调用了但没配价格」的模型。
     *
     * <p>这个标记很重要：没有它，未配置单价的模型会让总成本看起来
     * 比实际低，而用户以为那就是真实账单。有了它，看板可以明确提示
     * 「部分模型未配置价格，总成本偏低」。</p>
     */
    private boolean hasUnpricedModels;
    private List<AiGatewayTrendPoint> trend = new ArrayList<AiGatewayTrendPoint>();
    private List<AiGatewayModuleStat> modules = new ArrayList<AiGatewayModuleStat>();
    /** 按模型细分的调用统计（modelName 为空的记为「未知模型」） */
    private List<AiGatewayModelStat> models = new ArrayList<AiGatewayModelStat>();
    private List<AiGatewayUserStat> topUsers = new ArrayList<AiGatewayUserStat>();
}
