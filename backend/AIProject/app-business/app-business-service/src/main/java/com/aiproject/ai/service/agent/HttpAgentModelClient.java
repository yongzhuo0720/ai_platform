package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.gateway.AiGatewayGuard;
import com.aiproject.ai.service.gateway.AiGatewayProperties;
import com.aiproject.ai.service.gateway.ModelGatewayResolver;
import com.aiproject.ai.service.gateway.ModelPriceResolver;
import com.aiproject.ai.service.holde.AiCallLogHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 走网关治理组件的非流式模型客户端。
 *
 * <p>为什么不直接 new 一个 HttpURLConnection 打上游了事：那样会绕开限流、
 * Token 预扣、审计与计价，等于给 Agent 开了一条不受治理的后门。
 * 这里逐项复用 {@link AiGatewayGuard#check} / {@link AiGatewayGuard#reserve} /
 * {@link AiGatewayGuard#settle}、{@link ModelPriceResolver} 与
 * {@link AiCallLogHolder#record}，口径与 {@code AiGatewayService} 一致。</p>
 *
 * <p>与网关 SSE 主链路的区别：Agent 编排需要拿到完整的 message（含 tool_calls）
 * 才能决定下一步，所以用 {@code stream=false}；密钥仍只在服务端。</p>
 */
@Slf4j
@Component
public class HttpAgentModelClient implements AgentModelClient {

    private static final String MODULE = "models";
    private static final int MAX_ROUND_TIMEOUT_MS = 120000;

    @Autowired
    ModelGatewayResolver modelGatewayResolver;

    @Autowired
    AiGatewayGuard guard;

    @Autowired
    AiGatewayProperties properties;

    @Autowired
    ModelPriceResolver priceResolver;

    @Autowired
    AiCallLogHolder callLogHolder;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ModelReply call(AgentInvocation inv) throws AgentCallException {
        if (inv == null || inv.getAgent() == null || inv.getUid() == null) {
            throw new AgentCallException("调用上下文不完整");
        }
        Long modelIid = inv.getAgent().getModelIid();
        ModelGatewayResolver.ResolvedModel model = modelIid == null
                ? null : modelGatewayResolver.resolve(modelIid);
        if (model == null) {
            throw new AgentCallException("尚未配置可用的直连模型，请先在模型配置里选择并启用一个模型");
        }

        AiGatewayGuard.Decision decision = guard.check(inv.getUid(), MODULE);
        if (!decision.isAllowed()) {
            throw new AgentCallException(decision.getMessage());
        }
        AiGatewayGuard.Reservation reservation = guard.reserve(inv.getUid(), MODULE);
        if (reservation.isBlocked()) {
            throw new AgentCallException(reservation.getBlockedMessage());
        }

        String requestId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        long actualTokens = 0L;
        boolean succeeded = false;
        HttpURLConnection conn = null;
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model.getUpstreamModel());
            body.put("stream", false);
            body.set("messages", mapper.valueToTree(inv.getMessages()));
            if (inv.getTools() != null && !inv.getTools().isEmpty()) {
                body.set("tools", mapper.valueToTree(inv.getTools()));
            }

            conn = (HttpURLConnection) new URL(model.getBaseUrl() + "/chat/completions").openConnection();
            conn.setConnectTimeout(properties.getConnectTimeoutMs());
            conn.setReadTimeout(Math.min(properties.getReadTimeoutMs(), MAX_ROUND_TIMEOUT_MS));
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Authorization", "Bearer " + model.getApiKey());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new AgentCallException("上游模型返回 HTTP " + status);
            }
            String response = readAll(conn.getInputStream());
            ModelReply reply = parse(response, model.getUpstreamModel(), requestId);
            actualTokens = reply.getTotalTokens();

            ModelPriceResolver.Price price = priceResolver.resolve(model.getUpstreamModel());
            reply.setCostCents(price == null ? 0L
                    : price.costCents(reply.getPromptTokens(), reply.getCompletionTokens()));
            succeeded = true;
            writeAudit(inv, requestId, model.getUpstreamModel(), startedAt,
                    reply, 200, null);
            return reply;
        } catch (AgentCallException e) {
            writeAudit(inv, requestId, model.getUpstreamModel(), startedAt,
                    null, 0, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("[Agent] 模型调用失败, uid={}, runKey={}: {}",
                    inv.getUid(), inv.getRunKey(), e.getMessage());
            writeAudit(inv, requestId, model.getUpstreamModel(), startedAt,
                    null, 0, e.getMessage());
            throw new AgentCallException("模型调用失败，请稍后重试", e);
        } finally {
            // 失败全额释放预扣；成功按上游真实 usage 结算（多退少补）
            guard.settle(reservation, succeeded ? actualTokens : 0L);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private ModelReply parse(String json, String modelName, String requestId) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode choice = root.path("choices").isArray() && root.path("choices").size() > 0
                ? root.path("choices").get(0) : mapper.createObjectNode();
        JsonNode message = choice.path("message");

        ModelReply reply = new ModelReply();
        reply.setContent(message.path("content").asText(""));
        reply.setFinishReason(choice.path("finish_reason").asText(""));
        reply.setModelName(modelName);
        reply.setRequestId(requestId);

        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode node : calls) {
                ModelReply.ToolCall call = new ModelReply.ToolCall();
                call.setId(node.path("id").asText(""));
                call.setName(node.path("function").path("name").asText(""));
                call.setArguments(node.path("function").path("arguments").asText("{}"));
                if (!call.getName().isEmpty()) {
                    reply.getToolCalls().add(call);
                }
            }
        }

        JsonNode usage = root.path("usage");
        long prompt = usage.path("prompt_tokens").asLong(0);
        long completion = usage.path("completion_tokens").asLong(0);
        long total = usage.path("total_tokens").asLong(prompt + completion);
        reply.setPromptTokens(prompt);
        reply.setCompletionTokens(completion);
        reply.setTotalTokens(total);
        return reply;
    }

    /** 审计日志只记元数据，不记提问与回答正文 */
    private void writeAudit(AgentInvocation inv, String requestId, String modelName,
                            long startedAt, ModelReply reply, int httpStatus, String error) {
        AiCallLogBean entry = new AiCallLogBean();
        entry.setUserId(inv.getUid());
        entry.setModule(MODULE);
        entry.setEndpoint("agent/run");
        entry.setMethod("POST");
        entry.setStatus(reply == null ? "failed" : "success");
        entry.setHttpStatus(httpStatus);
        entry.setPromptTokens(reply == null ? 0L : reply.getPromptTokens());
        entry.setCompletionTokens(reply == null ? 0L : reply.getCompletionTokens());
        entry.setTotalTokens(reply == null ? 0L : reply.getTotalTokens());
        entry.setCostCents(reply == null ? 0L : reply.getCostCents());
        entry.setLatencyMs(System.currentTimeMillis() - startedAt);
        entry.setModelName(modelName);
        entry.setRequestId(requestId);
        entry.setErrorMessage(error);
        entry.setRunKey(inv.getRunKey());
        entry.setStepNo(inv.getStepNo());
        callLogHolder.record(entry);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
