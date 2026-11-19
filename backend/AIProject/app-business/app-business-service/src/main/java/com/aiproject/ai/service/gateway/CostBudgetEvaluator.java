package com.aiproject.ai.service.gateway;

import org.springframework.stereotype.Component;

/**
 * 成本预算告警的纯判定逻辑（无 IO，易单测）。
 *
 * <p>单位约定（这是最容易出错的地方，务必看清）：</p>
 * <ul>
 *   <li>审计日志 {@code AiCallLogBean.costCents} 存的是<b>毫分</b>（1 分 = 1000 毫分）；</li>
 *   <li>管理员配置的预算 {@code dailyCostBudgetCents} 是<b>分</b>（如 100 分 = 1 元）；</li>
 *   <li>比较前必须把累计毫分 ÷ 1000 换成分，否则会误判成"超预算 1000 倍"。</li>
 * </ul>
 *
 * <p>告警分两级：达到阈值为 WARNING，达到/超过预算为 ERROR。
 * 未配置预算（null 或 ≤0）时不告警。</p>
 */
@Component
public class CostBudgetEvaluator {

    /** 1 分 = 1000 毫分，与 ModelPriceResolver / AiCallLogBean 口径一致 */
    static final int MILLI_CENTS_PER_CENT = 1000;

    /** 默认告警阈值：预算的 80% */
    static final int DEFAULT_THRESHOLD_PCT = 80;

    public enum Level { NONE, WARNING, EXCEEDED }

    /**
     * 判定当日成本相对预算的告警级别。
     *
     * @param usedMilliCents 当日已用成本，单位毫分
     * @param budgetCents    每日预算，单位分；null 或 ≤0 表示不限制
     * @param thresholdPct   告警阈值百分比；null 用默认 80，越界夹到 1-100
     * @return 告警级别
     */
    public Level evaluate(long usedMilliCents, Long budgetCents, Integer thresholdPct) {
        if (budgetCents == null || budgetCents <= 0) {
            return Level.NONE;
        }
        long usedCents = usedMilliCents / MILLI_CENTS_PER_CENT;
        long budget = budgetCents;

        // 先判超预算，再判阈值，避免 100% 时同时命中两级
        if (usedCents >= budget) {
            return Level.EXCEEDED;
        }
        int pct = normalizeThreshold(thresholdPct);
        long thresholdCents = budget * pct / 100;
        if (usedCents >= thresholdCents) {
            return Level.WARNING;
        }
        return Level.NONE;
    }

    /** 阈值夹到 1-100，null/非法值用默认 80 */
    int normalizeThreshold(Integer thresholdPct) {
        if (thresholdPct == null || thresholdPct <= 0 || thresholdPct > 100) {
            return DEFAULT_THRESHOLD_PCT;
        }
        return thresholdPct;
    }

    /** 把毫分转成展示用的「元」字符串，前端/通知里可读（1 元 = 100 分 = 100000 毫分） */
    public String toYuan(long usedMilliCents) {
        long cents = usedMilliCents / MILLI_CENTS_PER_CENT;
        long yuan = cents / 100;
        long remainder = cents % 100;
        return yuan + "." + String.format("%02d", remainder);
    }
}
