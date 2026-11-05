package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.AgentToolBean;
import com.aiproject.ai.service.holde.AgentToolHolder;
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

/** Agent 工具注册接口：Schema 必填、工具类型白名单、Agent 归属校验都在 Holder 内。 */
@RestController
@Slf4j
@RequestMapping("/api/agentToolBean")
@Api(value = "Agent工具", tags = "Agent工作台")
public class AgentToolBeanController {

    @Autowired
    AgentToolHolder agentToolHolder;

    @ApiOperation(value = "工具列表", httpMethod = "POST", notes = "请求体需带 agentIid")
    @PostMapping("/list")
    public Result<List<AgentToolBean>> list(@LoginUserId String uid,
                                            @RequestBody(required = false) AgentToolBean data) {
        return agentToolHolder.list(uid, data == null ? null : data.getAgentIid());
    }

    @ApiOperation(value = "保存工具", httpMethod = "POST", notes = "带 iid 为修改，不带为新建")
    @PostMapping("/save")
    public Result<AgentToolBean> save(@LoginUserId String uid,
                                      @RequestBody(required = false) AgentToolBean data) {
        return agentToolHolder.save(uid, data);
    }

    @ApiOperation(value = "删除工具", httpMethod = "POST", notes = "请求体需带 iid")
    @PostMapping("/delete")
    public Result<Boolean> delete(@LoginUserId String uid,
                                  @RequestBody(required = false) AgentToolBean data) {
        return agentToolHolder.delete(uid, data == null ? null : data.getIid());
    }
}
