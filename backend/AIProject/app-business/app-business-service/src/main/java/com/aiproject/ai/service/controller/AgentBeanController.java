package com.aiproject.ai.service.controller;

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
}
