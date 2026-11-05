package com.aiproject.ai.service.agent;

/** 模型调用失败。用受检异常强制编排器处理每一轮失败，而不是静默跳过。 */
public class AgentCallException extends Exception {

    public AgentCallException(String message) {
        super(message);
    }

    public AgentCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
