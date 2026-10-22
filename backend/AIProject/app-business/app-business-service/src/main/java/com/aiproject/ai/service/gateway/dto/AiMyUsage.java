package com.aiproject.ai.service.gateway.dto;

import com.aiproject.ai.service.bean.AiCallLogBean;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 普通用户的个人用量视图：今日配额 + 区间内的个人调用统计与最近调用记录。
 */
@Data
public class AiMyUsage {
    /** 今日已用 tokens */
    private long usedToday;
    /** 生效配额（0 表示不限制） */
    private long effectiveQuota;
    /** 今日剩余 tokens（不限制时为 -1） */
    private long remainingTokens;
    /** 今日用量占比（不限制时为 0） */
    private double usagePercent;
    private AiGatewaySummary usage = new AiGatewaySummary();
    private List<AiCallLogBean> recentLogs = new ArrayList<AiCallLogBean>();
}
