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

    @ApiOperation(value = "上传文档", httpMethod = "POST", notes = "分块 + 向量化后入库")
    @PostMapping("/uploadDoc")
    public Result<KbDocBean> uploadDoc(@LoginUserId String uid,
                                       @RequestBody KbDocUpload data) {
        return kbRagHolder.uploadDoc(uid, data);
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
        return kbRagHolder.search(uid, data.getQuery(), data.getKbName(), data.getTopK());
    }
}
