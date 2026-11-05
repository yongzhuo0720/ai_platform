package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.bean.KbDocContentBean;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.aiproject.ai.service.mapper.KbDocContentBeanMapper;
import com.aiproject.ai.service.rag.KbDocRunner;
import com.aiproject.ai.service.rag.KbDocStatus;
import com.aiproject.ai.service.rag.KbDocTextParser;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档上传入口的单元测试。
 *
 * <p>接口层只做「解析 + 校验 + 去重 + 建任务」，因此这里验证两类边界：
 * 一是坏输入（超限、格式不支持、重复、并发过多）在落库前就被拒绝；
 * 二是任务提交失败时文档不能停在 queued，否则前端会永远转圈。</p>
 */
@ExtendWith(MockitoExtension.class)
class KbDocPipelineHolderTest {

    private static final String UID = "u1";
    private static final Long DOC_IID = 101L;
    private static final String HASH = KbDocTextParser.sha256("差旅报销制度");

    @Mock
    KbDocBeanMapper<KbDocBean> docMapper;

    @Mock
    KbDocContentBeanMapper<KbDocContentBean> contentMapper;

    @Mock
    KbDocRunner runner;

    @Mock
    TaskExecutor kbDocExecutor;

    @InjectMocks
    KbDocPipelineHolder holder;

    @Test
    void uploadFile_shouldParseTextAndScheduleBackgroundTask() {
        stubFreshUpload();
        MockMultipartFile file = new MockMultipartFile(
                "file", "制度.txt", "text/plain", "差旅报销制度".getBytes(StandardCharsets.UTF_8));

        Result<KbDocBean> result = holder.uploadFile(
                UID, file, null, null, null, null, null, null);

        assertNotNull(result.getDatas());
        assertEquals(DOC_IID, result.getDatas().getIid());
        assertEquals("文档已受理，正在后台处理", result.getResp_msg());
        assertNull(result.getDatas().getDocKey(), "内部定位字段不进入响应");
        assertNull(result.getDatas().getContentHash(), "正文哈希不进入响应");

        ArgumentCaptor<KbDocBean> docCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(docMapper).install(docCaptor.capture());
        KbDocBean saved = docCaptor.getValue();
        assertEquals(KbDocStatus.QUEUED, saved.getStatus());
        assertEquals(0, saved.getProgress().intValue());
        assertEquals(1, saved.getAttemptCount().intValue());
        assertEquals("制度.txt", saved.getName());
        assertEquals(KbDocPipelineHolder.DEFAULT_KB_NAME, saved.getKbName());
        assertEquals("txt", saved.getFileType());
        assertEquals(HASH, saved.getContentHash());
        assertEquals(Long.valueOf("差旅报销制度".length()), saved.getCharCount());
        assertNotNull(saved.getDocKey());
        assertNotNull(saved.getStartedAt());
        assertEquals(UID, saved.getLastuser());

        ArgumentCaptor<KbDocContentBean> contentCaptor =
                ArgumentCaptor.forClass(KbDocContentBean.class);
        verify(contentMapper).install(contentCaptor.capture());
        assertEquals(DOC_IID, contentCaptor.getValue().getDocIid());
        assertEquals("差旅报销制度", contentCaptor.getValue().getContent());
        assertEquals(HASH, contentCaptor.getValue().getContentHash());
        assertEquals(UID, contentCaptor.getValue().getLastuser());

        verify(kbDocExecutor).execute(any(Runnable.class));
    }

    @Test
    void uploadFile_shouldAcceptTextExtractedFromPdfByBrowser() {
        stubFreshUpload();

        Result<KbDocBean> result = holder.uploadFile(
                UID, null, "  扫描件提取正文  ", null, "合规库", "pdf", "员工手册.pdf", 2048L);

        assertNotNull(result.getDatas());
        ArgumentCaptor<KbDocBean> docCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(docMapper).install(docCaptor.capture());
        KbDocBean saved = docCaptor.getValue();
        assertEquals("员工手册.pdf", saved.getName(), "未填名称时使用浏览器上报的原文件名");
        assertEquals("pdf", saved.getFileType());
        assertEquals(Long.valueOf(2048L), saved.getSizeBytes());
        assertEquals("合规库", saved.getKbName());

        ArgumentCaptor<KbDocContentBean> contentCaptor =
                ArgumentCaptor.forClass(KbDocContentBean.class);
        verify(contentMapper).install(contentCaptor.capture());
        assertEquals("扫描件提取正文", contentCaptor.getValue().getContent());
    }

    @Test
    void uploadFile_shouldRejectBinaryAndUnsupportedTypesBeforeInsert() {
        MockMultipartFile exe = new MockMultipartFile(
                "file", "安装包.exe", "application/octet-stream", new byte[]{1, 2, 3});

        Result<KbDocBean> result = holder.uploadFile(
                UID, exe, null, null, null, null, null, null);

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("暂不支持解析"));
        verify(docMapper, never()).install(any(KbDocBean.class));
        verify(contentMapper, never()).install(any(KbDocContentBean.class));
    }

    @Test
    void uploadFile_shouldRejectFileLargerThanLimit() {
        MultipartFile huge = mock(MultipartFile.class);
        when(huge.isEmpty()).thenReturn(false);
        when(huge.getSize()).thenReturn(KbDocTextParser.MAX_FILE_BYTES + 1);

        Result<KbDocBean> result = holder.uploadFile(
                UID, huge, null, "超大文件.txt", null, null, null, null);

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("文件过大"));
        verify(docMapper, never()).install(any(KbDocBean.class));
    }

    @Test
    void uploadFile_shouldRejectWhenNoFileAndNoContent() {
        Result<KbDocBean> result = holder.uploadFile(
                UID, null, "   ", null, null, null, null, null);

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("请选择文件"));
        verify(docMapper, never()).install(any(KbDocBean.class));
    }

    @Test
    void uploadFile_shouldRejectDuplicateContentInSameKnowledgeBase() {
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(doc("旧制度.txt", KbDocStatus.READY)));

        Result<KbDocBean> result = holder.uploadFile(UID, null, "差旅报销制度",
                null, null, "txt", "制度.txt", null);

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("相同内容"));
        assertTrue(result.getResp_msg().contains("旧制度.txt"));
        verify(docMapper, never()).install(any(KbDocBean.class));
    }

    @Test
    void uploadFile_shouldRejectSameNameEvenWithDifferentContent() {
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.<KbDocBean>emptyList(),
                        Collections.singletonList(doc("制度.txt", KbDocStatus.READY)));

        Result<KbDocBean> result = holder.uploadFile(UID, null, "另一份正文",
                "制度.txt", null, "txt", "制度.txt", null);

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("同名文档"));
        verify(docMapper, never()).install(any(KbDocBean.class));
    }

    @Test
    void uploadFile_shouldRejectWhenTooManyDocumentsAreProcessing() {
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.<KbDocBean>emptyList(),
                        Collections.<KbDocBean>emptyList(),
                        Arrays.asList(doc("a.txt", KbDocStatus.QUEUED),
                                doc("b.txt", KbDocStatus.EMBEDDING),
                                doc("c.txt", KbDocStatus.CHUNKING)));

        Result<KbDocBean> result = holder.uploadFile(UID, null, "新的正文",
                "新文档.txt", null, "txt", "新文档.txt", null);

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("3 篇文档在处理中"));
        verify(docMapper, never()).install(any(KbDocBean.class));
    }

    @Test
    void uploadFile_shouldMarkFailedWhenExecutorRejectsTask() {
        stubFreshUpload();
        doThrow(new RejectedExecutionException("full"))
                .when(kbDocExecutor).execute(any(Runnable.class));
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);

        Result<KbDocBean> result = holder.uploadFile(UID, null, "差旅报销制度",
                null, null, "txt", "制度.txt", null);

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("队列繁忙"));

        ArgumentCaptor<KbDocBean> updateCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(docMapper).update(updateCaptor.capture(), any(KbDocBean.class));
        assertEquals(KbDocStatus.FAILED, updateCaptor.getValue().getStatus(),
                "任务没能排队时必须落 failed，否则文档永远停在 queued");
        assertTrue(updateCaptor.getValue().getErrorMsg().contains("队列繁忙"));
    }

    @Test
    void retry_shouldRequireFailedDocumentAndRequeueWithNextAttempt() {
        KbDocBean failed = doc("制度.txt", KbDocStatus.FAILED);
        failed.setAttemptCount(1);
        failed.setContentHash(HASH);
        when(docMapper.select(isNull(), any(KbDocBean.class))).thenAnswer(invocation -> {
            KbDocBean where = invocation.getArgument(1);
            return where.getIid() == null
                    ? Collections.<KbDocBean>emptyList()
                    : Collections.singletonList(failed);
        });
        KbDocContentBean source = new KbDocContentBean();
        source.setDocIid(DOC_IID);
        source.setContent("差旅报销制度");
        when(contentMapper.select(isNull(), any(KbDocContentBean.class)))
                .thenReturn(Collections.singletonList(source));
        when(docMapper.update(any(KbDocBean.class), any(KbDocBean.class))).thenReturn(1);

        Result<Boolean> result = holder.retry(UID, DOC_IID);

        assertEquals(Boolean.TRUE, result.getDatas());
        ArgumentCaptor<KbDocBean> updateCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        ArgumentCaptor<KbDocBean> whereCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(docMapper).update(updateCaptor.capture(), whereCaptor.capture());
        KbDocBean update = updateCaptor.getValue();
        assertEquals(KbDocStatus.QUEUED, update.getStatus());
        assertEquals(2, update.getAttemptCount().intValue());
        assertEquals(0, update.getProgress().intValue());
        assertEquals(Long.valueOf(0L), update.getFinishedAt());
        assertEquals("", update.getErrorMsg());
        assertEquals(KbDocStatus.FAILED, whereCaptor.getValue().getStatus(),
                "重试必须用 failed 做 CAS，避免覆盖正在执行的任务");
        verify(kbDocExecutor).execute(any(Runnable.class));
    }

    @Test
    void retry_shouldRejectReadyDocument() {
        KbDocBean ready = doc("制度.txt", KbDocStatus.READY);
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(ready));

        Result<Boolean> result = holder.retry(UID, DOC_IID);

        assertEquals(Boolean.FALSE, result.getDatas());
        assertTrue(result.getResp_msg().contains("只有处理失败"));
        verify(docMapper, never()).update(any(KbDocBean.class), any(KbDocBean.class));
    }

    @Test
    void retry_shouldRejectAfterAttemptLimit() {
        KbDocBean failed = doc("制度.txt", KbDocStatus.FAILED);
        failed.setAttemptCount(KbDocPipelineHolder.MAX_RETRY_ATTEMPTS);
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(failed));

        Result<Boolean> result = holder.retry(UID, DOC_IID);

        assertEquals(Boolean.FALSE, result.getDatas());
        assertTrue(result.getResp_msg().contains("已重试 5 次"));
        verify(docMapper, never()).update(any(KbDocBean.class), any(KbDocBean.class));
    }

    @Test
    void retry_shouldRejectWhenOriginalContentIsGone() {
        KbDocBean failed = doc("制度.txt", KbDocStatus.FAILED);
        failed.setAttemptCount(1);
        when(docMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(failed));
        when(contentMapper.select(isNull(), any(KbDocContentBean.class)))
                .thenReturn(Collections.<KbDocContentBean>emptyList());

        Result<Boolean> result = holder.retry(UID, DOC_IID);

        assertEquals(Boolean.FALSE, result.getDatas());
        assertTrue(result.getResp_msg().contains("缺少原始正文"));
        verify(docMapper, never()).update(any(KbDocBean.class), any(KbDocBean.class));
    }

    @Test
    void retry_shouldRejectWhenSameContentAlreadySucceeded() {
        KbDocBean failed = doc("制度.txt", KbDocStatus.FAILED);
        failed.setAttemptCount(1);
        failed.setContentHash(HASH);
        KbDocBean other = doc("备份.txt", KbDocStatus.READY);
        other.setIid(200L);
        when(docMapper.select(isNull(), any(KbDocBean.class))).thenAnswer(invocation -> {
            KbDocBean where = invocation.getArgument(1);
            if (where.getIid() != null) {
                return Collections.singletonList(failed);
            }
            if (where.getContentHash() != null) {
                return Collections.singletonList(other);
            }
            return Collections.<KbDocBean>emptyList();
        });
        KbDocContentBean source = new KbDocContentBean();
        source.setDocIid(DOC_IID);
        source.setContent("差旅报销制度");
        when(contentMapper.select(isNull(), any(KbDocContentBean.class)))
                .thenReturn(Collections.singletonList(source));

        Result<Boolean> result = holder.retry(UID, DOC_IID);

        assertEquals(Boolean.FALSE, result.getDatas());
        assertTrue(result.getResp_msg().contains("无需重试"));
        verify(docMapper, never()).update(any(KbDocBean.class), any(KbDocBean.class));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 上传成功路径的公共桩：去重/同名/并发检查都查不到，插入后能按 docKey 找回 iid */
    private void stubFreshUpload() {
        when(docMapper.select(isNull(), any(KbDocBean.class))).thenAnswer(invocation -> {
            KbDocBean where = invocation.getArgument(1);
            if (where.getDocKey() != null) {
                KbDocBean located = new KbDocBean();
                located.setIid(DOC_IID);
                return Collections.singletonList(located);
            }
            return Collections.<KbDocBean>emptyList();
        });
        when(docMapper.install(any(KbDocBean.class))).thenReturn(1);
        when(contentMapper.install(any(KbDocContentBean.class))).thenReturn(1);
    }

    private static KbDocBean doc(String name, String status) {
        KbDocBean doc = new KbDocBean();
        doc.setIid(DOC_IID);
        doc.setName(name);
        doc.setKbName(KbDocPipelineHolder.DEFAULT_KB_NAME);
        doc.setStatus(status);
        doc.setLastuser(UID);
        doc.setAttemptCount(1);
        return doc;
    }
}
