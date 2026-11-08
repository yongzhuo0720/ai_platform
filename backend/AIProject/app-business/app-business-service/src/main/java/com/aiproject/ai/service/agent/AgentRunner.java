package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.bean.AgentToolBean;
import com.aiproject.ai.service.holde.AgentConfigHolder;
import com.aiproject.ai.service.holde.AgentRunHolder;
import com.aiproject.ai.service.holde.AgentToolHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 执行编排器：模型返回 tool_calls → 执行工具 → 以 role=tool 回灌 → 再问一轮。
 *
 * <p>四类终止条件必须同时存在，否则一次失控的循环会一直烧 Token：
 * 最大迭代步数、Token 预算、总超时、重复工具调用检测。</p>
 *
 * <p>所有模型调用都经 {@link AgentModelClient}，它复用网关的限流/预扣/审计/计价，
 * 所以这里不需要（也不应该）再实现一遍治理逻辑。</p>
 */
@Slf4j
@Component
public class AgentRunner {

    private static final long MAX_TOTAL_MS = 120000L;
    private static final int DEFAULT_MAX_ITERATIONS = 8;
    private static final long DEFAULT_TOKEN_BUDGET = 20000L;
    private static final int MAX_STEP_TEXT = 2000;

    @Autowired
    AgentConfigHolder configHolder;

    @Autowired
    AgentToolHolder toolHolder;

    @Autowired
    AgentRunHolder runHolder;

    @Autowired
    AgentToolExecutor toolExecutor;

    @Autowired
    AgentModelClient modelClient;

    private final ObjectMapper mapper = new ObjectMapper();

    public interface Listener {
        default void onStart(String runKey) {
        }

        default void onStep(AgentStepBean step) {
        }

        default boolean isDisconnected() {
            return false;
        }
    }

    public Result<AgentRunDetail> run(String uid, String agentKey, String question) {
        return run(uid, agentKey, question, new Listener() { });
    }

    public Result<AgentRunDetail> run(String uid, String agentKey, String question, Listener listener) {
        if (listener == null) {
            listener = new Listener() { };
        }
        if (AgentConfigHolder.blank(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (AgentConfigHolder.blank(agentKey)) {
            return Result.failed(null, "Agent 标识不能为空");
        }
        AgentBean agent = configHolder.findByKey(uid, agentKey);
        if (agent == null) {
            return Result.failed(null, "Agent 不存在或不属于当前用户");
        }
        if (AgentConfigHolder.blank(question)) {
            return Result.failed(null, "请输入问题");
        }
        if (question.length() > 20000) {
            return Result.failed(null, "问题不能超过 20000 个字符");
        }
        if (agent.getModelIid() == null) {
            return Result.failed(null, "请先为这个 Agent 选择模型");
        }

        int maxIterations = agent.getMaxIterations() == null || agent.getMaxIterations() < 1
                ? DEFAULT_MAX_ITERATIONS : agent.getMaxIterations();
        long tokenBudget = agent.getTokenBudget() == null || agent.getTokenBudget() < 1
                ? DEFAULT_TOKEN_BUDGET : agent.getTokenBudget();

        String runKey = UUID.randomUUID().toString().replace("-", "");
        long startedAt = System.currentTimeMillis();
        try {
            runHolder.start(uid, agent.getIid(), question.trim(), runKey);
        } catch (AgentRunHolder.AgentRunConflictException e) {
            // 并发闸门按设计拒绝；明确告诉用户等，避免误报成数据库故障让他反复重试
            return Result.failed(null, e.getMessage());
        }

        int stepNo = 0;
        int iterations = 0;
        long promptSum = 0L;
        long completionSum = 0L;
        long costSum = 0L;
        String status = "failed";
        String error = "达到最大迭代步数";
        String answer = "";
        String lastSignature = null;

        try {
            listener.onStart(runKey);
            checkConnected(listener);
            List<AgentToolBean> tools = toolHolder.listForRun(uid, agent.getIid());
            List<Map<String, Object>> messages = initialMessages(agent, question.trim());
            List<Map<String, Object>> toolsJson = toTools(tools);

            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                checkConnected(listener);
                iterations = iteration;
                if (System.currentTimeMillis() - startedAt > MAX_TOTAL_MS) {
                    error = "执行总超时";
                    break;
                }

                AgentInvocation invocation = new AgentInvocation();
                invocation.setUid(uid);
                invocation.setRunKey(runKey);
                invocation.setStepNo(stepNo + 1);
                invocation.setAgent(agent);
                invocation.setMessages(messages);
                invocation.setTools(toolsJson);

                ModelReply reply;
                long roundStart = System.currentTimeMillis();
                try {
                    reply = modelClient.call(invocation);
                } catch (AgentCallException e) {
                    error = e.getMessage();
                    stepNo++;
                    recordStep(listener, uid, runKey, stepNo, iteration, "error", null, null, null,
                            "failed", 0L, 0L, 0L, System.currentTimeMillis() - roundStart,
                            truncate(error), null);
                    break;
                }

                stepNo++;
                promptSum += reply.getPromptTokens();
                completionSum += reply.getCompletionTokens();
                costSum += reply.getCostCents();
                recordStep(listener, uid, runKey, stepNo, iteration, "llm", null, null,
                        truncate(reply.getContent()), "success", reply.getPromptTokens(),
                        reply.getCompletionTokens(), reply.getCostCents(),
                        System.currentTimeMillis() - roundStart, null, reply.getRequestId());

                // 最终答案已经产出，不再有后续动作，预算无需拦截——
                // 这一轮的 token 已经花掉，判失败只会丢掉用户已付费的完整回答
                if (reply.getToolCalls().isEmpty()) {
                    String content = reply.getContent() == null ? "" : reply.getContent();
                    // 空回复不能算成功：用户拿到的是「运行成功 + 空白答案」，
                    // 既无法判断是模型拒绝、内容过滤还是上游异常
                    if (content.trim().isEmpty()) {
                        String reasoning = reply.getReasoningContent() == null
                                ? "" : reply.getReasoningContent();
                        if (!reasoning.trim().isEmpty()) {
                            // 推理模型只吐了思考过程没给结论：提示换模型或改提示词，
                            // 而不是笼统地说「未返回内容」让用户盲目重试
                            error = "模型只返回了推理内容，未给出最终答案；请在提示词中明确要求输出结论，或更换直接输出 content 的模型";
                            log.info("[Agent] 仅收到推理内容, uid={}, runKey={}, chars={}",
                                    uid, runKey, reasoning.length());
                        } else {
                            error = "模型未返回内容，请重试或更换模型";
                        }
                        stepNo++;
                        // 把推理内容截断留在错误步骤里，用户能在时间线上看到
                        // 「模型想了但没给结论」，而不是只看到一句笼统的失败提示
                        String reasoningHint = reasoning.trim().isEmpty() ? null
                                : "模型只输出了推理内容（未给出结论）：" + truncate(reasoning);
                        recordStep(listener, uid, runKey, stepNo, iteration, "error", null, null,
                                reasoningHint, "failed", 0L, 0L, 0L, 0L, truncate(error), null);
                        break;
                    }
                    answer = content;
                    status = "succeeded";
                    error = null;
                    stepNo++;
                    recordStep(listener, uid, runKey, stepNo, iteration, "finish", null, null,
                            truncate(answer), "success", 0L, 0L, 0L, 0L, null,
                            reply.getRequestId());
                    break;
                }

                // 预算只约束「还要继续执行工具、再空转一轮」的情况
                if (promptSum + completionSum >= tokenBudget) {
                    error = "Token 预算已耗尽";
                    stepNo++;
                    recordStep(listener, uid, runKey, stepNo, iteration, "error", null, null, null,
                            "failed", 0L, 0L, 0L, 0L, truncate(error), null);
                    break;
                }

                // 同一批工具调用连续出现两次，说明模型卡住了，继续循环只会重复烧 Token
                String signature = signature(reply.getToolCalls());
                if (signature.equals(lastSignature)) {
                    error = "检测到重复工具调用，已终止循环";
                    stepNo++;
                    recordStep(listener, uid, runKey, stepNo, iteration, "error", null, null, null,
                            "failed", 0L, 0L, 0L, 0L, truncate(error), null);
                    break;
                }
                lastSignature = signature;

                messages.add(assistantMessage(reply));
                for (ModelReply.ToolCall call : reply.getToolCalls()) {
                    checkConnected(listener);
                    AgentToolBean tool = findTool(tools, call.getName());
                    String result;
                    String stepStatus = "success";
                    String toolError = null;
                    long toolStart = System.currentTimeMillis();
                    try {
                        // 空值由执行器回落到默认值；配置上限在 AgentConfigHolder 已校验
                        result = toolExecutor.execute(uid, tool, call.getArguments(),
                                agent.getToolTimeoutMs());
                    } catch (AgentCallException e) {
                        // 工具失败不直接终止：把错误回灌给模型，让它有机会换参数重试
                        result = "工具执行失败：" + e.getMessage();
                        stepStatus = "failed";
                        toolError = e.getMessage();
                    }
                    stepNo++;
                    recordStep(listener, uid, runKey, stepNo, iteration, "tool_result",
                            call.getName(), truncate(call.getArguments()), truncate(result),
                            stepStatus, 0L, 0L, 0L, System.currentTimeMillis() - toolStart,
                            truncate(toolError), null);
                    messages.add(toolMessage(call.getId(), result));
                }
            }
        } catch (RunDisconnectedException e) {
            status = "failed";
            error = "客户端已断开连接，执行已停止";
        } catch (DataAccessException e) {
            status = "failed";
            error = "运行记录读写失败，请稍后重试";
            throw e;
        } catch (Exception e) {
            log.error("[Agent] 执行异常, uid={}, runKey={}", uid, runKey);
            status = "failed";
            error = "执行异常，请稍后重试";
        } finally {
            runHolder.finish(uid, runKey, status, iterations, promptSum, completionSum, costSum,
                    System.currentTimeMillis() - startedAt, answer, truncate(error));
        }
        return runHolder.detail(uid, runKey);
    }

    private void recordStep(Listener listener, String uid, String runKey, int stepNo, int iteration,
                            String stepType, String toolName, String args, String result,
                            String status, long promptTokens, long completionTokens,
                            long costCents, long latencyMs, String error, String requestId) {
        AgentStepBean step;
        try {
            step = runHolder.addStep(uid, runKey, stepNo, iteration, stepType, toolName, args,
                    result, status, promptTokens, completionTokens, costCents, latencyMs, error, requestId);
        } catch (DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DataRetrievalFailureException("运行步骤保存失败", e);
        }
        listener.onStep(step);
        checkConnected(listener);
    }

    private void checkConnected(Listener listener) {
        if (listener.isDisconnected()) {
            throw new RunDisconnectedException();
        }
    }

    private static class RunDisconnectedException extends RuntimeException {
    }

    private List<Map<String, Object>> initialMessages(AgentBean agent, String question) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        if (!AgentConfigHolder.blank(agent.getSystemPrompt())) {
            Map<String, Object> system = new HashMap<String, Object>();
            system.put("role", "system");
            system.put("content", agent.getSystemPrompt());
            messages.add(system);
        }
        Map<String, Object> user = new HashMap<String, Object>();
        user.put("role", "user");
        user.put("content", question);
        messages.add(user);
        return messages;
    }

    /** 把注册的工具转成 OpenAI function calling 的 tools 数组 */
    private List<Map<String, Object>> toTools(List<AgentToolBean> tools) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AgentToolBean tool : tools) {
            try {
                Map<String, Object> function = new HashMap<String, Object>();
                function.put("name", tool.getName());
                function.put("description", tool.getDescription() == null ? "" : tool.getDescription());
                function.put("parameters", mapper.readValue(tool.getJsonSchema(), Map.class));
                Map<String, Object> item = new HashMap<String, Object>();
                item.put("type", "function");
                item.put("function", function);
                result.add(item);
            } catch (Exception e) {
                // 单个工具 Schema 坏了不该让整轮执行崩掉，跳过并留痕
                log.warn("[Agent] 工具 Schema 无法解析, name={}: {}", tool.getName(), e.getMessage());
            }
        }
        return result;
    }

    private Map<String, Object> assistantMessage(ModelReply reply) {
        Map<String, Object> message = new HashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", reply.getContent() == null ? "" : reply.getContent());
        List<Map<String, Object>> calls = new ArrayList<Map<String, Object>>();
        for (ModelReply.ToolCall call : reply.getToolCalls()) {
            Map<String, Object> function = new HashMap<String, Object>();
            function.put("name", call.getName());
            function.put("arguments", call.getArguments());
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("id", call.getId());
            item.put("type", "function");
            item.put("function", function);
            calls.add(item);
        }
        message.put("tool_calls", calls);
        return message;
    }

    private Map<String, Object> toolMessage(String callId, String content) {
        Map<String, Object> message = new HashMap<String, Object>();
        message.put("role", "tool");
        message.put("tool_call_id", callId == null ? "" : callId);
        message.put("content", content);
        return message;
    }

    private AgentToolBean findTool(List<AgentToolBean> tools, String name) {
        if (name == null) {
            return null;
        }
        for (AgentToolBean tool : tools) {
            if (name.equals(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    private String signature(List<ModelReply.ToolCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (ModelReply.ToolCall call : calls) {
            sb.append(call.getName()).append('|').append(call.getArguments()).append(';');
        }
        return sb.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_STEP_TEXT ? text.substring(0, MAX_STEP_TEXT) : text;
    }
}
