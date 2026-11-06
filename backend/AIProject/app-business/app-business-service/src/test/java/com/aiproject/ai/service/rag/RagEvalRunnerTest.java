package com.aiproject.ai.service.rag;

import com.aiproject.ai.service.bean.RagEvalCaseBean;
import com.aiproject.ai.service.bean.RagEvalCaseResultBean;
import com.aiproject.ai.service.bean.RagEvalDatasetBean;
import com.aiproject.ai.service.bean.RagEvalRunBean;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.aiproject.ai.service.mapper.RagEvalCaseBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalCaseResultBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalDatasetBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalRunBeanMapper;
import com.aiproject.ai.service.rag.dto.RagEvalHitDto;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评估运行器的端到端（到 Mapper 边界）单元测试。
 *
 * <p>重点验证三件事：</p>
 * <ul>
 *   <li>逐题明细记录的是真实检索结果，整体指标复用 {@link RagEvaluator} 的口径；</li>
 *   <li>单题失败不让整轮评估报废，但全部失败必须判 failed，不能伪装成 0 分；</li>
 *   <li>关键词命中要求「全部关键词出现在期望文档的同一条命中分块里」，不能被多块拼凑。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RagEvalRunnerTest {

    private static final String UID = "u1";
    private static final String RUN_KEY = "run-1";
    private static final String DATASET_KEY = "dataset-1";
    private static final String KB_NAME = "默认知识库";

    @Mock
    RagEvalRunBeanMapper<RagEvalRunBean> runMapper;

    @Mock
    RagEvalCaseResultBeanMapper<RagEvalCaseResultBean> resultMapper;

    @Mock
    RagEvalCaseBeanMapper<RagEvalCaseBean> caseMapper;

    @Mock
    RagEvalDatasetBeanMapper<RagEvalDatasetBean> datasetMapper;

    @Mock
    KbRagHolder kbRagHolder;

    @InjectMocks
    RagEvalRunner runner;

    @Test
    void execute_shouldComputeMetricsAndPersistPerCaseResults() {
        stubRunAndDataset(caseBean("问题一", "A.pdf", "alpha", "beta"),
                caseBean("问题二", "C.pdf"));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(resultMapper.install(any(RagEvalCaseResultBean.class))).thenReturn(1);
        when(kbRagHolder.search(eq(UID), anyString(), eq(KB_NAME), eq(5)))
                .thenReturn(searchResult(
                                scoredHit("A.pdf", 2, "alpha beta 的正文",
                                        0.0328, 0.91, 3, "both"),
                                scoredHit("B.pdf", 0, "无关内容",
                                        0.0164, 0.42, -1, "dense")),
                        searchResult(
                                hit("B.pdf", "无关内容"),
                                hit("C.pdf", "gamma 的正文")));

        runner.execute(RUN_KEY, UID);

        RunUpdate finalUpdate = lastRunUpdate();
        assertEquals(RagEvalStatus.SUCCESS, finalUpdate.status);
        assertEquals(2, finalUpdate.processed);
        assertEquals(2, finalUpdate.total);
        assertEquals(1.0, finalUpdate.recall, 1e-9);
        assertEquals(0.5, finalUpdate.precision, 1e-9);
        assertEquals(0.75, finalUpdate.mrr, 1e-9);
        assertEquals((1.0 + (1.0 / (Math.log(3) / Math.log(2)))) / 2, finalUpdate.ndcg, 1e-9);
        assertEquals(1.0, finalUpdate.keywordHitRate, 1e-9);
        assertEquals(1, finalUpdate.keywordEvaluatedCount);
        assertEquals(0, finalUpdate.missCount);
        assertEquals("embed-v1", finalUpdate.embeddingModel);
        assertEquals("rag-eval-v1", finalUpdate.metricSpec);
        assertEquals("hybrid-rrf", finalUpdate.retrievalMode);
        assertFalse(finalUpdate.rerankApplied);
        assertNull(finalUpdate.errorMessage);

        ArgumentCaptor<RagEvalCaseResultBean> captor =
                ArgumentCaptor.forClass(RagEvalCaseResultBean.class);
        verify(resultMapper, times(2)).install(captor.capture());
        List<RagEvalCaseResultBean> saved = captor.getAllValues();
        assertEquals(0, saved.get(0).getCaseIndex().intValue());
        assertEquals("[\"A.pdf\",\"B.pdf\"]", saved.get(0).getRetrievedDocs());
        assertEquals("A.pdf", saved.get(0).getTopDoc());
        assertEquals(1, saved.get(0).getFirstHitRank().intValue());
        assertTrue(saved.get(0).getKeywordHit());
        assertTrue(saved.get(0).getKeywordEvaluated());
        assertEquals(2, saved.get(1).getFirstHitRank().intValue());
        assertEquals(0.5, saved.get(1).getReciprocalRank(), 1e-9);
        assertFalse(saved.get(1).getKeywordEvaluated());
        assertNull(saved.get(1).getErrorMessage());
        assertEquals(UID, saved.get(0).getLastuser());

        List<RagEvalHitDto> hits = RagEvalKeywordCodec.parseHits(saved.get(0).getRetrievedHits());
        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).getRank().intValue());
        assertEquals("A.pdf", hits.get(0).getDocName());
        assertEquals(2, hits.get(0).getChunkIndex().intValue());
        assertEquals(0.0328, hits.get(0).getScore(), 1e-9);
        assertEquals(0.91, hits.get(0).getDenseScore(), 1e-9);
        assertEquals(3, hits.get(0).getLexicalScore().intValue());
        assertEquals("both", hits.get(0).getChannel());
        assertEquals("alpha beta 的正文", hits.get(0).getContentPreview());
        assertEquals(2, hits.get(1).getRank().intValue());
        assertEquals(-1, hits.get(1).getLexicalScore().intValue(),
                "未参与关键词路的片段必须保留 -1，不能伪造成 0 分");
    }

    @Test
    void execute_shouldSnapshotRerankModeWhenSearchReportsApplied() {
        stubRunAndDataset(caseBean("问题", "A.pdf"));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(resultMapper.install(any(RagEvalCaseResultBean.class))).thenReturn(1);
        KbSearchResult result = new KbSearchResult();
        result.setModel("embed-v1");
        result.setRerankApplied(true);
        result.setItems(new ArrayList<KbSearchItem>(Collections.singletonList(
                hit("A.pdf", "正文"))));
        when(kbRagHolder.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Result.succeed(result, "ok"));

        runner.execute(RUN_KEY, UID);

        RunUpdate finalUpdate = lastRunUpdate();
        assertEquals("hybrid-rrf+rerank", finalUpdate.retrievalMode);
        assertEquals(Boolean.TRUE, finalUpdate.rerankApplied);
    }

    @Test
    void execute_shouldTruncatePreviewWithoutAffectingKeywordMatching() {
        stubRunAndDataset(caseBean("问题", "A.pdf", "关键结论"));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(resultMapper.install(any(RagEvalCaseResultBean.class))).thenReturn(1);
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longContent.append('x');
        }
        longContent.append("关键结论");
        when(kbRagHolder.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(searchResult(hit("A.pdf", longContent.toString())));

        runner.execute(RUN_KEY, UID);

        ArgumentCaptor<RagEvalCaseResultBean> captor =
                ArgumentCaptor.forClass(RagEvalCaseResultBean.class);
        verify(resultMapper).install(captor.capture());
        List<RagEvalHitDto> hits =
                RagEvalKeywordCodec.parseHits(captor.getValue().getRetrievedHits());
        assertEquals(200, hits.get(0).getContentPreview().length(),
                "片段预览必须截断，避免评估明细表被原文撑大");
        assertTrue(captor.getValue().getKeywordHit(),
                "关键词匹配必须使用完整分块，不能被预览截断影响");
    }

    @Test
    void execute_shouldTreatRepeatedChunksOfSameDocAsOneDocument() {
        // 同一文档的多个分块在 top-k 中重复出现：文档级 Precision 只能计一次
        stubRunAndDataset(caseBean("问题", "A.pdf"));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(resultMapper.install(any(RagEvalCaseResultBean.class))).thenReturn(1);
        when(kbRagHolder.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(searchResult(
                        hit("A.pdf", "第一块"),
                        hit("A.pdf", "第二块"),
                        hit("B.pdf", "无关内容")));

        runner.execute(RUN_KEY, UID);

        RunUpdate finalUpdate = lastRunUpdate();
        assertEquals(1.0, finalUpdate.recall, 1e-9);
        assertEquals(1.0 / 3, finalUpdate.precision, 1e-9,
                "同文档多分块不能让 Precision 虚高");
        assertEquals(1.0, finalUpdate.mrr, 1e-9);
        assertEquals(1.0, finalUpdate.ndcg, 1e-9, "重复命中不能让 NDCG 超过 1");
    }

    @Test
    void execute_shouldKeepResultsWhenOneCaseFails() {
        stubRunAndDataset(caseBean("问题一", "A.pdf"), caseBean("问题二", "C.pdf"));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(resultMapper.install(any(RagEvalCaseResultBean.class))).thenReturn(1);
        when(kbRagHolder.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(searchResult(hit("A.pdf", "正文")))
                .thenThrow(new RuntimeException("向量服务超时"));

        runner.execute(RUN_KEY, UID);

        RunUpdate finalUpdate = lastRunUpdate();
        assertEquals(RagEvalStatus.PARTIAL, finalUpdate.status);
        assertTrue(finalUpdate.errorMessage.contains("1/2"));
        assertTrue(finalUpdate.errorMessage.contains("向量服务超时"));
        assertEquals(2, finalUpdate.processed);

        ArgumentCaptor<RagEvalCaseResultBean> captor =
                ArgumentCaptor.forClass(RagEvalCaseResultBean.class);
        verify(resultMapper, times(2)).install(captor.capture());
        assertNotNull(captor.getAllValues().get(1).getErrorMessage(),
                "失败题也必须留下明细，说明失败原因");
    }

    @Test
    void execute_shouldFailRunWhenAllCasesFail() {
        stubRunAndDataset(caseBean("问题一", "A.pdf"), caseBean("问题二", "C.pdf"));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(kbRagHolder.search(anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("连接被拒绝"));

        runner.execute(RUN_KEY, UID);

        RunUpdate finalUpdate = lastRunUpdate();
        assertEquals(RagEvalStatus.FAILED, finalUpdate.status);
        assertTrue(finalUpdate.errorMessage.contains("全部用例检索失败"));
        assertEquals(2, finalUpdate.processed);
    }

    @Test
    void execute_shouldMarkPartialWhenResultPersistenceFails() {
        stubRunAndDataset(caseBean("问题", "A.pdf"));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(resultMapper.install(any(RagEvalCaseResultBean.class))).thenReturn(0);
        when(kbRagHolder.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(searchResult(hit("A.pdf", "正文")));

        runner.execute(RUN_KEY, UID);

        RunUpdate finalUpdate = lastRunUpdate();
        assertEquals(RagEvalStatus.PARTIAL, finalUpdate.status);
        assertTrue(finalUpdate.errorMessage.contains("逐题结果保存失败"));
        assertEquals(1.0, finalUpdate.recall, 1e-9,
                "落库失败不影响已计算出的指标");
    }

    @Test
    void execute_shouldAbortWhenRunRowDisappearsMidway() {
        stubRunAndDataset(caseBean("问题一", "A.pdf"), caseBean("问题二", "C.pdf"));
        // 第 1 次：置 running；第 2 次：快照；第 3 次：进度更新失败（运行记录被删除）
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class)))
                .thenReturn(1, 1, 0);
        when(kbRagHolder.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(searchResult(hit("A.pdf", "正文")));

        runner.execute(RUN_KEY, UID);

        verify(kbRagHolder, times(1)).search(anyString(), anyString(), anyString(), anyInt());
        verify(resultMapper, never()).install(any(RagEvalCaseResultBean.class));
    }

    @Test
    void execute_shouldNotRunWhenRecordIsNoLongerQueued() {
        RagEvalRunBean running = run(RagEvalStatus.RUNNING);
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(running));

        runner.execute(RUN_KEY, UID);

        verify(kbRagHolder, never()).search(anyString(), anyString(), anyString(), anyInt());
        verify(runMapper, never()).update(any(RagEvalRunBean.class), any(RagEvalRunBean.class));
    }

    @Test
    void execute_shouldFailRunWhenDatasetWasDeleted() {
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(run(RagEvalStatus.QUEUED)));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.<RagEvalDatasetBean>emptyList());

        runner.execute(RUN_KEY, UID);

        RunUpdate finalUpdate = lastRunUpdate();
        assertEquals(RagEvalStatus.FAILED, finalUpdate.status);
        assertTrue(finalUpdate.errorMessage.contains("数据集已被删除"));
    }

    @Test
    void keywordHit_shouldRequireAllKeywordsInSameExpectedDocChunk() {
        stubRunAndDataset(caseBean("问题", "A.pdf", "alpha", "beta"));
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        when(resultMapper.install(any(RagEvalCaseResultBean.class))).thenReturn(1);
        // 关键词被拆在两个分块，另一个不相关文档里反而凑齐了：都不算命中
        when(kbRagHolder.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(searchResult(
                        hit("A.pdf", "只有 alpha"),
                        hit("A.pdf", "只有 beta"),
                        hit("B.pdf", "alpha 和 beta 都在这里")));

        runner.execute(RUN_KEY, UID);

        RunUpdate finalUpdate = lastRunUpdate();
        assertEquals(0.0, finalUpdate.keywordHitRate, 1e-9);
        assertEquals(1, finalUpdate.keywordEvaluatedCount);
        ArgumentCaptor<RagEvalCaseResultBean> captor =
                ArgumentCaptor.forClass(RagEvalCaseResultBean.class);
        verify(resultMapper).install(captor.capture());
        assertFalse(captor.getValue().getKeywordHit());    }

    @Test
    void recoverStaleRuns_shouldOnlyFailRunsOlderThan30Minutes() {
        long now = System.currentTimeMillis();
        RagEvalRunBean stale = run(RagEvalStatus.QUEUED);
        stale.setRunKey("stale");
        stale.setStartedAt(now - 31 * 60 * 1000L);
        RagEvalRunBean fresh = run(RagEvalStatus.QUEUED);
        fresh.setRunKey("fresh");
        fresh.setStartedAt(now - 60 * 1000L);
        when(runMapper.select(any(PageRequest.class), any(RagEvalRunBean.class)))
                .thenAnswer(invocation -> {
                    RagEvalRunBean where = invocation.getArgument(1);
                    if (RagEvalStatus.QUEUED.equals(where.getStatus())) {
                        return Arrays.asList(stale, fresh);
                    }
                    return Collections.emptyList();
                });
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);

        runner.recoverStaleRuns();

        ArgumentCaptor<RagEvalRunBean> updateCaptor =
                ArgumentCaptor.forClass(RagEvalRunBean.class);
        ArgumentCaptor<RagEvalRunBean> whereCaptor =
                ArgumentCaptor.forClass(RagEvalRunBean.class);
        verify(runMapper).update(updateCaptor.capture(), whereCaptor.capture());
        assertEquals("stale", whereCaptor.getValue().getRunKey());
        assertEquals(RagEvalStatus.FAILED, updateCaptor.getValue().getStatus());
        assertTrue(updateCaptor.getValue().getErrorMessage().contains("30 分钟"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void stubRunAndDataset(RagEvalCaseBean... cases) {
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(run(RagEvalStatus.QUEUED)));
        RagEvalDatasetBean dataset = new RagEvalDatasetBean();
        dataset.setDatasetKey(DATASET_KEY);
        dataset.setName("评估集");
        dataset.setKbName(KB_NAME);
        dataset.setCaseSetKey("set-1");
        dataset.setLastuser(UID);
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.singletonList(dataset));
        when(caseMapper.select(any(PageRequest.class), any(RagEvalCaseBean.class)))
                .thenReturn(new ArrayList<RagEvalCaseBean>(Arrays.asList(cases)));
    }

    private static RagEvalRunBean run(String status) {
        RagEvalRunBean run = new RagEvalRunBean();
        run.setRunKey(RUN_KEY);
        run.setDatasetKey(DATASET_KEY);
        run.setTopK(5);
        run.setStatus(status);
        run.setTotal(1);
        run.setProcessed(0);
        run.setStartedAt(System.currentTimeMillis());
        run.setLastuser(UID);
        return run;
    }

    private static RagEvalCaseBean caseBean(String question, String expectedDoc,
                                            String... keywords) {
        RagEvalCaseBean bean = new RagEvalCaseBean();
        bean.setDatasetKey(DATASET_KEY);
        bean.setCaseSetKey("set-1");
        bean.setQuestion(question);
        bean.setExpectedDoc(expectedDoc);
        bean.setCategory("未分类");
        bean.setExpectedKeywords(RagEvalKeywordCodec.writeKeywords(
                new ArrayList<String>(Arrays.asList(keywords))));
        bean.setLastuser(UID);
        return bean;
    }

    private static KbSearchItem hit(String docName, String content) {
        KbSearchItem item = new KbSearchItem();
        item.setDocName(docName);
        item.setContent(content);
        return item;
    }

    private static KbSearchItem scoredHit(String docName, int chunkIndex, String content,
                                           double score, double denseScore,
                                           int lexicalScore, String channel) {
        KbSearchItem item = hit(docName, content);
        item.setChunkIndex(chunkIndex);
        item.setScore(score);
        item.setDenseScore(denseScore);
        item.setLexicalScore(lexicalScore);
        item.setChannel(channel);
        return item;
    }

    private static Result<KbSearchResult> searchResult(KbSearchItem... items) {
        KbSearchResult result = new KbSearchResult();
        result.setModel("embed-v1");
        result.setItems(new ArrayList<KbSearchItem>(Arrays.asList(items)));
        return Result.succeed(result, "ok");
    }

    private RunUpdate lastRunUpdate() {
        ArgumentCaptor<RagEvalRunBean> captor = ArgumentCaptor.forClass(RagEvalRunBean.class);
        verify(runMapper, org.mockito.Mockito.atLeastOnce())
                .update(captor.capture(), any(RagEvalRunBean.class));
        List<RagEvalRunBean> updates = captor.getAllValues();
        RagEvalRunBean last = updates.get(updates.size() - 1);
        RunUpdate update = new RunUpdate();
        update.status = last.getStatus();
        update.processed = last.getProcessed();
        update.total = last.getTotal();
        update.recall = last.getRecall() == null ? Double.NaN : last.getRecall();
        update.precision = last.getPrecision() == null ? Double.NaN : last.getPrecision();
        update.mrr = last.getMrr() == null ? Double.NaN : last.getMrr();
        update.ndcg = last.getNdcg() == null ? Double.NaN : last.getNdcg();
        update.keywordHitRate = last.getKeywordHitRate() == null
                ? Double.NaN : last.getKeywordHitRate();
        update.keywordEvaluatedCount = last.getKeywordEvaluatedCount() == null
                ? -1 : last.getKeywordEvaluatedCount();
        update.missCount = last.getMissCount() == null ? -1 : last.getMissCount();
        update.embeddingModel = last.getEmbeddingModel();
        update.metricSpec = last.getMetricSpec();
        update.retrievalMode = last.getRetrievalMode();
        update.rerankApplied = last.getRerankApplied();
        update.errorMessage = last.getErrorMessage();
        return update;
    }

    /** 只暴露断言需要的最终字段，避免测试里到处判空 */
    private static class RunUpdate {
        private String status;
        private Integer processed;
        private Integer total;
        private double recall;
        private double precision;
        private double mrr;
        private double ndcg;
        private double keywordHitRate;
        private int keywordEvaluatedCount;
        private int missCount;
        private String embeddingModel;
        private String metricSpec;
        private String retrievalMode;
        private Boolean rerankApplied;
        private String errorMessage;
    }
}
