package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.aiproject.ai.service.gateway.dto.AiGatewayConfigStatus;
import com.aiproject.ai.service.gateway.dto.AiGatewayLogQuery;
import com.aiproject.ai.service.gateway.dto.AiGatewaySummary;
import com.aiproject.ai.service.gateway.dto.AiGatewayUserOption;
import com.aiproject.ai.service.gateway.dto.AiQuotaOverview;
import com.aiproject.ai.service.holde.AiGatewayAdminHolder;
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

import java.util.List;

/**
 * AI 网关后台接口：用量看板、审计日志、配额管理。
 * <p>
 * 所有接口仅超级管理员可访问（rid=99999）。
 */
@RestController
@Slf4j
@RequestMapping("/api/aiGatewayAdmin")
@Api(value = "AI网关后台", tags = "AI网关后台")
public class AiGatewayAdminController {

    @Autowired
    AiGatewayAdminHolder adminHolder;

    @ApiOperation(value = "网关配置状态", httpMethod = "POST", notes = "不返回任何密钥")
    @PostMapping("/config")
    public Result<AiGatewayConfigStatus> config(@LoginUserId String uid) {
        return adminHolder.configStatus(uid);
    }

    @ApiOperation(value = "用量汇总", httpMethod = "POST", notes = "卡片 + 趋势 + 模块 + 用户排行")
    @PostMapping("/summary")
    public Result<AiGatewaySummary> summary(@LoginUserId String uid,
                                            @RequestBody(required = false) AiGatewayLogQuery query) {
        return adminHolder.summary(uid, query);
    }

    @ApiOperation(value = "审计日志分页", httpMethod = "POST", notes = "审计日志分页")
    @PostMapping("/logs")
    public Result<PageResult<AiCallLogBean>> logs(@LoginUserId String uid,
                                                  @RequestBody(required = false) AiGatewayLogQuery query) {
        return adminHolder.logs(uid, query);
    }

    @ApiOperation(value = "配额列表", httpMethod = "POST", notes = "用户配额覆盖 + 今日用量")
    @PostMapping("/quotas")
    public Result<AiQuotaOverview> quotas(@LoginUserId String uid) {
        return adminHolder.quotas(uid);
    }

    @ApiOperation(value = "保存配额", httpMethod = "POST", notes = "按用户 upsert")
    @PostMapping("/saveQuota")
    public Result<Boolean> saveQuota(@LoginUserId String uid,
                                     @RequestBody AiUserQuotaBean data) {
        return adminHolder.saveQuota(uid, data);
    }

    @ApiOperation(value = "删除配额", httpMethod = "POST", notes = "删除用户覆盖，恢复默认配额")
    @PostMapping("/deleteQuota")
    public Result<Boolean> deleteQuota(@LoginUserId String uid,
                                       @RequestBody AiUserQuotaBean data) {
        return adminHolder.deleteQuota(uid, data);
    }

    @ApiOperation(value = "用户下拉", httpMethod = "POST", notes = "配额管理选择用户")
    @PostMapping("/users")
    public Result<List<AiGatewayUserOption>> users(@LoginUserId String uid) {
        return adminHolder.users(uid);
    }
}
