package com.aiproject.ai.service.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

/**
 * 从 Dify / 智能体的 SSE、阻塞式 JSON 响应中提取用量与上下文信息。
 * <p>
 * 只做「嗅探」，不参与转发：解析失败一律忽略，绝不影响响应内容。
 */
@Getter
public class AiGatewayUsageParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private String modelName;
    private String conversationId;
    private String messageId;
    private String errorMessage;

    /**
     * 处理一行 SSE 文本（非 data 行会被忽略）。
     *
     * @return 这一行是否携带了回答正文——用于把「首 token 延迟」算在真正的正文上，
     *         而不是上游先发的心跳、role 帧或 usage 帧
     */
    public boolean acceptSseLine(String line) {
        if (line == null) {
            return false;
        }
        String text = line.trim();
        if (!text.startsWith("data:")) {
            return false;
        }
        String payload = text.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return false;
        }
        JsonNode root = parse(payload);
        if (root == null) {
            return false;
        }
        boolean carriesAnswer = carriesAnswer(root);
        applyRoot(root);
        return carriesAnswer;
    }

    /** 处理一个完整的 JSON 响应体（阻塞模式，或 SSE 的 data 行） */
    public void acceptJson(String json) {
        JsonNode root = parse(json);
        if (root != null) {
            applyRoot(root);
        }
    }

    private static JsonNode parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return root != null && root.isObject() ? root : null;
        } catch (Exception ignored) {
            // 非 JSON / 截断的 SSE 行：忽略
            return null;
        }
    }

    /**
     * 判断一个流式事件是否带正文。
     * <p>OpenAI 协议看 {@code choices[0].delta.content}（DeepSeek 思考流还有
     * {@code reasoning_content}，同样属于模型已产出的 token）；Dify 看
     * {@code event=message/agent_message} 的 {@code answer}。</p>
     */
    private static boolean carriesAnswer(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            if (delta.isObject() && hasContent(delta.get("content"))) {
                return true;
            }
            if (delta.isObject() && hasContent(delta.get("reasoning_content"))) {
                return true;
            }
            JsonNode message = choice.path("message");
            if (message.isObject() && hasContent(message.get("content"))) {
                return true;
            }
        }
        String event = text(root, "event");
        if ("message".equals(event) || "agent_message".equals(event)) {
            return hasContent(root.get("answer"));
        }
        return false;
    }

    private static boolean hasContent(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            return node.size() > 0;
        }
        String value = node.asText();
        return value != null && !value.isEmpty();
    }

    private void applyRoot(JsonNode root) {
        String event = text(root, "event");
        String object = text(root, "object");
        if ("error".equals(event)) {
            String message = text(root, "message");
            if (message == null) {
                message = text(root, "error");
            }
            if (message != null) {
                errorMessage = message;
            }
        }
        /*
         * OpenAI 协议的错误挂在根级 error 对象上（HTTP 仍可能是 200 或 4xx）：
         * {"error": {"message": "...", "type": "...", "code": "..."}}
         */
        if (errorMessage == null) {
            JsonNode error = root.path("error");
            if (error.isObject()) {
                String message = text(error, "message");
                if (message != null) {
                    String code = text(error, "code");
                    errorMessage = code == null ? message : message + " (" + code + ")";
                }
            }
        }
        /*
         * 智能体把失败放在 response 事件里（HTTP 仍是 200），
         * error 可能是字符串，也可能是 {message: ...} 对象。
         */
        if (errorMessage == null && "response".equals(object)
                && "failed".equals(text(root, "status"))) {
            JsonNode error = root.path("error");
            String message = error.isObject() ? text(error, "message") : text(root, "error");
            if (message != null) {
                errorMessage = message;
            }
        }
        if (errorMessage == null) {
            String code = text(root, "code");
            String message = text(root, "message");
            if (code != null && message != null) {
                errorMessage = message + " (" + code + ")";
            }
        }

        JsonNode data = root.path("data");
        if (conversationId == null) {
            conversationId = text(root, "conversation_id");
        }
        if (conversationId == null) {
            conversationId = text(root, "session_id");
        }
        if (conversationId == null && data.isObject()) {
            conversationId = text(data, "conversation_id");
        }

        if (messageId == null) {
            messageId = text(root, "message_id");
        }
        if (messageId == null && ("message".equals(event) || "message_end".equals(event))) {
            messageId = text(root, "id");
        }
        if (messageId == null && ("message".equals(object)
                || "chat.completion".equals(object) || "chat.completion.chunk".equals(object))) {
            messageId = text(root, "id");
        }
        if (messageId == null && data.isObject()) {
            messageId = text(data, "message_id");
        }

        if (modelName == null) {
            JsonNode metadata = root.path("metadata");
            if (metadata.isObject()) {
                String name = text(metadata, "model_name");
                if (name != null) {
                    modelName = name;
                }
            }
        }
        if (modelName == null) {
            String name = text(root, "model_name");
            if (name != null) {
                modelName = name;
            }
        }
        // OpenAI 协议：每个响应块根级都带 model 字段
        if (modelName == null) {
            String name = text(root, "model");
            if (name != null) {
                modelName = name;
            }
        }

        JsonNode metadata = root.path("metadata");
        if (metadata.isObject()) {
            applyUsage(metadata.path("usage"));
            if (modelName == null) {
                String name = text(metadata, "model_name");
                if (name != null) {
                    modelName = name;
                }
            }
        }
        applyUsage(root.path("usage"));
        if (data.isObject()) {
            applyUsage(data.path("usage"));
            long dataTotal = data.path("total_tokens").asLong(0);
            if (dataTotal > 0) {
                totalTokens = max(totalTokens, dataTotal);
            }
        }
    }

    private void applyUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return;
        }
        long prompt = usage.path("prompt_tokens").asLong(0);
        if (prompt <= 0) {
            prompt = usage.path("input_tokens").asLong(0);
        }
        long completion = usage.path("completion_tokens").asLong(0);
        if (completion <= 0) {
            completion = usage.path("output_tokens").asLong(0);
        }
        long total = usage.path("total_tokens").asLong(0);
        if (total <= 0) {
            total = prompt + completion;
        }
        if (prompt > 0) {
            promptTokens = max(promptTokens, prompt);
        }
        if (completion > 0) {
            completionTokens = max(completionTokens, completion);
        }
        if (total > 0) {
            totalTokens = max(totalTokens, total);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isEmpty() ? null : text;
    }

    private static long max(Long current, long candidate) {
        return current == null ? candidate : Math.max(current, candidate);
    }
}
