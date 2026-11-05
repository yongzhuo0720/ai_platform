package com.aiproject.ai.service.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮模型调用的解析结果。
 *
 * <p>用非流式调用拿到完整的 {@code choices[0].message}：既可能有正文，
 * 也可能有 tool_calls。编排器据此决定「结束」还是「执行工具后再问一轮」。</p>
 */
@Data
public class ModelReply {

    private String content = "";

    private List<ToolCall> toolCalls = new ArrayList<ToolCall>();

    private String finishReason;

    private long promptTokens;

    private long completionTokens;

    private long totalTokens;

    /** 毫分，与 ModelPriceResolver / aicalllogbean 口径一致 */
    private long costCents;

    private String modelName;

    private String requestId;

    @Data
    public static class ToolCall {
        private String id;
        private String name;
        /** 模型生成的参数，JSON 字符串；执行前必须按工具的 jsonSchema 校验 */
        private String arguments;
    }
}
