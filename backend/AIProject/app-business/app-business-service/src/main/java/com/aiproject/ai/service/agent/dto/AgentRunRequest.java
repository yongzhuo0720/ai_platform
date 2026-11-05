package com.aiproject.ai.service.agent.dto;

import lombok.Data;

/** 发起一次 Agent 运行的请求体。 */
@Data
public class AgentRunRequest {

    private String agentKey;

    private String question;
}
