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

    /**
     * 部分推理模型把思考过程放在 reasoning_content / reasoning，只在 content
     * 里放最终结论。这里单独保留，用于区分「模型只给了推理、没给答案」与
     * 「上游完全空回复」——两者的排查方向完全不同，不能共用一句提示。
     */
    private String reasoningContent = "";

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
