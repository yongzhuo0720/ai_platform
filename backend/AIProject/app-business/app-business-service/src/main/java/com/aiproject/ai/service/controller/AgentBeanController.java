package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.agent.AgentRunner;
import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.agent.dto.AgentRunRequest;
import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.holde.AgentConfigHolder;
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
 * Agent 配置接口。
 *
 * <p>Controller 只做转交与空体保护；归属校验、参数校验一律在
 * {@link AgentConfigHolder} 内完成，避免出现「某个入口忘了校验」。</p>
 */
@RestController
@Slf4j
@RequestMapping("/api/agentBean")
@Api(value = "Agent配置", tags = "Agent工作台")
public class AgentBeanController {

    @Autowired
    AgentConfigHolder agentConfigHolder;

    @Autowired
    AgentRunner agentRunner;

    @ApiOperation(value = "配置列表", httpMethod = "POST")
    @PostMapping("/list")
    public Result<List<AgentBean>> list(@LoginUserId String uid) {
        return agentConfigHolder.list(uid);
    }

    @ApiOperation(value = "新建配置", httpMethod = "POST", notes = "返回 agentKey")
    @PostMapping("/create")
    public Result<AgentBean> create(@LoginUserId String uid,
                                    @RequestBody(required = false) AgentBean data) {
        return agentConfigHolder.create(uid, data);
    }

    @ApiOperation(value = "修改配置", httpMethod = "POST", notes = "请求体需带 iid")
    @PostMapping("/update")
    public Result<Boolean> update(@LoginUserId String uid,
                                  @RequestBody(required = false) AgentBean data) {
        return agentConfigHolder.update(uid, data);
    }

    @ApiOperation(value = "删除配置", httpMethod = "POST", notes = "请求体需带 iid")
    @PostMapping("/delete")
    public Result<Boolean> delete(@LoginUserId String uid,
                                  @RequestBody(required = false) AgentBean data) {
        return agentConfigHolder.delete(uid, data == null ? null : data.getIid());
    }

    /**
     * 同步执行一次 Agent 运行，返回运行详情（汇总 + 步骤明细）。
     *
     * <p>本子阶段用同步返回而不是 SSE：先把 tool_calls 循环、终止条件与追踪落库
     * 做对并单测覆盖；流式步骤事件是下一步的增强，不改变这里的编排语义。</p>
     */
    @ApiOperation(value = "运行 Agent", httpMethod = "POST", notes = "同步执行工具调用循环，返回运行详情")
    @PostMapping("/run")
    public Result<AgentRunDetail> run(@LoginUserId String uid,
                                      @RequestBody(required = false) AgentRunRequest data) {
        return agentRunner.run(uid, data == null ? null : data.getAgentKey(),
                data == null ? null : data.getQuestion());
    }
}
