package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.agent.AgentRunner;
import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.agent.dto.AgentRunRequest;
import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.holde.AgentConfigHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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

    @ApiOperation(value = "运行 Agent", httpMethod = "POST", notes = "同步执行工具调用循环，返回运行详情")
    @PostMapping("/run")
    public Result<AgentRunDetail> run(@LoginUserId String uid,
                                      @RequestBody(required = false) AgentRunRequest data) {
        return agentRunner.run(uid, data == null ? null : data.getAgentKey(),
                data == null ? null : data.getQuestion());
    }

    @ApiOperation(value = "流式运行 Agent", httpMethod = "POST")
    @PostMapping(value = "/streamRun", produces = "text/event-stream")
    public void streamRun(@LoginUserId String uid,
                          @RequestBody(required = false) AgentRunRequest data,
                          HttpServletResponse response) {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseListener listener = new SseListener(response);
        try {
            Result<AgentRunDetail> result = agentRunner.run(uid,
                    data == null ? null : data.getAgentKey(),
                    data == null ? null : data.getQuestion(), listener);
            if (result == null || !CodeEnum.SUCCESS.getCode().equals(result.getResp_code())) {
                listener.error(listener.runKey == null && result != null
                        ? result.getResp_msg() : "读取运行详情失败，请稍后重试");
                return;
            }
            AgentRunDetail detail = result.getDatas();
            if (detail == null || detail.getRun() == null || listener.runKey == null
                    || !listener.runKey.equals(detail.getRun().getRunKey())
                    || !("succeeded".equals(detail.getRun().getStatus())
                    || "failed".equals(detail.getRun().getStatus()))) {
                listener.error("读取运行详情失败，请稍后重试");
                return;
            }
            listener.send("agent_done", detail);
        } catch (DuplicateKeyException e) {
            listener.error("运行记录发生并发冲突，请重试");
        } catch (DataAccessException e) {
            listener.error("运行记录读写失败，请稍后重试");
        } catch (Exception e) {
            listener.error("Agent 执行失败，请稍后重试");
        }
    }

    private static class SseListener implements AgentRunner.Listener {
        private final HttpServletResponse response;
        private final ObjectMapper mapper = new ObjectMapper();
        private boolean disconnected;
        private String runKey;

        private SseListener(HttpServletResponse response) {
            this.response = response;
        }

        @Override
        public void onStart(String runKey) {
            this.runKey = runKey;
            send("agent_start", Collections.singletonMap("runKey", runKey));
        }

        @Override
        public void onStep(AgentStepBean step) {
            send("agent_step", step);
        }

        @Override
        public boolean isDisconnected() {
            return disconnected;
        }

        private void error(String message) {
            send("agent_error", Collections.singletonMap("message", message));
        }

        private void send(String event, Object data) {
            if (disconnected) {
                return;
            }
            String json;
            try {
                json = mapper.writeValueAsString(data);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("流式事件序列化失败", e);
            }
            try {
                response.getOutputStream().write(("event: " + event + "\ndata: " + json + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
            } catch (IOException e) {
                disconnected = true;
            }
        }
    }
}
