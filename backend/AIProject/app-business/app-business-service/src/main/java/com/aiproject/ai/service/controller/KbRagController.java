package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.gateway.dto.KbDocUpload;
import com.aiproject.ai.service.gateway.dto.KbSearchQuery;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.KbDocPipelineHolder;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 本地知识库（RAG）接口：文档入库、文档管理、相似度检索测试。
 * 所有登录用户可用；向量模型配置由管理员在模型配置里维护。
 */
@RestController
@Slf4j
@RequestMapping("/api/kbRag")
@Api(value = "本地知识库RAG", tags = "本地知识库RAG")
public class KbRagController {

    @Autowired
    KbRagHolder kbRagHolder;

    @Autowired
    KbDocPipelineHolder kbDocPipelineHolder;

    @ApiOperation(value = "文档列表", httpMethod = "POST", notes = "不返回正文与向量")
    @PostMapping("/listDocs")
    public Result<List<KbDocBean>> listDocs(@LoginUserId String uid) {
        return kbRagHolder.listDocs(uid);
    }

    /**
     * 旧同步上传入口，仅保留兼容：实际转发到异步流水线。
     *
     * <p>修复前这个入口直接写 kbdocbean/kbchunkbean，不写 contentHash、docKey、
     * 原始正文、状态与向量模型标识，也不做去重与同名校验——任何登录用户都能
     * 绕过新流水线的全部闸门无限重复上传；这些「黑户文档」不可重试，换向量模型后
     * 还会被当成有效候选参与检索，检索质量在无声中下降。</p>
     *
     * <p>产品前端已全部切到 {@link #uploadFile}。这里转发后旧调用方仍然可用，
     * 数据一律由 {@link KbDocPipelineHolder} 按同一套规则处理。</p>
     *
     * <p><b>语义变化</b>：由「同步返回 ready」变为「受理后返回 queued」，
     * 调用方应从 {@code listDocs} 轮询 status / progress。</p>
     */
    @Deprecated
    @ApiOperation(value = "上传文档（旧入口，转发到异步流水线）", httpMethod = "POST",
            notes = "兼容旧调用方；实际走 /uploadFile 同一套解析、去重、状态与重试，返回 queued")
    @PostMapping("/uploadDoc")
    public Result<KbDocBean> uploadDoc(@LoginUserId String uid,
                                       @RequestBody(required = false) KbDocUpload data) {
        if (data == null) {
            return Result.failed(null, "请求参数不能为空");
        }
        // fileType / sizeBytes 留空，由 KbDocTextParser 从文件名推断类型、按正文长度计算体积
        return kbDocPipelineHolder.uploadFile(uid, null, data.getContent(),
                data.getName(), data.getKbName(), null, data.getName(), null);
    }

    @ApiOperation(value = "上传文件（异步流水线）", httpMethod = "POST",
            notes = "txt/md 等文本文件由服务端解析；PDF/DOCX 由浏览器提取正文后以 content 字段提交")
    @PostMapping(value = "/uploadFile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KbDocBean> uploadFile(@LoginUserId String uid,
                                        @RequestParam(value = "file", required = false) MultipartFile file,
                                        @RequestParam(value = "content", required = false) String content,
                                        @RequestParam(value = "name", required = false) String name,
                                        @RequestParam(value = "kbName", required = false) String kbName,
                                        @RequestParam(value = "fileType", required = false) String fileType,
                                        @RequestParam(value = "fileName", required = false) String fileName,
                                        @RequestParam(value = "sizeBytes", required = false) Long sizeBytes) {
        return kbDocPipelineHolder.uploadFile(uid, file, content, name, kbName,
                fileType, fileName, sizeBytes);
    }

    @ApiOperation(value = "重试失败文档", httpMethod = "POST", notes = "仅 failed 状态可重试")
    @PostMapping("/retryDoc")
    public Result<Boolean> retryDoc(@LoginUserId String uid,
                                    @RequestBody KbDocBean data) {
        return kbDocPipelineHolder.retry(uid, data == null ? null : data.getIid());
    }

    @ApiOperation(value = "删除文档", httpMethod = "POST", notes = "连同分块一起删除")
    @PostMapping("/deleteDoc")
    public Result<Boolean> deleteDoc(@LoginUserId String uid,
                                     @RequestBody KbDocBean data) {
        return kbRagHolder.deleteDoc(uid, data);
    }

    @ApiOperation(value = "相似度检索", httpMethod = "POST", notes = "返回 topK 命中分块与分数")
    @PostMapping("/search")
    public Result<KbSearchResult> search(@LoginUserId String uid,
                                         @RequestBody KbSearchQuery data) {
        return kbRagHolder.search(uid, data.getQuery(), data.getKbName(), data.getTopK(),
                data.getRerankEnabled());
    }
}
