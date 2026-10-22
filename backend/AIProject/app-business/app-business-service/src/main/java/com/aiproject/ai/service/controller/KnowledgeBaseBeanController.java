package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.dto.KnowledgeBaseFilterRequest;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;

import java.lang.String;
import java.util.List;

import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.RequestBody;
import lombok.extern.slf4j.Slf4j;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import com.heneng.hnsp.common.inter.ControllerLog;
import blxt.qjava.utils.check.CheckUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import io.swagger.annotations.Api;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.holde.KnowledgeBaseBeanHolder;

@RestController
@Slf4j
@RequestMapping("/api/knowledgeBaseBean")
@Api(value = "知识库权限", tags = "知识库权限")
public class KnowledgeBaseBeanController {

    @Autowired
    KnowledgeBaseBeanHolder knowledgeBaseBeanHolder = null;

    @ApiOperation(value = "创建", httpMethod = "POST", notes = "创建")
    @PostMapping("/create")
    @ControllerLog(value = "创建知识库权限", user = "uid", target = "")
    public Result<Boolean> create(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody KnowledgeBaseBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        data.setLastuser(uid);
        return knowledgeBaseBeanHolder.install(uid, data);
    }

    @ApiOperation(value = "删除", httpMethod = "POST", notes = "删除")
    @PostMapping("/delete")
    @ControllerLog(value = "删除知识库权限", user = "uid", target = "")
    public Result<Boolean> delete(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody KnowledgeBaseBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return knowledgeBaseBeanHolder.delete(uid, data);
    }

    @ApiOperation(value = "更新", httpMethod = "POST", notes = "更新")
    @PostMapping("/update")
    @ControllerLog(value = "更新知识库权限", user = "uid", target = "")
    public Result<Boolean> update(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody KnowledgeBaseBean data, @ApiParam(required = true, value = "条件") @RequestBody KnowledgeBaseBean where) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(where, "条件不能空", CodeEnum.NULL.getCode(), null);
        return knowledgeBaseBeanHolder.update(uid, data, where);
    }

    @ApiOperation(value = "查询", httpMethod = "POST", notes = "查询")
    @PostMapping("/query")
    @ControllerLog(value = "查询知识库权限", user = "uid", target = "")
    public Result<List<KnowledgeBaseBean>> query(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody KnowledgeBaseBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return knowledgeBaseBeanHolder.queryList(uid, data);
    }

    @ApiOperation(value = "过滤可见知识库", httpMethod = "POST", notes = "根据context过滤可见的知识库ID列表")
    @PostMapping("/filterVisible")
    @ControllerLog(value = "过滤可见知识库", user = "uid", target = "")
    @SuppressWarnings("unchecked")
    public Result<List<String>> filterVisible(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody KnowledgeBaseFilterRequest data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data.getContext(), "context不能空", CodeEnum.NULL.getCode(), null);
        return knowledgeBaseBeanHolder.filterVisible(uid, data.getContext(), data.getKbIds());
    }
}
