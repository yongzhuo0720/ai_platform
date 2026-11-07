package com.aiproject.ai.service.agent.dto;

import lombok.Data;

/**
 * Agent 事件续播请求。
 *
 * <p>断线重连走这个只读接口，而不是重新 POST /streamRun——后者会
 * 重新执行模型、重复消耗 Token。服务端只从已落库的 agentstepbean
 * 补发 stepNo 大于 lastEventId 的步骤。</p>
 */
@Data
public class AgentResumeRequest {

    private String runKey;

    private Long lastEventId;
}
