package com.aiproject.ai.service.rag;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 检索质量的评估指标。
 *
 * <p>核心原则：<b>没有评估集的 RAG 优化都是盲调</b>。改一个 chunk size、
 * 换一个 embedding 模型、加一路混合检索——看着都"应该更好"，但没有数字
 * 就无法证明，也无法发现某次改动其实让效果变差了。</p>
 *
 * <p>这里只实现<b>不需要 LLM</b> 的检索侧指标（Recall / Precision / MRR / NDCG）。
 * 为什么不直接用 RAGAS 那套 context precision / faithfulness：
 * 那些指标需要 LLM-as-judge，每次评估都要花钱、且结果不稳定（同一份数据
 * 跑两次可能不同）。检索侧指标是确定性的，能在 CI 里跑、能作为回归门禁。
 * 生成侧指标（faithfulness / answer relevancy）适合人工月度抽检，
 * 不适合放进每次提交的门禁。</p>
 *
 * <p>指标口径：</p>
 * <ul>
 *   <li><b>Recall@k</b> —— 相关片段里，被前 k 条覆盖了多少。RAG 最关键的一个：
 *       召回不到，后面模型再强也白搭。</li>
 *   <li><b>Precision@k</b> —— 前 k 条里有多少是相关的。太低会稀释上下文。</li>
 *   <li><b>MRR</b> —— 第一条相关结果排名的倒数均值。衡量"能不能一次命中"。</li>
 *   <li><b>NDCG@k</b> —— 考虑排名位置与多级相关度的综合指标，越靠前权重越大。</li>
 * </ul>
 */
public class RagEvaluator {

    private RagEvaluator() {
    }

    /**
     * Recall@k：相关片段中被检索到的比例。
     *
     * @param retrieved 检索结果（按相关度降序），元素为片段标识
     * @param relevant  该问题真正相关的片段集合（ground truth）
     * @param k         只看前 k 条
     * @return 0-1；relevant 为空时返回 1（无相关片段的问题不参与召回评估）
     */
    public static double recallAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 1.0;
        }
        if (retrieved == null || retrieved.isEmpty() || k <= 0) {
            return 0.0;
        }
        Set<Long> topK = new HashSet<Long>(retrieved.subList(0, Math.min(k, retrieved.size())));
        int hit = 0;
        for (Long id : relevant) {
            if (topK.contains(id)) {
                hit++;
            }
        }
        return (double) hit / relevant.size();
    }

    /**
     * Precision@k：前 k 条里相关结果的比例。
     *
     * <p>分母用 {@code min(k, retrieved.size())} 而不是固定 k：
     * 只检索出 2 条时，不该因为"没凑满 5 条"而被判低分。</p>
     *
     * <p>同一相关结果出现多次只计一次。文档级评估下 top-k 里会出现同一文档的
     * 多个分块，不去重的话 Precision 会被虚高——
     * 这是第一次真实跑分（NDCG 冒出 1.39）暴露的问题，单测的合成用例
     * 全是唯一 ID，覆盖不到。</p>
     */
    public static double precisionAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        if (retrieved == null || retrieved.isEmpty() || k <= 0) {
            return 0.0;
        }
        if (relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, retrieved.size());
        int hit = 0;
        Set<Long> counted = new HashSet<Long>();
        for (int i = 0; i < limit; i++) {
            Long id = retrieved.get(i);
            if (relevant.contains(id) && counted.add(id)) {
                hit++;
            }
        }
        return (double) hit / limit;
    }

    /**
     * MRR（Mean Reciprocal Rank 的单条版本）：第一条相关结果排名的倒数。
     *
     * <p>排第 1 → 1.0；排第 3 → 0.333；一条都没命中 → 0。
     * 这个指标回答的是"用户能不能在第一屏就看到正确内容"。</p>
     */
    public static double reciprocalRank(List<Long> retrieved, Set<Long> relevant) {
        if (retrieved == null || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * NDCG@k：折损累积增益（DCG）除以理想排序的 DCG。
     *
     * <p>与 Recall 的区别：Recall 只看"有没有覆盖到"，NDCG 还看"排得多靠前"。
     * 同样召回 3 条相关片段，排在前 3 和排在第 8-10，NDCG 会明显不同——
     * 而 RAG 只取 top-k 喂给模型，位置直接决定信息能否进入上下文。</p>
     *
     * <p>这里用二值相关度（相关=1，不相关=0），因为知识库检索场景下
     * "部分相关"很难客观标注，强行分等级反而引入噪声。</p>
     *
     * <p>同一相关结果出现多次只计一次（取最靠前的位置）：文档级评估下
     * top-k 会混着同一文档的多个分块，重复累加会让 DCG 超过 IDCG，
     * NDCG 冒出大于 1 的值——真实跑分暴露的问题。</p>
     */
    public static double ndcgAtK(List<Long> retrieved, Set<Long> relevant, int k) {
        if (retrieved == null || relevant == null || relevant.isEmpty() || k <= 0) {
            return 0.0;
        }
        int limit = Math.min(k, retrieved.size());
        double dcg = 0.0;
        Set<Long> counted = new HashSet<Long>();
        for (int i = 0; i < limit; i++) {
            Long id = retrieved.get(i);
            if (relevant.contains(id) && counted.add(id)) {
                // 标准 NDCG 折损：位置越靠后增益越低
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        // 理想情况：相关片段全部排在最前面
        int idealHits = Math.min(k, relevant.size());
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    /**
     * 对一批问题做整体评估。
     *
     * @param cases 评估用例
     * @param k     评估的截断位置
     */
    public static EvalReport evaluate(List<EvalCase> cases, int k) {
        EvalReport report = new EvalReport(k);
        if (cases == null || cases.isEmpty()) {
            return report;
        }
        double recallSum = 0.0;
        double precisionSum = 0.0;
        double rrSum = 0.0;
        double ndcgSum = 0.0;
        for (EvalCase c : cases) {
            double recall = recallAtK(c.getRetrieved(), c.getRelevant(), k);
            double precision = precisionAtK(c.getRetrieved(), c.getRelevant(), k);
            double rr = reciprocalRank(c.getRetrieved(), c.getRelevant());
            double ndcg = ndcgAtK(c.getRetrieved(), c.getRelevant(), k);

            recallSum += recall;
            precisionSum += precision;
            rrSum += rr;
            ndcgSum += ndcg;

            report.cases.add(new CaseResult(c.getQuestion(), recall, precision, rr, ndcg));
            if (recall == 0.0 && !c.getRelevant().isEmpty()) {
                report.misses.add(c.getQuestion());
            }
        }
        int n = cases.size();
        report.recallAtK = recallSum / n;
        report.precisionAtK = precisionSum / n;
        report.mrr = rrSum / n;
        report.ndcgAtK = ndcgSum / n;
        report.total = n;
        return report;
    }

    /** 一条评估用例：问题 + 检索结果（按相关度降序的片段标识）+ 标准答案涉及的片段 */
    @Getter
    public static class EvalCase {
        private final String question;
        private final List<Long> retrieved;
        private final Set<Long> relevant;

        public EvalCase(String question, List<Long> retrieved, Set<Long> relevant) {
            this.question = question;
            this.retrieved = retrieved == null ? new ArrayList<Long>() : retrieved;
            this.relevant = relevant == null ? new HashSet<Long>() : relevant;
        }
    }

    /** 单条用例的得分，用于定位"哪些问题检索不到" */
    @Getter
    public static class CaseResult {
        private final String question;
        private final double recall;
        private final double precision;
        private final double reciprocalRank;
        private final double ndcg;

        CaseResult(String question, double recall, double precision,
                   double reciprocalRank, double ndcg) {
            this.question = question;
            this.recall = recall;
            this.precision = precision;
            this.reciprocalRank = reciprocalRank;
            this.ndcg = ndcg;
        }
    }

    /** 整体评估结果 */
    @Getter
    public static class EvalReport {
        private final int k;
        private int total;
        private double recallAtK;
        private double precisionAtK;
        private double mrr;
        private double ndcgAtK;
        private final List<CaseResult> cases = new ArrayList<CaseResult>();
        /** 完全没召回到相关片段的那些问题——最该优先看的失败案例 */
        private final List<String> misses = new ArrayList<String>();

        EvalReport(int k) {
            this.k = k;
        }

        /** 一行摘要，方便贴进 PR 描述或评估报告 */
        public String summary() {
            return String.format(
                    "k=%d 用例=%d Recall@%d=%.4f Precision@%d=%.4f MRR=%.4f NDCG@%d=%.4f 完全漏召=%d",
                    k, total, k, recallAtK, k, precisionAtK, mrr, k, ndcgAtK, misses.size());
        }
    }
}
