package com.aiproject.ai.service.controller;

import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;

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
import com.aiproject.ai.service.bean.DifyConfigBean;
import com.aiproject.ai.service.holde.DifyConfigBeanHolder;

@RestController
@Slf4j
@RequestMapping("/api/difyConfigBean")
@Api(value = "Dify配置管理", tags = "Dify配置管理")
public class DifyConfigBeanController {

    @Autowired
    DifyConfigBeanHolder difyConfigBeanHolder = null;

    @ApiOperation(value = "查询", httpMethod = "POST", notes = "查询")
    @PostMapping("/query")
    @ControllerLog(value = "查询Dify配置", user = "uid", target = "")
    public Result<List<DifyConfigBean>> query(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody DifyConfigBean data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        return difyConfigBeanHolder.queryList(uid, data);
    }

    @ApiOperation(value = "更新", httpMethod = "POST", notes = "更新")
    @PostMapping("/update")
    @ControllerLog(value = "更新Dify配置", user = "uid", target = "")
    public Result<Boolean> update(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody DifyConfigBean data, @ApiParam(required = true, value = "条件") @RequestBody DifyConfigBean where) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(where, "条件不能空", CodeEnum.NULL.getCode(), null);
        return difyConfigBeanHolder.update(uid, data, where);
    }
}
