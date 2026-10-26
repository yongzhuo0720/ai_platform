package com.aiproject.ai.service.rag;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索评估指标的单元测试。
 *
 * <p>指标算错是最危险的一类 bug：它会让"改造后效果提升了"这个结论
 * 建立在错误的数字上，而且没人会怀疑——因为报告看起来很专业。
 * 所以每个指标都用<b>手算可验证</b>的用例钉住。</p>
 */
class RagEvaluatorTest {

    private Set<Long> set(Long... ids) {
        return new HashSet<Long>(Arrays.asList(ids));
    }

    private List<Long> list(Long... ids) {
        return Arrays.asList(ids);
    }

    // ==================== Recall@k ====================

    /** 3 个相关片段命中 2 个 → 2/3 */
    @Test
    void recall_shouldCountCoverage() {
        assertEquals(2.0 / 3, RagEvaluator.recallAtK(list(1L, 2L, 9L), set(1L, 2L, 3L), 3), 1e-9);
    }

    /** 相关片段排在第 4 位，k=3 时不算命中 */
    @Test
    void recall_shouldOnlyLookWithinTopK() {
        assertEquals(0.0, RagEvaluator.recallAtK(list(9L, 8L, 7L, 1L), set(1L), 3), 1e-9);
        assertEquals(1.0, RagEvaluator.recallAtK(list(9L, 8L, 7L, 1L), set(1L), 4), 1e-9);
    }

    /** 全部命中 → 1.0 */
    @Test
    void recall_shouldBeOneWhenAllFound() {
        assertEquals(1.0, RagEvaluator.recallAtK(list(1L, 2L), set(1L, 2L), 5), 1e-9);
    }

    /** 无相关片段的问题（闲聊类）不参与召回评估，按 1.0 处理而不是拖低均值 */
    @Test
    void recall_shouldIgnoreCasesWithoutRelevantChunks() {
        assertEquals(1.0, RagEvaluator.recallAtK(list(1L), Collections.<Long>emptySet(), 5), 1e-9);
    }

    /** 检索结果为空 → 0 */
    @Test
    void recall_shouldBeZeroWhenNothingRetrieved() {
        assertEquals(0.0, RagEvaluator.recallAtK(Collections.<Long>emptyList(), set(1L), 5), 1e-9);
    }

    // ==================== Precision@k ====================

    /**
     * 分母用 min(k, retrieved.size())：只检索出 2 条时不该因为没凑满 5 条被判低分。
     * 这里 2 条中 1 条相关 → 1/2，而不是 1/5。
     */
    @Test
    void precision_shouldUseActualRetrievedCountAsDenominator() {
        assertEquals(0.5, RagEvaluator.precisionAtK(list(1L, 9L), set(1L), 5), 1e-9);
    }

    /** 5 条里 2 条相关 → 2/5 */
    @Test
    void precision_shouldDivideByK() {
        assertEquals(0.4, RagEvaluator.precisionAtK(list(1L, 9L, 2L, 8L, 7L), set(1L, 2L), 5), 1e-9);
    }

    // ==================== MRR ====================

    /** 第一条就命中 → 1.0 */
    @Test
    void mrr_shouldBeOneWhenFirstHit() {
        assertEquals(1.0, RagEvaluator.reciprocalRank(list(1L, 9L), set(1L)), 1e-9);
    }

    /** 排第 3 → 1/3 */
    @Test
    void mrr_shouldBeReciprocalOfPosition() {
        assertEquals(1.0 / 3, RagEvaluator.reciprocalRank(list(9L, 8L, 1L), set(1L)), 1e-9);
    }

    /** 一条都没命中 → 0 */
    @Test
    void mrr_shouldBeZeroWhenNoHit() {
        assertEquals(0.0, RagEvaluator.reciprocalRank(list(9L, 8L), set(1L)), 1e-9);
    }

    // ==================== NDCG@k ====================

    /**
     * 单个相关片段排第 1：DCG = 1/log2(2) = 1，IDCG = 1 → NDCG = 1
     */
    @Test
    void ndcg_shouldBeOneWhenPerfectOrder() {
        assertEquals(1.0, RagEvaluator.ndcgAtK(list(1L, 9L, 8L), set(1L), 3), 1e-9);
    }

    /**
     * 单个相关片段排第 2：
     * DCG = 1/log2(3)，IDCG = 1/log2(2) = 1 → NDCG = 1/log2(3) ≈ 0.6309
     */
    @Test
    void ndcg_shouldPenalizeLaterPosition() {
        assertEquals(1.0 / (Math.log(3) / Math.log(2)),
                RagEvaluator.ndcgAtK(list(9L, 1L, 8L), set(1L), 3), 1e-9);
    }

    /**
     * 关键区别：Recall 相同但排序不同，NDCG 必须不同。
     * 两条相关片段都召回，排在 1、2 位 vs 排在 4、5 位。
     */
    @Test
    void ndcg_shouldDistinguishRankingQuality_whenRecallEqual() {
        List<Long> goodOrder = list(1L, 2L, 9L, 8L, 7L);
        List<Long> badOrder = list(9L, 8L, 7L, 1L, 2L);

        // Recall 完全一样：都覆盖了 1 和 2
        assertEquals(1.0, RagEvaluator.recallAtK(goodOrder, set(1L, 2L), 5), 1e-9);
        assertEquals(1.0, RagEvaluator.recallAtK(badOrder, set(1L, 2L), 5), 1e-9);

        // 但 NDCG 必须体现差距
        double goodNdcg = RagEvaluator.ndcgAtK(goodOrder, set(1L, 2L), 5);
        double badNdcg = RagEvaluator.ndcgAtK(badOrder, set(1L, 2L), 5);
        assertTrue(goodNdcg > badNdcg,
                "NDCG 应惩罚把相关结果排到后面：good=" + goodNdcg + " bad=" + badNdcg);
        assertEquals(1.0, goodNdcg, 1e-9);
    }

    /** 相关片段多于 k 时，IDCG 只算 k 个，避免分数被人为压低 */
    @Test
    void ndcg_shouldCapIdealAtK() {
        // 5 个相关片段，k=2，前 2 位都命中 → 满分
        assertEquals(1.0, RagEvaluator.ndcgAtK(list(1L, 2L), set(1L, 2L, 3L, 4L, 5L), 2), 1e-9);
    }

    // ==================== 整体评估 ====================

    /** 整体评估应正确求均值，并记录完全漏召的问题 */
    @Test
    void evaluate_shouldAverageAndCollectMisses() {
        List<RagEvaluator.EvalCase> cases = Arrays.asList(
                // 完美
                new RagEvaluator.EvalCase("q1", list(1L, 2L), set(1L, 2L)),
                // 命中一半
                new RagEvaluator.EvalCase("q2", list(1L, 9L), set(1L, 2L)),
                // 完全漏召
                new RagEvaluator.EvalCase("q3", list(9L, 8L), set(1L))
        );

        RagEvaluator.EvalReport report = RagEvaluator.evaluate(cases, 5);

        assertEquals(3, report.getTotal());
        // (1.0 + 0.5 + 0.0) / 3 = 0.5
        assertEquals(1.5, report.getRecallAtK() * 3, 1e-9);
        assertEquals(0.5, report.getRecallAtK(), 1e-9);
        assertEquals(1, report.getMisses().size());
        assertEquals("q3", report.getMisses().get(0));
        assertEquals(3, report.getCases().size());

        // 摘要行应包含关键数字，方便直接贴报告
        assertTrue(report.summary().contains("Recall@5"));
        assertTrue(report.summary().contains("完全漏召=1"));
    }

    /** 空用例集不能抛异常 */
    @Test
    void evaluate_shouldHandleEmptyCases() {
        RagEvaluator.EvalReport report = RagEvaluator.evaluate(Collections.<RagEvaluator.EvalCase>emptyList(), 5);
        assertEquals(0, report.getTotal());
        assertEquals(0.0, report.getRecallAtK(), 1e-9);
    }
}
