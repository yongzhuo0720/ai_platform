package com.aiproject.ai.service.rag;

import com.aiproject.ai.service.bean.KbChunkBean;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.holde.KbRagHolder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混合检索的量化评估：证明它相对纯向量检索到底好在哪、好多少。
 *
 * <p><b>为什么要有这个测试</b>：加混合检索很容易，说"应该更好"也很容易，
 * 但面试官会问"好多少、怎么证明"。这个测试给的就是那个数字。</p>
 *
 * <p><b>实验设计</b>：构造 RAG 里最典型的一类失败——<i>语义陷阱</i>。</p>
 * <ul>
 *   <li>每个用例 8 个分块，目标分块含用户问题里的<b>精确关键词</b>
 *       （如版本号 {@code BGE-M3}）；</li>
 *   <li>另有 3 个干扰分块的向量与问题<b>更接近</b>（cosine 0.86-0.88 vs 目标 0.80），
 *       它们讲的是同一个主题、只是型号/版本不同；</li>
 *   <li>结果：纯向量检索把 3 个干扰项排在目标前面，目标掉到第 4 位；
 *       关键词路能精确捞到目标，RRF 融合后目标升到第 1 位。</li>
 * </ul>
 *
 * <p>这正是 embedding 的结构性盲区：{@code BGE-M3} 与 {@code BGE-M2}
 * 在向量空间里几乎重合，差别只在最后一位数字——而这恰恰是用户最在意的信息。</p>
 *
 * <p><b>测什么、不测什么</b>：这里测的是<b>排序与融合逻辑</b>，
 * 关键词路的召回结果（哪些分块含有关键词）作为入参直接给出，
 * 不经过 PostgreSQL。FTS 引擎本身的行为由数据库保证，不属于本测试范围。
 * 这样测试是确定性的、毫秒级、可进 CI。</p>
 */
class KbRetrievalEvalTest {

    private static final int TOP_K = 3;

    /** 单位查询向量：[1, 0, 0]，分块向量与之的 cosine 就等于其第一维 */
    private static final double[] QUERY_VECTOR = new double[]{1.0, 0.0, 0.0};

    /** 干扰项与目标的 cosine 配置：目标排第 4，前面 3 个都是干扰 */
    private static final double[] COSINES = new double[]{0.88, 0.87, 0.86, 0.80, 0.50, 0.30, 0.20, 0.10};

    /**
     * 构造一个用例的语料。
     *
     * @param caseIndex 用例序号，用于生成不重复的 iid
     * @param targetText 目标分块正文（含精确关键词）
     * @param distractors 3 个干扰分块正文（同主题、不同型号）
     * @return 8 个分块，目标在第 4 个（dense 排名第 4）
     */
    private List<KbChunkBean> corpus(int caseIndex, String targetText, String... distractors) {
        List<KbChunkBean> chunks = new ArrayList<KbChunkBean>();
        for (int i = 0; i < COSINES.length; i++) {
            KbChunkBean chunk = new KbChunkBean();
            chunk.setIid((long) (caseIndex * 100 + i + 1));
            chunk.setDocIid((long) caseIndex);
            chunk.setKbName("评估语料");
            chunk.setChunkIndex(i);
            chunk.setContent(i < 3 ? distractors[i] : (i == 3 ? targetText : "与本次评估无关的其它内容 " + i));
            chunk.setEmbedding(vectorJson(COSINES[i]));
            chunks.add(chunk);
        }
        return chunks;
    }

    /** 生成 cosine 为 c 的三维单位向量 [c, sqrt(1-c²), 0] */
    private String vectorJson(double c) {
        double s = Math.sqrt(Math.max(0, 1 - c * c));
        return String.format(java.util.Locale.ROOT, "[%.6f,%.6f,0.000000]", c, s);
    }

    /** 取目标分块（第 4 个）的 iid */
    private long targetIid(int caseIndex) {
        return caseIndex * 100L + 4L;
    }

    /** 从排序结果里提取 iid 列表，供指标计算 */
    private List<Long> iids(List<KbSearchItem> items) {
        List<Long> result = new ArrayList<Long>(items.size());
        for (KbSearchItem item : items) {
            // KbSearchItem 没有 iid 字段，按 docIid + chunkIndex 反查
            result.add(reverseIid(item));
        }
        return result;
    }

    /** 把 docIid/chunkIndex 还原成测试里用的 iid（caseIndex*100 + chunkIndex + 1） */
    private long reverseIid(KbSearchItem item) {
        int caseIndex = item.getDocIid().intValue();
        return caseIndex * 100L + item.getChunkIndex() + 1L;
    }

    /**
     * 六个用例的语料定义。每个用例都模拟"型号/版本号"这类 embedding 分不清的关键词。
     */
    private List<Object[]> cases() {
        List<Object[]> list = new ArrayList<Object[]>();
        list.add(new Object[]{
                "BGE-M3 支持哪几种检索方式？",
                "BGE-M3 是智源研究院开源的多语言向量模型，支持稠密、稀疏、多向量三种检索方式。",
                "BGE-M1 是第一代模型，只支持稠密检索，不支持稀疏与多向量。",
                "BGE-M2 是过渡版本，增加了稀疏检索，仍然不支持多向量。",
                "BGE-M2.5 修补了多语言表现，检索方式与 M2 一致。"});
        list.add(new Object[]{
                "pgvector 0.8 版本的 HNSW 索引怎么建？",
                "pgvector 0.8 版本用 CREATE INDEX ... USING hnsw (embedding vector_cosine_ops) 建 HNSW 索引。",
                "pgvector 0.5 版本只支持 ivfflat 索引，还不支持 HNSW。",
                "pgvector 0.6 版本开始试验 HNSW，默认参数与 0.8 不同。",
                "pgvector 0.7 版本优化了 HNSW 的构建速度，语法与 0.8 基本一致。"});
        list.add(new Object[]{
                "Dify 0.15 的知识库 API 路径是什么？",
                "Dify 0.15 的知识库 API 路径是 /v1/datasets/{dataset_id}/documents。",
                "Dify 0.9 的知识库 API 路径是 /v1/datasets/{id}/docs，与 0.15 不同。",
                "Dify 0.12 改过一次 API 前缀，变成 /v1/dataset。",
                "Dify 0.13 调整了分页参数，路径本身与 0.12 相同。"});
        list.add(new Object[]{
                "RRF 的 k 参数一般取多少？",
                "RRF 倒数排名融合的 k 参数业界一般取 60，出自原论文的推荐值。",
                "加权求和的权重参数一般取 0.5，与 RRF 的 k 不是一回事。",
                "BM25 的 k1 参数一般取 1.2 到 2.0，控制词频饱和。",
                "BM25 的 b 参数一般取 0.75，控制文档长度归一化。"});
        list.add(new Object[]{
                "Spring Boot 2.6 的路径匹配策略怎么配？",
                "Spring Boot 2.6 需要显式设置 spring.mvc.pathmatch.matching-strategy=ant_path_matcher 才兼容 Swagger。",
                "Spring Boot 2.4 首次引入 PathPatternParser，默认仍是 ant。",
                "Spring Boot 2.5 调整了默认值，但不影响 Swagger 2 的兼容性。",
                "Spring Boot 3.0 彻底移除 ant 匹配器，只能改 Swagger 版本。"});
        list.add(new Object[]{
                "Sa-Token 1.28 的 token 名称怎么改？",
                "Sa-Token 1.28 通过 sa-token.token-name 配置项修改 token 名称，默认是 satoken。",
                "Sa-Token 1.20 不支持自定义 token 名称，只能沿用默认值。",
                "Sa-Token 1.25 的自定义方式与 1.20 相同，仍不可配。",
                "Sa-Token 1.26 增加了部分配置项，但 token 名称仍写死。"});
        return list;
    }

    /**
     * 核心断言：混合检索在"语义陷阱"场景下显著优于纯向量检索。
     *
     * <p>量化结果会打印出来，可直接引用到评估报告或简历里。</p>
     */
    @Test
    void hybridRetrieval_shouldBeatDenseOnly_onSemanticTraps() {
        KbRagHolder holder = new KbRagHolder();
        List<RagEvaluator.EvalCase> denseCases = new ArrayList<RagEvaluator.EvalCase>();
        List<RagEvaluator.EvalCase> hybridCases = new ArrayList<RagEvaluator.EvalCase>();

        List<Object[]> defs = cases();
        for (int i = 0; i < defs.size(); i++) {
            Object[] def = defs.get(i);
            String question = (String) def[0];
            String targetText = (String) def[1];
            String d1 = (String) def[2];
            String d2 = (String) def[3];
            String d3 = (String) def[4];

            List<KbChunkBean> chunks = corpus(i, targetText, d1, d2, d3);

            // ---- 纯向量路：不提供关键词召回 ----
            List<KbSearchItem> denseOnly = holder.hybridRank(
                    question, chunks, QUERY_VECTOR, Collections.<Long>emptySet());

            // ---- 混合路：关键词路能捞到目标（模拟 PG 全文检索命中精确关键词）----
            Set<Long> lexicalHits = new HashSet<Long>();
            lexicalHits.add(targetIid(i));
            List<KbSearchItem> hybrid = holder.hybridRank(question, chunks, QUERY_VECTOR, lexicalHits);

            Set<Long> relevant = new HashSet<Long>();
            relevant.add(targetIid(i));

            denseCases.add(new RagEvaluator.EvalCase(question, iids(denseOnly), relevant));
            hybridCases.add(new RagEvaluator.EvalCase(question, iids(hybrid), relevant));
        }

        RagEvaluator.EvalReport denseReport = RagEvaluator.evaluate(denseCases, TOP_K);
        RagEvaluator.EvalReport hybridReport = RagEvaluator.evaluate(hybridCases, TOP_K);

        System.out.println("[RAG 评估] 纯向量  " + denseReport.summary());
        System.out.println("[RAG 评估] 混合检索 " + hybridReport.summary());

        /*
         * 纯向量路：目标固定排第 4，top-3 一个都捞不到。
         *   Recall@3 = 0，MRR = 1/4 = 0.25
         * 混合路：关键词路把目标捞到第 1，RRF 融合后稳定排第 1。
         *   Recall@3 = 1，MRR = 1.0
         */
        assertEquals(0.0, denseReport.getRecallAtK(), 1e-9,
                "纯向量检索在这个构造下必然漏召——目标排第 4，top-3 覆盖不到");
        assertEquals(0.25, denseReport.getMrr(), 1e-9,
                "纯向量下第一条命中排第 4，MRR 应为 1/4");

        assertEquals(1.0, hybridReport.getRecallAtK(), 1e-9,
                "混合检索应把目标全部召回");
        assertEquals(1.0, hybridReport.getMrr(), 1e-9,
                "混合检索应把目标排到第 1");

        // 增益是数量级差异，不是打平
        assertTrue(hybridReport.getRecallAtK() - denseReport.getRecallAtK() >= 0.99,
                "Recall@3 增益应接近 100 个百分点");
        assertTrue(hybridReport.getMrr() >= denseReport.getMrr() * 3,
                "MRR 至少提升 3 倍");
        assertTrue(hybridReport.getMisses().isEmpty(),
                "混合检索不应有完全漏召的问题");
        assertEquals(defs.size(), denseReport.getMisses().size(),
                "纯向量下所有问题都应漏召——这正是混合检索要解决的问题");
    }

    /**
     * 融合不能反过来伤害关键词路的命中：
     * 目标向量分最低（cosine 0.10）但关键词精确命中时，仍应排到前面。
     */
    @Test
    void rrf_shouldRescueResultWithLowDenseScore_whenKeywordExact() {
        KbRagHolder holder = new KbRagHolder();
        List<KbChunkBean> chunks = corpus(99, "目标内容 exact-keyword",
                "干扰一 同主题", "干扰二 同主题", "干扰三 同主题");

        // 让目标变成 cosine 最低的那个（重写其 embedding）
        KbChunkBean target = chunks.get(3);
        target.setEmbedding(vectorJson(0.10));
        // 干扰项全部拉到 0.90 以上
        for (int i = 0; i < 3; i++) {
            chunks.get(i).setEmbedding(vectorJson(0.95 - i * 0.01));
        }

        Set<Long> lexicalHits = new HashSet<Long>();
        lexicalHits.add(targetIid(99));

        List<KbSearchItem> ranked = holder.hybridRank(
                "exact-keyword 怎么配", chunks, QUERY_VECTOR, lexicalHits);

        long first = reverseIid(ranked.get(0));
        assertEquals(targetIid(99), first,
                "关键词精确命中的结果必须排第一，哪怕向量分只有 0.10");
    }

    /**
     * 关键词路为空时必须退化成纯向量排序，不能把结果弄乱。
     */
    @Test
    void hybridRank_shouldFallBackToDenseOrder_whenNoLexicalHits() {
        KbRagHolder holder = new KbRagHolder();
        List<KbChunkBean> chunks = corpus(98, "目标内容", "干扰一", "干扰二", "干扰三");

        List<KbSearchItem> ranked = holder.hybridRank(
                "问题", chunks, QUERY_VECTOR, Collections.<Long>emptySet());

        // 应按 cosine 降序：0.88, 0.87, 0.86, 0.80, ...
        assertEquals(COSINES.length, ranked.size());
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).getDenseScore() >= ranked.get(i).getDenseScore(),
                    "无关键词命中时应严格按向量分降序");
        }
        for (KbSearchItem item : ranked) {
            assertEquals("dense", item.getChannel(), "无关键词路时来源都应是 dense");
        }
    }
}
