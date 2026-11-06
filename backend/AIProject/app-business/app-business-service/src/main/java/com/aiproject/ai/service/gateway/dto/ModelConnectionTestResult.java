package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

/**
 * 模型连接测试结果。
 *
 * <p>只描述「这次探测看到了什么」，不携带服务地址与密钥：
 * 前端拿到的是一句可操作的中文原因，而不是内网拓扑。</p>
 */
@Data
public class ModelConnectionTestResult {

    /** ok / model_missing / auth_failed / rate_limited / unreachable / http_error */
    public static final String STATUS_OK = "ok";
    public static final String STATUS_MODEL_MISSING = "model_missing";
    public static final String STATUS_AUTH_FAILED = "auth_failed";
    public static final String STATUS_RATE_LIMITED = "rate_limited";
    public static final String STATUS_UNREACHABLE = "unreachable";
    public static final String STATUS_HTTP_ERROR = "http_error";

    private Long iid;
    private String modelName;
    private String upstreamModel;
    /** 见 STATUS_* 常量 */
    private String status;
    /** 是否在 /models 列表中确认到配置的上游模型；走对话兜底验证时为 true */
    private boolean modelAvailable;
    private Integer httpStatus;
    private long latencyMs;
    private String message;
    private long checkedAt;
}
