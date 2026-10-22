package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.gateway.dto.KbDocUpload;
import com.aiproject.ai.service.gateway.dto.KbSearchQuery;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
