package com.aiproject.ai.service.rag;

import com.aiproject.ai.service.bean.KbChunkBean;
import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.bean.KbDocContentBean;
import com.aiproject.ai.service.gateway.EmbeddingClient;
import com.aiproject.ai.service.mapper.KbChunkBeanMapper;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.aiproject.ai.service.mapper.KbDocContentBeanMapper;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档处理执行器的单元测试。
 *
 * <p>重点不是「跑通一次成功」，而是验证失败路径不会留下半成品：
 * 向量化失败不写分块、分块写一半要回滚、处理中途被删要立刻停止、
 * 僵死任务要能回收，否则用户会永远看到「处理中」且无法重新上传。</p>
 */
@ExtendWith(MockitoExtension.class)
class KbDocRunnerTest {

    private static final String UID = "u1";
    private static final Long DOC_IID = 100L;

    @Mock
    KbDocBeanMapper<KbDocBean> docMapper;

    @Mock
    KbDocContentBeanMapper<KbDocContentBean> contentMapper;

    @Mock
    KbChunkBeanMapper<KbChunkBean> chunkMapper;

    @Mock
    EmbeddingClient embeddingClient;

    @InjectMocks
    KbDocRunner runner;

    @Test
    void execute_shouldChunkEmbedInBatchesAndMarkReady() throws Exception {
        String content = paragraphs(70);
        KbDocBean doc = stubQueuedDoc(content);
        doc.setKbName("默认知识库");
        when(embeddingClient.resolve()).thenReturn(embedding());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), anyList()))
                .thenAnswer(invocation -> vectors(invocation.getArgument(1)));
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);
        when(chunkMapper.install(any(KbChunkBean.class))).thenReturn(1);

        runner.execute(DOC_IID, UID);

        // 70 块按 32/32/6 分三批，不能一次性把全文发给向量服务
        ArgumentCaptor<List> batches = ArgumentCaptor.forClass(List.class);
        verify(embeddingClient, times(3))
                .embed(any(EmbeddingClient.ResolvedEmbedding.class), batches.capture());
        assertEquals(32, batches.getAllValues().get(0).size());
        assertEquals(32, batches.getAllValues().get(1).size());
        assertEquals(6, batches.getAllValues().get(2).size());

        ArgumentCaptor<KbChunkBean> chunkCaptor = ArgumentCaptor.forClass(KbChunkBean.class);
        verify(chunkMapper, times(70)).install(chunkCaptor.capture());
        KbChunkBean first = chunkCaptor.getAllValues().get(0);
        assertEquals(DOC_IID, first.getDocIid());
        assertEquals("默认知识库", first.getKbName());
        assertEquals(0, first.getChunkIndex().intValue());
        assertEquals(UID, first.getLastuser());
        assertEquals("text-embedding-3-small", first.getEmbeddingModel());
        assertEquals(2, first.getEmbeddingDim().intValue());
        assertNotNull(first.getEmbedding());

        // 状态推进使用 CAS：第一次更新必须带上前置状态 queued
        ArgumentCaptor<KbDocBean> updateCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        ArgumentCaptor<KbDocBean> whereCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(docMapper, atLeastOnce()).update(updateCaptor.capture(), whereCaptor.capture());
        assertEquals(KbDocStatus.QUEUED, whereCaptor.getAllValues().get(0).getStatus());

        KbDocBean last = updateCaptor.getAllValues().get(updateCaptor.getAllValues().size() - 1);
        assertEquals(KbDocStatus.READY, last.getStatus());
        assertEquals(100, last.getProgress().intValue());
        assertEquals(70, last.getChunkCount().intValue());
        assertEquals(Long.valueOf(content.length()), last.getCharCount());
        assertEquals("text-embedding-3-small", last.getEmbeddingModel());
        assertNotNull(last.getFinishedAt());
    }

    @Test
    void execute_shouldFailWithoutWritingChunksWhenEmbeddingKeepsFailing() throws Exception {
        stubQueuedDoc(paragraphs(2));
        when(embeddingClient.resolve()).thenReturn(embedding());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), anyList()))
                .thenThrow(new RuntimeException("向量服务超时"));
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);

        runner.execute(DOC_IID, UID);

        verify(embeddingClient, times(2))
                .embed(any(EmbeddingClient.ResolvedEmbedding.class), anyList());
        verify(chunkMapper, never()).install(any(KbChunkBean.class));

        KbDocBean last = lastUpdate();
        assertEquals(KbDocStatus.FAILED, last.getStatus());
        assertTrue(last.getErrorMsg().contains("向量化失败"));
        assertTrue(last.getErrorMsg().contains("向量服务超时"));
    }

    @Test
    void execute_shouldFailWhenEmbeddingReturnsWrongVectorCount() throws Exception {
        stubQueuedDoc(paragraphs(2));
        when(embeddingClient.resolve()).thenReturn(embedding());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), anyList()))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);

        runner.execute(DOC_IID, UID);

        verify(chunkMapper, never()).install(any(KbChunkBean.class));
        KbDocBean last = lastUpdate();
        assertEquals(KbDocStatus.FAILED, last.getStatus());
        assertTrue(last.getErrorMsg().contains("数量不一致"));
    }

    @Test
    void execute_shouldRollbackChunksWhenInstallFails() throws Exception {
        stubQueuedDoc(paragraphs(2));
        when(embeddingClient.resolve()).thenReturn(embedding());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), anyList()))
                .thenAnswer(invocation -> vectors(invocation.getArgument(1)));
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);
        // 第一块成功、第二块失败
        when(chunkMapper.install(any(KbChunkBean.class))).thenReturn(1, 0);

        runner.execute(DOC_IID, UID);

        KbDocBean last = lastUpdate();
        assertEquals(KbDocStatus.FAILED, last.getStatus());
        assertTrue(last.getErrorMsg().contains("分块保存失败"));

        ArgumentCaptor<KbChunkBean> deleteCaptor = ArgumentCaptor.forClass(KbChunkBean.class);
        verify(chunkMapper, atLeastOnce()).delete(deleteCaptor.capture());
        for (KbChunkBean where : deleteCaptor.getAllValues()) {
            assertEquals(DOC_IID, where.getDocIid(), "回滚必须清掉本文件的残留分块");
            assertEquals(UID, where.getLastuser(), "回滚删除也要带归属，避免误删他人分块");
        }
    }

    @Test
    void execute_shouldStopWhenDocumentDeletedDuringEmbedding() throws Exception {
        stubQueuedDoc(paragraphs(2));
        when(embeddingClient.resolve()).thenReturn(embedding());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), anyList()))
                .thenAnswer(invocation -> vectors(invocation.getArgument(1)));
        // queued→chunking 成功、chunking→embedding 成功、进度更新时发现文档已被删除
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1, 1, 0);

        runner.execute(DOC_IID, UID);

        verify(embeddingClient, times(1))
                .embed(any(EmbeddingClient.ResolvedEmbedding.class), anyList());
        verify(chunkMapper, never()).install(any(KbChunkBean.class));
    }

    @Test
    void execute_shouldSkipWhenDocumentIsNoLongerQueued() {
        KbDocBean running = doc(KbDocStatus.EMBEDDING);
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(running));

        runner.execute(DOC_IID, UID);

        verify(docMapper, never()).update(any(KbDocBean.class), any(KbDocBean.class));
        verify(embeddingClient, never()).resolve();
    }

    @Test
    void execute_shouldFailWhenOriginalContentIsMissing() {
        KbDocBean doc = doc(KbDocStatus.QUEUED);
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(doc));
        when(contentMapper.select(isNull(), any(KbDocContentBean.class)))
                .thenReturn(Collections.<KbDocContentBean>emptyList());
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);

        runner.execute(DOC_IID, UID);

        KbDocBean last = lastUpdate();
        assertEquals(KbDocStatus.FAILED, last.getStatus());
        assertTrue(last.getErrorMsg().contains("找不到原始正文"));
        verify(embeddingClient, never()).resolve();
    }

    @Test
    void execute_shouldRejectDocumentWithTooManyChunks() {
        // 解析入口限制 20 万字符；执行器仍要独立兜底，避免绕过接口直接写库的脏数据
        String huge = repeatChars('甲', 200200);
        stubQueuedDoc(huge);
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);

        runner.execute(DOC_IID, UID);

        KbDocBean last = lastUpdate();
        assertEquals(KbDocStatus.FAILED, last.getStatus());
        assertTrue(last.getErrorMsg().contains("分块过多"));
        verify(embeddingClient, never()).resolve();
        verify(chunkMapper, never()).install(any(KbChunkBean.class));
    }

    @Test
    void recoverStaleDocs_shouldCleanChunksAndFailOnlyExpiredTasks() {
        long now = System.currentTimeMillis();
        KbDocBean stale = doc(KbDocStatus.QUEUED);
        stale.setIid(7L);
        stale.setLastuser("u2");
        stale.setStartedAt(now - 31 * 60 * 1000L);
        KbDocBean fresh = doc(KbDocStatus.QUEUED);
        fresh.setIid(8L);
        fresh.setStartedAt(now - 60 * 1000L);

        when(docMapper.select(any(PageRequest.class), any(KbDocBean.class)))
                .thenAnswer(invocation -> {
                    KbDocBean where = invocation.getArgument(1);
                    if (KbDocStatus.QUEUED.equals(where.getStatus())) {
                        return Arrays.asList(stale, fresh);
                    }
                    return Collections.emptyList();
                });
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);

        runner.recoverStaleDocs();

        ArgumentCaptor<KbChunkBean> deleteCaptor = ArgumentCaptor.forClass(KbChunkBean.class);
        verify(chunkMapper).delete(deleteCaptor.capture());
        assertEquals(Long.valueOf(7L), deleteCaptor.getValue().getDocIid(),
                "未超时的文档不应被回收");
        assertEquals("u2", deleteCaptor.getValue().getLastuser());

        ArgumentCaptor<KbDocBean> updateCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        ArgumentCaptor<KbDocBean> whereCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(docMapper).update(updateCaptor.capture(), whereCaptor.capture());
        assertEquals(Long.valueOf(7L), whereCaptor.getValue().getIid());
        assertEquals(KbDocStatus.QUEUED, whereCaptor.getValue().getStatus(),
                "回收必须带活动状态做 CAS，不能覆盖刚完成的 ready");
        assertEquals(KbDocStatus.FAILED, updateCaptor.getValue().getStatus());
        assertTrue(updateCaptor.getValue().getErrorMsg().contains("30 分钟"));

        // 先 CAS 标记失败，成功后才清残片；顺序反了可能删掉刚就绪文档的分块
        InOrder order = inOrder(docMapper, chunkMapper);
        order.verify(docMapper).update(any(KbDocBean.class), any(KbDocBean.class));
        order.verify(chunkMapper).delete(any(KbChunkBean.class));
    }

    /** Runner 在回收前刚好完成：CAS 更新 0 行，不能删除它刚落库的分块 */
    @Test
    void recoverStaleDocs_shouldNotDeleteChunksWhenTaskBecameReady() {
        KbDocBean stale = doc(KbDocStatus.QUEUED);
        stale.setIid(7L);
        stale.setLastuser("u2");
        stale.setStartedAt(System.currentTimeMillis() - 31 * 60 * 1000L);

        when(docMapper.select(any(PageRequest.class), any(KbDocBean.class)))
                .thenAnswer(invocation -> {
                    KbDocBean where = invocation.getArgument(1);
                    if (KbDocStatus.QUEUED.equals(where.getStatus())) {
                        return Collections.singletonList(stale);
                    }
                    return Collections.emptyList();
                });
        // 模拟 Runner 已完成并置 ready：状态条件匹配不到
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(0);

        runner.recoverStaleDocs();

        verify(chunkMapper, never()).delete(any(KbChunkBean.class));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private KbDocBean stubQueuedDoc(String content) {
        KbDocBean doc = doc(KbDocStatus.QUEUED);
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(doc));
        KbDocContentBean source = new KbDocContentBean();
        source.setDocIid(DOC_IID);
        source.setContent(content);
        source.setLastuser(UID);
        when(contentMapper.select(isNull(), any(KbDocContentBean.class)))
                .thenReturn(Collections.singletonList(source));
        return doc;
    }

    private static KbDocBean doc(String status) {
        KbDocBean doc = new KbDocBean();
        doc.setIid(DOC_IID);
        doc.setKbName("默认知识库");
        doc.setName("制度.txt");
        doc.setStatus(status);
        doc.setLastuser(UID);
        return doc;
    }

    private static EmbeddingClient.ResolvedEmbedding embedding() {
        return new EmbeddingClient.ResolvedEmbedding(
                "https://api.example.com/v1", "sk-test", "text-embedding-3-small", "测试向量模型");
    }

    private static List<double[]> vectors(List<String> batch) {
        List<double[]> list = new ArrayList<double[]>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            list.add(new double[]{1.0, 0.5});
        }
        return list;
    }

    private KbDocBean lastUpdate() {
        ArgumentCaptor<KbDocBean> captor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(docMapper, atLeastOnce()).update(captor.capture(), any(KbDocBean.class));
        List<KbDocBean> values = captor.getAllValues();
        return values.get(values.size() - 1);
    }

    /** 每段 300 字符、空行分隔：打包时一段一块，数量可精确预期 */
    private static String paragraphs(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("第").append(i + 1).append("段").append(repeatChars('甲', 296));
        }
        return sb.toString();
    }

    private static String repeatChars(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
