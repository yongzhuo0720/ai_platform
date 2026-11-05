package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.bean.AgentBean;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一次模型调用所需的上下文（编排器 → 模型客户端）。 */
@Data
public class AgentInvocation {

    private String uid;

    private String runKey;

    private Integer stepNo;

    private AgentBean agent;

    /** OpenAI 兼容的 messages，直接由 Jackson 序列化 */
    private List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();

    /** OpenAI 兼容的 tools（function 定义） */
    private List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
}
