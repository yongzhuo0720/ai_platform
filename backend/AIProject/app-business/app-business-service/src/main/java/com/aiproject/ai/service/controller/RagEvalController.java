package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.RagEvalDatasetBean;
import com.aiproject.ai.service.bean.RagEvalRunBean;
import com.aiproject.ai.service.holde.RagEvalHolder;
import com.aiproject.ai.service.rag.dto.RagEvalDatasetDetail;
import com.aiproject.ai.service.rag.dto.RagEvalDatasetRequest;
import com.aiproject.ai.service.rag.dto.RagEvalKeyRequest;
import com.aiproject.ai.service.rag.dto.RagEvalRunDetail;
import com.aiproject.ai.service.rag.dto.RagEvalRunRequest;
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
 * RAG 检索评估工作台。
 *
 * <p>所有接口只接受当前登录用户的操作，归属校验在 {@link RagEvalHolder} 内完成；
 * Controller 不承担权限判断，避免出现「某个入口忘了校验」。</p>
 */
@RestController
@Slf4j
@RequestMapping("/api/ragEval")
@Api(value = "RAG检索评估", tags = "RAG检索评估")
public class RagEvalController {

    @Autowired
    RagEvalHolder ragEvalHolder;

    @ApiOperation(value = "数据集列表", httpMethod = "POST")
    @PostMapping("/listDatasets")
    public Result<List<RagEvalDatasetBean>> listDatasets(@LoginUserId String uid) {
        return ragEvalHolder.listDatasets(uid);
    }

    @ApiOperation(value = "数据集详情", httpMethod = "POST", notes = "含当前生效的用例集")
    @PostMapping("/getDataset")
    public Result<RagEvalDatasetDetail> getDataset(@LoginUserId String uid,
                                                   @RequestBody RagEvalKeyRequest data) {
        return ragEvalHolder.getDataset(uid, data == null ? null : data.getDatasetKey());
    }

    @ApiOperation(value = "新建数据集", httpMethod = "POST", notes = "返回 datasetKey")
    @PostMapping("/createDataset")
    public Result<String> createDataset(@LoginUserId String uid,
                                        @RequestBody RagEvalDatasetRequest data) {
        return ragEvalHolder.createDataset(uid, data);
    }

    @ApiOperation(value = "编辑数据集", httpMethod = "POST", notes = "整体替换当前用例集")
    @PostMapping("/updateDataset")
    public Result<Boolean> updateDataset(@LoginUserId String uid,
                                         @RequestBody RagEvalDatasetRequest data) {
        return ragEvalHolder.updateDataset(uid, data);
    }

    @ApiOperation(value = "删除数据集", httpMethod = "POST", notes = "连同用例与运行历史")
    @PostMapping("/deleteDataset")
    public Result<Boolean> deleteDataset(@LoginUserId String uid,
                                         @RequestBody RagEvalKeyRequest data) {
        return ragEvalHolder.deleteDataset(uid, data == null ? null : data.getDatasetKey());
    }

    @ApiOperation(value = "启动评估", httpMethod = "POST", notes = "异步执行，返回 runKey")
    @PostMapping("/startRun")
    public Result<String> startRun(@LoginUserId String uid,
                                   @RequestBody RagEvalRunRequest data) {
        return ragEvalHolder.startRun(uid, data);
    }

    @ApiOperation(value = "运行历史", httpMethod = "POST", notes = "可按 datasetKey 过滤")
    @PostMapping("/listRuns")
    public Result<List<RagEvalRunBean>> listRuns(@LoginUserId String uid,
                                                 @RequestBody RagEvalKeyRequest data) {
        return ragEvalHolder.listRuns(uid, data == null ? null : data.getDatasetKey());
    }

    @ApiOperation(value = "运行详情", httpMethod = "POST", notes = "整体指标 + 逐题结果 + 对比基线")
    @PostMapping("/getRun")
    public Result<RagEvalRunDetail> getRun(@LoginUserId String uid,
                                           @RequestBody RagEvalKeyRequest data) {
        return ragEvalHolder.getRun(uid, data == null ? null : data.getRunKey());
    }

    @ApiOperation(value = "删除运行记录", httpMethod = "POST")
    @PostMapping("/deleteRun")
    public Result<Boolean> deleteRun(@LoginUserId String uid,
                                     @RequestBody RagEvalKeyRequest data) {
        return ragEvalHolder.deleteRun(uid, data == null ? null : data.getRunKey());
    }
}
