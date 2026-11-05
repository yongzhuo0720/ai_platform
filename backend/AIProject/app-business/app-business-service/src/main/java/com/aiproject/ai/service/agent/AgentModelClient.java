package com.aiproject.ai.service.agent;

/**
 * Agent 编排器与「模型调用」之间的边界。
 *
 * <p>抽这一层的目的：编排逻辑（循环、终止条件、步骤记录）可以完全独立于
 * HTTP / 上游协议做单测；真实实现 {@code HttpAgentModelClient} 仍然走
 * 网关的限流、预扣、审计与计价组件，不另起一套治理口径。</p>
 */
public interface AgentModelClient {

    ModelReply call(AgentInvocation invocation) throws AgentCallException;
}
