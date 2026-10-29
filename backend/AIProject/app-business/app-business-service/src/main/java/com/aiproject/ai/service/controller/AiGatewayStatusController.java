package com.aiproject.ai.service.controller;

import blxt.qjava.utils.check.CheckUtils;
import com.aiproject.ai.service.gateway.dto.AiGatewayConfigStatus;
import com.aiproject.ai.service.holde.AiGatewayAdminHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 网关模块状态：供业务页面判断某模块是否已配置。
 * <p>
 * 只返回各模块是否可用，不返回地址与密钥；
 * 页面据此直接展示配置引导，避免反复请求网关产生失败审计。
 */
@RestController
@Slf4j
@RequestMapping("/api/aiGatewayStatus")
@Api(value = "AI网关状态", tags = "AI网关状态")
public class AiGatewayStatusController {

    @Autowired
    AiGatewayAdminHolder adminHolder;

    @ApiOperation(value = "模块配置状态", httpMethod = "POST", notes = "只返回各模块是否已配置，不返回密钥")
    @PostMapping("/config")
    public Result<AiGatewayConfigStatus> config(@LoginUserId String uid) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        return adminHolder.moduleStatus();
    }
}
