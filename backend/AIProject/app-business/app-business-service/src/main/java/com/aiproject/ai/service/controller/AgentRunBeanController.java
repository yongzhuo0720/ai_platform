package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.agent.dto.AgentResumeRequest;
import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.bean.AgentRunBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.gateway.AiGatewayAsyncDispatcher;
import com.aiproject.ai.service.gateway.AsyncResponseGate;
import com.aiproject.ai.service.holde.AgentConfigHolder;
import com.aiproject.ai.service.holde.AgentRunHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行记录（只读）。
 *
 * <p>{@code /streamResume} 是断线续播入口：只读数据库里已经落库的步骤，
 * 不重新执行模型、不产生任何新的模型调用与计费。</p>
 */
@RestController
@Slf4j
@RequestMapping("/api/agentRunBean")
@Api(value = "Agent运行", tags = "Agent工作台")
public class AgentRunBeanController {

    /** 单次续播连接最长驻留：30 次 × 1 秒，超时静默结束让前端继续重连 */
    private static final int MAX_POLLS = 30;
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long MAX_EVENT_ID = 100000L;

    @Autowired
    AgentRunHolder agentRunHolder;

    /** 可选注入：为 null 时同步回退，既有单测零改动；生产环境复用网关中继线程池 */
    @Autowired(required = false)
    AiGatewayAsyncDispatcher asyncDispatcher;

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

    /**
     * 断线续播：按 Last-Event-ID 补发缺失步骤，运行未结束时短轮询等待，
     * 到达终态后发 agent_done。全程不触发 AgentRunner，因此不会重复执行或计费。
     * 短轮询最多占 30 秒，交给中继线程池后不再占住 Tomcat 请求线程。
     */
    @ApiOperation(value = "事件续播", httpMethod = "POST", notes = "只读已落库步骤，支持 Last-Event-ID 头")
    @PostMapping(value = "/streamResume", produces = "text/event-stream")
    public WebAsyncTask<Void> streamResume(@LoginUserId String uid,
                                           @RequestBody(required = false) AgentResumeRequest data,
                                           @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        if (asyncDispatcher == null) {
            streamResumeSync(uid, data, lastEventIdHeader, response, AsyncResponseGate.NONE);
            return null;
        }
        return asyncDispatcher.dispatch("agent/streamResume", request,
                AgentBeanController.AGENT_ASYNC_TIMEOUT_MS,
                gate -> streamResumeSync(uid, data, lastEventIdHeader, response, gate));
    }

    /** 包内可见的同步重载：既有单元测试直接调用，保持四参数契约不变 */
    void streamResume(String uid, AgentResumeRequest data, String lastEventIdHeader,
                      HttpServletResponse response) {
        streamResumeSync(uid, data, lastEventIdHeader, response, AsyncResponseGate.NONE);
    }

    private void streamResumeSync(String uid, AgentResumeRequest data, String lastEventIdHeader,
                                  HttpServletResponse response, AsyncResponseGate gate) {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        AgentBeanController.SseListener listener =
                new AgentBeanController.SseListener(response, gate);

        String runKey = data == null ? null : data.getRunKey();
        if (AgentConfigHolder.blank(uid) || AgentConfigHolder.blank(runKey)) {
            listener.error("缺少运行标识，无法续播");
            return;
        }
        long lastEventId = parseLastEventId(data.getLastEventId(), lastEventIdHeader);
        if (lastEventId < 0 || lastEventId > MAX_EVENT_ID) {
            listener.error("事件序号不合法，无法续播");
            return;
        }

        try {
            for (int poll = 0; poll < MAX_POLLS && !listener.isDisconnected(); poll++) {
                Result<AgentRunDetail> result = agentRunHolder.detail(uid, runKey.trim());
                if (result == null || !CodeEnum.SUCCESS.getCode().equals(result.getResp_code())
                        || result.getDatas() == null) {
                    listener.error("运行记录不存在或不属于当前用户");
                    return;
                }
                AgentRunDetail detail = result.getDatas();
                List<AgentStepBean> steps = detail.getSteps() == null
                        ? new ArrayList<AgentStepBean>() : detail.getSteps();
                for (AgentStepBean step : steps) {
                    long id = step.getStepNo() == null ? 0L : step.getStepNo().longValue();
                    if (id > lastEventId) {
                        listener.onStep(step);
                        lastEventId = id;
                    }
                }
                String status = detail.getRun() == null ? null : detail.getRun().getStatus();
                if ("succeeded".equals(status) || "failed".equals(status)) {
                    listener.send("agent_done", detail);
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            // 仍在运行：不发 agent_error，静默结束让前端带着新的 lastEventId 再次续播
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[Agent] 事件续播失败, uid={}, runKey={}: {}", uid, runKey, e.getMessage());
            listener.error("续播失败，请稍后重试");
        }
    }

    private static long parseLastEventId(Long bodyValue, String headerValue) {
        if (bodyValue != null) {
            return bodyValue;
        }
        if (headerValue == null || headerValue.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(headerValue.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
