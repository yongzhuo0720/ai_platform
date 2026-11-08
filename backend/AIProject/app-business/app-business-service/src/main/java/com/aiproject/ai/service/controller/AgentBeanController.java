package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.agent.AgentRunner;
import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.agent.dto.AgentRunRequest;
import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.gateway.AiGatewayAsyncDispatcher;
import com.aiproject.ai.service.gateway.AsyncResponseGate;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

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

    /**
     * 可选注入：未接入异步分发器（或测试直接 new Controller）时走同步回退，
     * 行为与改造前完全一致。生产环境由 Spring 注入网关现有的
     * {@code aiGatewayExecutor}，让 Agent 的多轮模型调用不再占住 Tomcat 请求线程。
     */
    @Autowired(required = false)
    AiGatewayAsyncDispatcher asyncDispatcher;

    /** 同步运行接口复用同一个有界中继线程池，避免每次调用新建线程 */
    @Autowired(required = false)
    @Qualifier("aiGatewayExecutor")
    AsyncTaskExecutor agentExecutor;

    /** 同步接口容器级超时：Agent 总预算 120 秒 + 工具超时/收尾余量 */
    private static final long RUN_ASYNC_TIMEOUT_MS = 300_000L;

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
     * 同步运行入口（外部脚本 / 非 SSE 场景）。返回类型仍是「一次 HTTP 请求 = 一个结果」，
     * 但整段执行循环交给有界中继线程池，不再占住 Tomcat 请求线程。
     */
    @ApiOperation(value = "运行 Agent", httpMethod = "POST", notes = "同步返回运行详情，服务端异步执行")
    @PostMapping("/run")
    public WebAsyncTask<Result<AgentRunDetail>> runAsync(@LoginUserId String uid,
                                                         @RequestBody(required = false) AgentRunRequest data) {
        Callable<Result<AgentRunDetail>> task = () -> run(uid, data);
        if (agentExecutor == null) {
            // 未注入线程池（测试直接 new Controller）：由 Spring MVC 默认执行器兜底
            return new WebAsyncTask<>(RUN_ASYNC_TIMEOUT_MS, task);
        }
        return new WebAsyncTask<>(RUN_ASYNC_TIMEOUT_MS, agentExecutor, task);
    }

    /** 包内可见的同步实现：既有单元测试直接调用，保持三参数契约不变 */
    Result<AgentRunDetail> run(@LoginUserId String uid,
                               @RequestBody(required = false) AgentRunRequest data) {
        // 与 /streamRun 保持同一套异常分级：唯一索引冲突要给人能懂的提示，
        // 数据库异常不能把原始 SQL / 堆栈通过 resp_msg 带回浏览器。
        try {
            return agentRunner.run(uid, data == null ? null : data.getAgentKey(),
                    data == null ? null : data.getQuestion());
        } catch (DuplicateKeyException e) {
            return Result.failed(null, "运行记录发生并发冲突，请重试");
        } catch (DataAccessException e) {
            return Result.failed(null, "运行记录读写失败，请稍后重试");
        } catch (Exception e) {
            log.error("[Agent] 同步运行失败, uid={}", uid, e);
            return Result.failed(null, "Agent 执行失败，请稍后重试");
        }
    }

    /**
     * 流式入口：请求线程只负责提交，整段执行循环交给中继线程池。
     * 返回 {@code null} 表示已在当前线程同步处理完（分发器不可用时的回退）。
     */
    @ApiOperation(value = "流式运行 Agent", httpMethod = "POST")
    @PostMapping(value = "/streamRun", produces = "text/event-stream")
    public WebAsyncTask<Void> streamRun(@LoginUserId String uid,
                                        @RequestBody(required = false) AgentRunRequest data,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        if (asyncDispatcher == null) {
            streamRunSync(uid, data, response, AsyncResponseGate.NONE);
            return null;
        }
        return asyncDispatcher.dispatch("agent/streamRun", request,
                gate -> streamRunSync(uid, data, response, gate));
    }

    /** 包内可见的同步重载：既有单元测试直接调用，保持三参数契约不变 */
    void streamRun(String uid, AgentRunRequest data, HttpServletResponse response) {
        streamRunSync(uid, data, response, AsyncResponseGate.NONE);
    }

    private void streamRunSync(String uid, AgentRunRequest data,
                               HttpServletResponse response, AsyncResponseGate gate) {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseListener listener = new SseListener(response, gate);
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

    /**
     * SSE 帧写入器。包内复用于 {@code /streamRun} 与 {@code /streamResume}，
     * 保证实时流与续播流的帧格式完全一致（含 {@code id:} 事件序号）。
     */
    static final class SseListener implements AgentRunner.Listener {
        private final HttpServletResponse response;
        private final AsyncResponseGate gate;
        private final ObjectMapper mapper = new ObjectMapper();
        private boolean disconnected;
        private String runKey;

        /** 续播接口等同步入口：没有容器级取消，门闸恒为放行 */
        SseListener(HttpServletResponse response) {
            this(response, AsyncResponseGate.NONE);
        }

        /** 异步执行入口：容器回收响应前会先 cancel 门闸，之后所有写入都被拒绝 */
        SseListener(HttpServletResponse response, AsyncResponseGate gate) {
            this.response = response;
            this.gate = gate == null ? AsyncResponseGate.NONE : gate;
        }

        @Override
        public void onStart(String runKey) {
            this.runKey = runKey;
            send("agent_start", Collections.singletonMap("runKey", runKey), 0L);
        }

        @Override
        public void onStep(AgentStepBean step) {
            long id = step.getStepNo() == null ? 0L : step.getStepNo().longValue();
            send("agent_step", step, id);
        }

        @Override
        public boolean isDisconnected() {
            // 门闸被容器取消等价于客户端已断开：AgentRunner 的 checkConnected
            // 会据此提前结束循环，不再继续调用模型、消耗额度
            return disconnected || gate.isCancelled();
        }

        String runKey() {
            return runKey;
        }

        void error(String message) {
            send("agent_error", Collections.singletonMap("message", message), null);
        }

        void send(String event, Object data) {
            send(event, data, null);
        }

        /** id 为 SSE 事件序号，续播时用它做 Last-Event-ID 去重 */
        void send(String event, Object data, Long id) {
            if (isDisconnected()) {
                return;
            }
            String json;
            try {
                json = mapper.writeValueAsString(data);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("流式事件序列化失败", e);
            }
            StringBuilder frame = new StringBuilder();
            frame.append("event: ").append(event).append('\n');
            if (id != null) {
                frame.append("id: ").append(id).append('\n');
            }
            frame.append("data: ").append(json).append("\n\n");
            final byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
            try {
                // 与容器回收互斥：cancel 之后的写入一律落到「已拒绝」，
                // 不会把字节写进被下一个请求复用的连接
                boolean written = gate.runIfActive(new AsyncResponseGate.ResponseAction() {
                    @Override
                    public void run() throws IOException {
                        response.getOutputStream().write(bytes);
                        response.flushBuffer();
                    }
                });
                if (!written) {
                    disconnected = true;
                }
            } catch (IOException e) {
                disconnected = true;
            }
        }
    }
}
