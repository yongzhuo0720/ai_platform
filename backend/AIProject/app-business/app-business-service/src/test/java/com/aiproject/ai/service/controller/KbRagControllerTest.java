package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.gateway.dto.KbDocUpload;
import com.aiproject.ai.service.holde.KbDocPipelineHolder;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧同步上传入口的收敛测试。
 *
 * <p>修复前 /uploadDoc 直接调用 KbRagHolder.uploadDoc，绕过新流水线的
 * 去重、原始正文、状态与向量模型标识，产生的文档既不可重试也无法参与
 * 内容去重。这里锁死「只转发、不自己写库」，防止后续被改回旧实现。</p>
 */
class KbRagControllerTest {

    private static final String UID = "u1";

    private KbRagController controllerWith(KbRagHolder legacy, KbDocPipelineHolder pipeline) {
        KbRagController controller = new KbRagController();
        controller.kbRagHolder = legacy;
        controller.kbDocPipelineHolder = pipeline;
        return controller;
    }

    @Test
    void uploadDoc_shouldDelegateToAsyncPipelineInsteadOfLegacyWriter() {
        KbRagHolder legacy = mock(KbRagHolder.class);
        KbDocPipelineHolder pipeline = mock(KbDocPipelineHolder.class);
        KbRagController controller = controllerWith(legacy, pipeline);

        KbDocUpload data = new KbDocUpload();
        data.setName("制度.txt");
        data.setKbName("默认知识库");
        data.setContent("差旅报销制度");

        KbDocBean queued = new KbDocBean();
        queued.setStatus("queued");
        when(pipeline.uploadFile(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Result.succeed(queued, "文档已受理，正在后台处理"));

        Result<KbDocBean> result = controller.uploadDoc(UID, data);

        assertNotNull(result.getDatas());
        assertEquals("queued", result.getDatas().getStatus());
        // 类型与体积留空，由 KbDocTextParser 从文件名/正文推断
        verify(pipeline).uploadFile(eq(UID), isNull(), eq("差旅报销制度"), eq("制度.txt"),
                eq("默认知识库"), isNull(), eq("制度.txt"), isNull());
        verify(legacy, never()).uploadDoc(any(), any());
    }

    @Test
    void uploadDoc_shouldRejectNullBodyWithoutTouchingAnyHolder() {
        KbRagHolder legacy = mock(KbRagHolder.class);
        KbDocPipelineHolder pipeline = mock(KbDocPipelineHolder.class);
        KbRagController controller = controllerWith(legacy, pipeline);

        Result<KbDocBean> result = controller.uploadDoc(UID, null);

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("参数"));
        verify(pipeline, never()).uploadFile(any(), any(), any(), any(), any(), any(), any(), any());
        verify(legacy, never()).uploadDoc(any(), any());
    }
}
