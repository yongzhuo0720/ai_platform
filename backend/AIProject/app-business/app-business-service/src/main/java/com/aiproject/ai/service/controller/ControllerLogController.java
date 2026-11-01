package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.UserControllerLogBean;
import com.aiproject.ai.service.dto.ControllerLogQuery;
import com.aiproject.ai.service.holde.ControllerLogHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.inter.LoginUserId;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台操作日志接口（仅超管）。
 *
 * <p>请求体按框架的 MultiRequestBody 约定包一层：{@code {"query": {...}}}，
 * 参数名 {@code query} 必须和方法形参名一致。</p>
 */
@RestController
@Slf4j
@RequestMapping("/api/controllerLog")
@Api(value = "操作日志", tags = "操作日志")
public class ControllerLogController {

    @Autowired
    ControllerLogHolder controllerLogHolder;

    @ApiOperation(value = "操作日志分页", httpMethod = "POST", notes = "仅超管可查全站操作记录")
    @PostMapping("/query")
    public Result<PageResult<UserControllerLogBean>> query(
            @LoginUserId String uid,
            @RequestBody(required = false) ControllerLogQuery query) {
        return controllerLogHolder.query(uid, query);
    }
}
