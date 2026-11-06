package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.bean.RagEvalCaseBean;
import com.aiproject.ai.service.bean.RagEvalCaseResultBean;
import com.aiproject.ai.service.bean.RagEvalDatasetBean;
import com.aiproject.ai.service.bean.RagEvalRunBean;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalCaseBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalCaseResultBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalDatasetBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalRunBeanMapper;
import com.aiproject.ai.service.rag.RagEvalRunner;
import com.aiproject.ai.service.rag.RagEvalStatus;
import com.aiproject.ai.service.rag.dto.RagEvalCaseInput;
import com.aiproject.ai.service.rag.dto.RagEvalDatasetRequest;
import com.aiproject.ai.service.rag.dto.RagEvalHitDto;
import com.aiproject.ai.service.rag.dto.RagEvalRunDetail;
import com.aiproject.ai.service.rag.dto.RagEvalRunRequest;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 评估数据集与运行编排的单元测试。
 *
 * <p>覆盖点集中在「没有数据库事务时如何保证一致性」与「权限边界」：
 * 写时复制用例集、创建失败回收、活动运行保护、按归属查询，
 * 以及启动前就把期望文档对不上的配置错误拦下来。</p>
 */
@ExtendWith(MockitoExtension.class)
class RagEvalHolderTest {

    private static final String UID = "u1";

    @Mock
    RagEvalDatasetBeanMapper<RagEvalDatasetBean> datasetMapper;

    @Mock
    RagEvalCaseBeanMapper<RagEvalCaseBean> caseMapper;

    @Mock
    RagEvalRunBeanMapper<RagEvalRunBean> runMapper;

    @Mock
    RagEvalCaseResultBeanMapper<RagEvalCaseResultBean> resultMapper;

    @Mock
    KbDocBeanMapper<KbDocBean> kbDocBeanMapper;

    @Mock
    RagEvalRunner ragEvalRunner;

    @Mock
    TaskExecutor ragEvalExecutor;

    @InjectMocks
    RagEvalHolder holder;

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    @Test
    void createDataset_shouldRejectDuplicateQuestionBeforeWriting() {
        RagEvalDatasetRequest request = request(
                item("制度怎么请假？", "员工手册.pdf", "请假"),
                item("制度怎么请假？", "员工手册.pdf"));

        Result<String> result = holder.createDataset(UID, request);

        assertFailed(result, "重复问题");
        verify(datasetMapper, never()).install(any(RagEvalDatasetBean.class));
        verify(caseMapper, never()).install(any(RagEvalCaseBean.class));
    }

    @Test
    void createDataset_shouldNormalizeDefaultsAndPersistOwner() {
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.<RagEvalDatasetBean>emptyList());
        when(datasetMapper.install(any(RagEvalDatasetBean.class))).thenReturn(1);
        when(caseMapper.install(any(RagEvalCaseBean.class))).thenReturn(1);

        RagEvalCaseInput input = item("  试用期多久？  ", " 员工手册.pdf ", " 试用期 ");
        input.setCategory(" ");
        Result<String> result = holder.createDataset(UID, request(input));

        assertTrue(ok(result), result.getResp_msg());
        assertNotNull(result.getDatas());

        ArgumentCaptor<RagEvalDatasetBean> datasetCaptor =
                ArgumentCaptor.forClass(RagEvalDatasetBean.class);
        verify(datasetMapper).install(datasetCaptor.capture());
        RagEvalDatasetBean dataset = datasetCaptor.getValue();
        assertEquals(UID, dataset.getLastuser());
        assertEquals("评估集", dataset.getName());
        assertEquals("默认知识库", dataset.getKbName());
        assertEquals(1, dataset.getCaseCount().intValue());
        assertFalse(dataset.getCaseSetKey() == null || dataset.getCaseSetKey().isEmpty(),
                "必须生成用例集版本键，编辑时靠它做写时复制");

        ArgumentCaptor<RagEvalCaseBean> caseCaptor =
                ArgumentCaptor.forClass(RagEvalCaseBean.class);
        verify(caseMapper).install(caseCaptor.capture());
        RagEvalCaseBean saved = caseCaptor.getValue();
        assertEquals("试用期多久？", saved.getQuestion());
        assertEquals("员工手册.pdf", saved.getExpectedDoc());
        assertEquals(UID, saved.getLastuser());
        assertEquals("未分类", saved.getCategory());
        assertEquals("[\"试用期\"]", saved.getExpectedKeywords());
        assertEquals(dataset.getCaseSetKey(), saved.getCaseSetKey());
    }

    @Test
    void createDataset_shouldRollbackDatasetWhenCaseInsertFails() {
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.<RagEvalDatasetBean>emptyList());
        when(datasetMapper.install(any(RagEvalDatasetBean.class))).thenReturn(1);
        when(caseMapper.install(any(RagEvalCaseBean.class))).thenReturn(0);

        Result<String> result = holder.createDataset(UID, request(item("问题", "文档.pdf")));

        assertFailed(result, "用例保存失败");
        ArgumentCaptor<RagEvalCaseBean> caseWhere = ArgumentCaptor.forClass(RagEvalCaseBean.class);
        verify(caseMapper).delete(caseWhere.capture());
        assertEquals(UID, caseWhere.getValue().getLastuser(), "回收用例也必须限定归属");
        ArgumentCaptor<RagEvalDatasetBean> datasetWhere =
                ArgumentCaptor.forClass(RagEvalDatasetBean.class);
        verify(datasetMapper).delete(datasetWhere.capture());
        assertEquals(UID, datasetWhere.getValue().getLastuser());
    }

    // ------------------------------------------------------------------
    // 编辑：写时复制
    // ------------------------------------------------------------------

    @Test
    void updateDataset_shouldSwitchToNewCaseSetAndDeleteOldOne() {
        RagEvalDatasetBean existing = dataset("old-set-key");
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.singletonList(existing));
        when(caseMapper.install(any(RagEvalCaseBean.class))).thenReturn(1);
        when(datasetMapper.update(any(RagEvalDatasetBean.class), any(RagEvalDatasetBean.class)))
                .thenReturn(1);

        RagEvalDatasetRequest request = request(item("新问题", "新文档.pdf"));
        request.setDatasetKey("dk1");
        Result<Boolean> result = holder.updateDataset(UID, request);

        assertTrue(ok(result), result.getResp_msg());
        ArgumentCaptor<RagEvalDatasetBean> updateCaptor =
                ArgumentCaptor.forClass(RagEvalDatasetBean.class);
        verify(datasetMapper).update(updateCaptor.capture(), any(RagEvalDatasetBean.class));
        String newCaseSetKey = updateCaptor.getValue().getCaseSetKey();
        assertNotNull(newCaseSetKey);
        assertNotEquals("old-set-key", newCaseSetKey);

        ArgumentCaptor<RagEvalCaseBean> inserted =
                ArgumentCaptor.forClass(RagEvalCaseBean.class);
        verify(caseMapper).install(inserted.capture());
        assertEquals(newCaseSetKey, inserted.getValue().getCaseSetKey());

        ArgumentCaptor<RagEvalCaseBean> deleted =
                ArgumentCaptor.forClass(RagEvalCaseBean.class);
        verify(caseMapper).delete(deleted.capture());
        assertEquals("old-set-key", deleted.getValue().getCaseSetKey());
        assertEquals(UID, deleted.getValue().getLastuser());
    }

    @Test
    void updateDataset_shouldKeepOldCaseSetWhenNewCasesFail() {
        RagEvalDatasetBean existing = dataset("old-set-key");
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.singletonList(existing));
        when(caseMapper.install(any(RagEvalCaseBean.class))).thenReturn(0);

        RagEvalDatasetRequest request = request(item("新问题", "新文档.pdf"));
        request.setDatasetKey("dk1");
        Result<Boolean> result = holder.updateDataset(UID, request);

        assertFailed(result, "原数据集未改动");
        verify(datasetMapper, never()).update(any(RagEvalDatasetBean.class),
                any(RagEvalDatasetBean.class));
        ArgumentCaptor<RagEvalCaseBean> deleted =
                ArgumentCaptor.forClass(RagEvalCaseBean.class);
        verify(caseMapper).delete(deleted.capture());
        assertNotEquals("old-set-key", deleted.getValue().getCaseSetKey(),
                "清理的只能是本次新建的用例集");
    }

    @Test
    void updateDataset_shouldRejectOtherUsersDataset() {
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.<RagEvalDatasetBean>emptyList());

        RagEvalDatasetRequest request = request(item("问题", "文档.pdf"));
        request.setDatasetKey("other-key");
        Result<Boolean> result = holder.updateDataset(UID, request);

        assertFailed(result, "不属于当前用户");
        verify(caseMapper, never()).install(any(RagEvalCaseBean.class));
    }

    // ------------------------------------------------------------------
    // 删除与运行记录
    // ------------------------------------------------------------------

    @Test
    void deleteDataset_shouldRejectWhileRunIsActive() {
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.singletonList(dataset("set-key")));
        RagEvalRunBean running = run("r1", RagEvalStatus.RUNNING, 1);
        when(runMapper.select(any(PageRequest.class), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(running));

        Result<Boolean> result = holder.deleteDataset(UID, "dk1");

        assertFailed(result, "正在进行的评估");
        verify(runMapper, never()).delete(any(RagEvalRunBean.class));
        verify(datasetMapper, never()).delete(any(RagEvalDatasetBean.class));
    }

    @Test
    void deleteDataset_shouldCascadeRunsResultsCasesAndDataset() {
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.singletonList(dataset("set-key")));
        RagEvalRunBean finished = run("r1", RagEvalStatus.SUCCESS, 1);
        when(runMapper.select(any(PageRequest.class), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(finished));
        when(datasetMapper.delete(any(RagEvalDatasetBean.class))).thenReturn(1);

        Result<Boolean> result = holder.deleteDataset(UID, "dk1");

        assertTrue(ok(result), result.getResp_msg());
        verify(resultMapper).delete(any(RagEvalCaseResultBean.class));
        verify(runMapper).delete(any(RagEvalRunBean.class));
        verify(caseMapper).delete(any(RagEvalCaseBean.class));
        verify(datasetMapper).delete(any(RagEvalDatasetBean.class));
    }

    @Test
    void deleteRun_shouldRejectActiveRun() {
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(run("r1", RagEvalStatus.QUEUED, 1)));

        Result<Boolean> result = holder.deleteRun(UID, "r1");

        assertFailed(result, "暂不能删除");
        verify(runMapper, never()).delete(any(RagEvalRunBean.class));
    }

    // ------------------------------------------------------------------
    // 启动评估
    // ------------------------------------------------------------------

    @Test
    void startRun_shouldRejectTopKOutOfRange() {
        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setDatasetKey("dk1");
        request.setTopK(KbRagHolder.MAX_TOP_K + 1);

        Result<String> result = holder.startRun(UID, request);

        assertFailed(result, "1-" + KbRagHolder.MAX_TOP_K);
        verify(datasetMapper, never()).select(isNull(), any(RagEvalDatasetBean.class));
    }

    @Test
    void startRun_shouldFailBeforeEmbeddingWhenExpectedDocsMissing() {
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.singletonList(dataset("set-key")));
        when(caseMapper.select(any(PageRequest.class), any(RagEvalCaseBean.class)))
                .thenReturn(Collections.singletonList(caseBean("问题", "不存在.pdf")));
        KbDocBean doc = new KbDocBean();
        doc.setName("已上传.pdf");
        when(kbDocBeanMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(doc));

        Result<String> result = holder.startRun(UID, runRequest("dk1", 5));

        assertFailed(result, "不存在.pdf");
        verify(runMapper, never()).install(any(RagEvalRunBean.class));
        verify(ragEvalExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void startRun_shouldRejectWhenSameDatasetAlreadyRunning() {
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.singletonList(dataset("set-key")));
        when(caseMapper.select(any(PageRequest.class), any(RagEvalCaseBean.class)))
                .thenReturn(Collections.singletonList(caseBean("问题", "文档.pdf")));
        KbDocBean doc = new KbDocBean();
        doc.setName("文档.pdf");
        when(kbDocBeanMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(doc));
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(run("running", RagEvalStatus.RUNNING, 1)));

        Result<String> result = holder.startRun(UID, runRequest("dk1", 5));

        assertFailed(result, "正在进行的评估");
        verify(runMapper, never()).install(any(RagEvalRunBean.class));
    }

    @Test
    void startRun_shouldCreateQueuedRunAndSubmitAsyncTask() {
        stubRunnableDataset();
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.<RagEvalRunBean>emptyList());
        when(runMapper.install(any(RagEvalRunBean.class))).thenReturn(1);
        doNothing().when(ragEvalExecutor).execute(any(Runnable.class));

        Result<String> result = holder.startRun(UID, runRequest("dk1", 8));

        assertTrue(ok(result), result.getResp_msg());
        assertNotNull(result.getDatas());

        ArgumentCaptor<RagEvalRunBean> captor = ArgumentCaptor.forClass(RagEvalRunBean.class);
        verify(runMapper).install(captor.capture());
        RagEvalRunBean run = captor.getValue();
        assertEquals(result.getDatas(), run.getRunKey());
        assertEquals(RagEvalStatus.QUEUED, run.getStatus());
        assertEquals(8, run.getTopK().intValue());
        assertEquals(2, run.getTotal().intValue());
        assertEquals(0, run.getProcessed().intValue());
        assertEquals(UID, run.getLastuser());
        verify(ragEvalExecutor).execute(any(Runnable.class));
    }

    @Test
    void startRun_shouldMarkRunFailedWhenExecutorRejects() {
        stubRunnableDataset();
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.<RagEvalRunBean>emptyList());
        when(runMapper.install(any(RagEvalRunBean.class))).thenReturn(1);
        when(runMapper.update(any(RagEvalRunBean.class), any(RagEvalRunBean.class))).thenReturn(1);
        doThrow(new RuntimeException("queue full")).when(ragEvalExecutor)
                .execute(any(Runnable.class));

        Result<String> result = holder.startRun(UID, runRequest("dk1", 5));

        assertFailed(result, "队列繁忙");
        ArgumentCaptor<RagEvalRunBean> captor = ArgumentCaptor.forClass(RagEvalRunBean.class);
        verify(runMapper).update(captor.capture(), any(RagEvalRunBean.class));
        assertEquals(RagEvalStatus.FAILED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getErrorMessage());
    }

    // ------------------------------------------------------------------
    // 查询与对比
    // ------------------------------------------------------------------

    @Test
    void getRun_shouldScopeToOwnerAndPickEarlierComparableBaseline() {
        RagEvalRunBean current = run("r3", RagEvalStatus.SUCCESS, 10);
        RagEvalRunBean newerFailed = run("r4", RagEvalStatus.FAILED, 12);
        RagEvalRunBean earlierSuccess = run("r2", RagEvalStatus.SUCCESS, 5);
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(current));
        when(runMapper.select(any(PageRequest.class), any(RagEvalRunBean.class)))
                .thenReturn(Arrays.asList(current, newerFailed, earlierSuccess));
        when(resultMapper.select(any(PageRequest.class), any(RagEvalCaseResultBean.class)))
                .thenReturn(Collections.<RagEvalCaseResultBean>emptyList());

        Result<RagEvalRunDetail> result = holder.getRun(UID, "r3");

        assertTrue(ok(result), result.getResp_msg());
        RagEvalRunDetail detail = result.getDatas();
        assertEquals("r3", detail.getRun().getRunKey());
        assertNotNull(detail.getBaseline());
        assertEquals("r2", detail.getBaseline().getRunKey(), "基线必须是更早且可比较的运行");
    }

    @Test
    void getRun_shouldParseTopKHitsAndTolerateLegacyRows() {
        RagEvalRunBean current = run("r1", RagEvalStatus.SUCCESS, 10);
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(current));
        when(runMapper.select(any(PageRequest.class), any(RagEvalRunBean.class)))
                .thenReturn(Collections.singletonList(current));

        RagEvalCaseResultBean withHits = new RagEvalCaseResultBean();
        withHits.setCaseIndex(0);
        withHits.setRetrievedDocs("[\"A.pdf\"]");
        withHits.setRetrievedHits("[{\"rank\":1,\"docName\":\"A.pdf\",\"chunkIndex\":3,"
                + "\"score\":0.0328,\"denseScore\":0.91,\"lexicalScore\":2,"
                + "\"channel\":\"both\",\"contentPreview\":\"预览\"}]");
        RagEvalCaseResultBean legacy = new RagEvalCaseResultBean();
        legacy.setCaseIndex(1);
        legacy.setRetrievedDocs("[\"B.pdf\"]");
        when(resultMapper.select(any(PageRequest.class), any(RagEvalCaseResultBean.class)))
                .thenReturn(Arrays.asList(withHits, legacy));

        Result<RagEvalRunDetail> result = holder.getRun(UID, "r1");

        assertTrue(ok(result), result.getResp_msg());
        RagEvalHitDto hit = result.getDatas().getResults().get(0).getRetrievedHits().get(0);
        assertEquals(1, hit.getRank().intValue());
        assertEquals("A.pdf", hit.getDocName());
        assertEquals(3, hit.getChunkIndex().intValue());
        assertEquals(0.91, hit.getDenseScore(), 1e-9);
        assertEquals("both", hit.getChannel());
        assertEquals("预览", hit.getContentPreview());
        assertTrue(result.getDatas().getResults().get(1).getRetrievedHits().isEmpty(),
                "旧运行没有明细时应返回空列表，不能让详情页整体失败");
    }

    @Test
    void getRun_shouldNotReturnAnotherUsersRun() {
        when(runMapper.select(isNull(), any(RagEvalRunBean.class)))
                .thenReturn(Collections.<RagEvalRunBean>emptyList());

        Result<RagEvalRunDetail> result = holder.getRun(UID, "other-run");

        assertFailed(result, "不属于当前用户");
        verify(resultMapper, never()).select(any(PageRequest.class),
                any(RagEvalCaseResultBean.class));
    }

    @Test
    void listRuns_shouldFilterByCurrentUser() {
        when(runMapper.select(any(PageRequest.class), any(RagEvalRunBean.class)))
                .thenReturn(Collections.<RagEvalRunBean>emptyList());

        holder.listRuns(UID, "dk1");

        ArgumentCaptor<RagEvalRunBean> captor = ArgumentCaptor.forClass(RagEvalRunBean.class);
        verify(runMapper).select(any(PageRequest.class), captor.capture());
        assertEquals(UID, captor.getValue().getLastuser());
        assertEquals("dk1", captor.getValue().getDatasetKey());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void stubRunnableDataset() {
        RagEvalDatasetBean dataset = dataset("set-key");
        when(datasetMapper.select(isNull(), any(RagEvalDatasetBean.class)))
                .thenReturn(Collections.singletonList(dataset));
        when(caseMapper.select(any(PageRequest.class), any(RagEvalCaseBean.class)))
                .thenReturn(Arrays.asList(
                        caseBean("问题一", "文档.pdf"),
                        caseBean("问题二", "文档.pdf")));
        KbDocBean doc = new KbDocBean();
        doc.setName("文档.pdf");
        when(kbDocBeanMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(doc));
    }

    private static RagEvalDatasetBean dataset(String caseSetKey) {
        RagEvalDatasetBean bean = new RagEvalDatasetBean();
        bean.setDatasetKey("dk1");
        bean.setName("评估集");
        bean.setKbName("默认知识库");
        bean.setCaseCount(2);
        bean.setCaseSetKey(caseSetKey);
        bean.setLastuser(UID);
        return bean;
    }

    private static RagEvalCaseBean caseBean(String question, String doc) {
        RagEvalCaseBean bean = new RagEvalCaseBean();
        bean.setDatasetKey("dk1");
        bean.setCaseSetKey("set-key");
        bean.setQuestion(question);
        bean.setExpectedDoc(doc);
        bean.setCategory("未分类");
        bean.setLastuser(UID);
        return bean;
    }

    private static RagEvalRunBean run(String runKey, String status, long iid) {
        RagEvalRunBean bean = new RagEvalRunBean();
        bean.setIid(iid);
        bean.setRunKey(runKey);
        bean.setDatasetKey("dk1");
        bean.setStatus(status);
        bean.setLastuser(UID);
        bean.setTotal(2);
        bean.setProcessed(2);
        return bean;
    }

    private static RagEvalDatasetRequest request(RagEvalCaseInput... cases) {
        RagEvalDatasetRequest request = new RagEvalDatasetRequest();
        request.setName(" 评估集 ");
        request.setKbName("");
        request.setDescription("说明");
        request.setCases(new ArrayList<RagEvalCaseInput>(Arrays.asList(cases)));
        return request;
    }

    private static RagEvalCaseInput item(String question, String doc, String... keywords) {
        RagEvalCaseInput input = new RagEvalCaseInput();
        input.setQuestion(question);
        input.setExpectedDoc(doc);
        input.setKeywords(new ArrayList<String>(Arrays.asList(keywords)));
        return input;
    }

    private static RagEvalRunRequest runRequest(String datasetKey, Integer topK) {
        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setDatasetKey(datasetKey);
        request.setTopK(topK);
        return request;
    }

    private static boolean ok(Result<?> result) {
        return result != null && Integer.valueOf(0).equals(result.getResp_code());
    }

    private static void assertFailed(Result<?> result, String messagePart) {
        assertNotNull(result);
        assertFalse(ok(result), "预期失败，但返回成功：" + result.getResp_msg());
        assertTrue(result.getResp_msg() != null && result.getResp_msg().contains(messagePart),
                "错误信息应包含「" + messagePart + "」，实际：" + result.getResp_msg());
    }
}
