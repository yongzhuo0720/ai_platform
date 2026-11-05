package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.bean.AgentRunBean;
import com.aiproject.ai.service.holde.AgentRunHolder;
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
 * Agent 运行记录（只读）。
 *
 * <p>本子阶段没有写入接口：运行与步骤由后续执行循环落库。
 * 先把「读」的归属隔离做对，避免先出现越权查询入口。</p>
 */
@RestController
@Slf4j
@RequestMapping("/api/agentRunBean")
@Api(value = "Agent运行", tags = "Agent工作台")
public class AgentRunBeanController {

    @Autowired
    AgentRunHolder agentRunHolder;

    @ApiOperation(value = "运行列表", httpMethod = "POST", notes = "agentIid 可选")
    @PostMapping("/list")
    public Result<List<AgentRunBean>> list(@LoginUserId String uid,
                                           @RequestBody(required = false) AgentRunBean data) {
        return agentRunHolder.list(uid, data == null ? null : data.getAgentIid());
    }

    @ApiOperation(value = "运行详情", httpMethod = "POST", notes = "返回汇总 + 步骤明细")
    @PostMapping("/detail")
    public Result<AgentRunDetail> detail(@LoginUserId String uid,
                                         @RequestBody(required = false) AgentRunBean data) {
        return agentRunHolder.detail(uid, data == null ? null : data.getRunKey());
    }
}
