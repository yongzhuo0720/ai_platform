package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成本预算判定逻辑单测。
 * 核心是单位换算：审计日志记毫分、预算配的是分，错一位就是 1000 倍误判。
 */
class CostBudgetEvaluatorTest {

    private final CostBudgetEvaluator evaluator = new CostBudgetEvaluator();

    @Test
    void noBudget_neverAlerts() {
        assertEquals(CostBudgetEvaluator.Level.NONE, evaluator.evaluate(999_999L, null, 80));
        assertEquals(CostBudgetEvaluator.Level.NONE, evaluator.evaluate(999_999L, 0L, 80));
        assertEquals(CostBudgetEvaluator.Level.NONE, evaluator.evaluate(999_999L, -1L, 80));
    }

    /** 1 分 = 1000 毫分：预算 100 分，用到 80 分（80000 毫分）应触发 warning */
    @Test
    void shouldWarnAtThreshold_withMilliCentConversion() {
        assertEquals(CostBudgetEvaluator.Level.WARNING,
                evaluator.evaluate(80_000L, 100L, 80), "80000 毫分 = 80 分，正好 80% 阈值");
    }

    @Test
    void shouldNotWarnBelowThreshold() {
        assertEquals(CostBudgetEvaluator.Level.NONE,
                evaluator.evaluate(79_999L, 100L, 80), "低于阈值不告警");
    }

    /** 超预算判定优先于阈值：100 分预算用到 100 分（100000 毫分）应 EXCEEDED 而非 WARNING */
    @Test
    void exceedTakesPriorityOverWarning() {
        assertEquals(CostBudgetEvaluator.Level.EXCEEDED,
                evaluator.evaluate(100_000L, 100L, 80));
        assertEquals(CostBudgetEvaluator.Level.EXCEEDED,
                evaluator.evaluate(150_000L, 100L, 80), "超预算 50% 仍判 EXCEEDED");
    }

    /** 单位换算回归：若有人把毫分当成分直接比，100000 毫分会 vs 100 分成 1000 倍误判 */
    @Test
    void shouldNotMistakeMilliCentsForCents() {
        // 预算 100 分，实际只用了 5 分（5000 毫分）——若忘了换算会算成 5000 分 → 误报超预算
        assertEquals(CostBudgetEvaluator.Level.NONE,
                evaluator.evaluate(5_000L, 100L, 80), "5000 毫分=5分，远未到 80 分阈值");
    }

    @Test
    void thresholdClampsToValidRange() {
        assertEquals(80, evaluator.normalizeThreshold(null), "null 用默认 80");
        assertEquals(80, evaluator.normalizeThreshold(0));
        assertEquals(80, evaluator.normalizeThreshold(101));
        assertEquals(80, evaluator.normalizeThreshold(-5));
        assertEquals(50, evaluator.normalizeThreshold(50), "合法值原样返回");
        assertEquals(100, evaluator.normalizeThreshold(100));
    }

    /** 100% 阈值 = 只在超预算时告警（warning 分支 disappears） */
    @Test
    void hundredPercentThreshold_onlyExceedAlerts() {
        assertEquals(CostBudgetEvaluator.Level.NONE,
                evaluator.evaluate(99_000L, 100L, 100));
        assertEquals(CostBudgetEvaluator.Level.EXCEEDED,
                evaluator.evaluate(100_000L, 100L, 100));
    }

    @Test
    void toYuan_formatsReadableAmount() {
        assertEquals("0.05", evaluator.toYuan(5_000L), "5000 毫分=5分=0.05元");
        assertEquals("1.00", evaluator.toYuan(100_000L), "100000 毫分=100分=1元");
        assertEquals("12.34", evaluator.toYuan(1_234_000L));
    }
}
