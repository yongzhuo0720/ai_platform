package com.aiproject.ai.service.controller;

import blxt.qjava.utils.check.CheckUtils;
import com.aiproject.ai.service.bean.AssistantBean;
import com.aiproject.ai.service.holde.AssistantBeanHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.ControllerLog;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/api/assistantBean")
@Api(value = "助手", tags = "助手")
public class AssistantBeanController {

    @Autowired
    AssistantBeanHolder assistantBeanHolder = null;

    @ApiOperation(value = "创建", httpMethod = "POST", notes = "创建助手")
    @PostMapping("/create")
    @ControllerLog(value = "创建助手", user = "uid", target = "")
    public Result<AssistantBean> create(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody AssistantBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        if (data == null) {
            return Result.failed(null, "数据不能为空");
        }
        data.setLastuser(uid);
        data.setUserId(uid);
        return assistantBeanHolder.install(data);
    }

    @ApiOperation(value = "删除", httpMethod = "POST", notes = "删除助手")
    @PostMapping("/delete")
    @ControllerLog(value = "删除助手", user = "uid", target = "")
    public Result<Boolean> delete(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody AssistantBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        if (data == null || data.getIid() == null) {
            return Result.failed(false, "数据不能为空");
        }
        return assistantBeanHolder.delete(uid, data);
    }

    @ApiOperation(value = "更新", httpMethod = "POST", notes = "更新助手")
    @PostMapping("/update")
    @ControllerLog(value = "更新助手", user = "uid", target = "")
    public Result<Boolean> update(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody AssistantBean data, @ApiParam(required = true, value = "条件") @RequestBody AssistantBean where) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        if (data == null) {
            return Result.failed(false, "数据不能为空");
        }
        if (where == null || where.getIid() == null) {
            return Result.failed(false, "条件不能为空");
        }
        return assistantBeanHolder.update(uid, data, where);
    }

    @ApiOperation(value = "查询", httpMethod = "POST", notes = "查询当前用户的助手列表")
    @PostMapping("/query")
    @ControllerLog(value = "查询助手", user = "uid", target = "")
    public Result<List<AssistantBean>> query(@LoginUserId String uid) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        return assistantBeanHolder.queryList(uid);
    }
}
