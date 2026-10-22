package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.AssistantBean;
import com.aiproject.ai.service.holde.AssistantBeanHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 解析智能体网关的上游地址与凭据。
 * <p>
 * 与 Dify 不同，智能体的凭据是「按助手」的：
 * - default：内置助手，取服务端 ai.gateway.agent.* 配置；
 * - 数字 id：取 assistantbean 中对应助手的 X-Agent-Id 与 Bearer Token。
 * <p>
 * 上游地址始终来自服务端配置，不使用助手记录里的 apiUrl，
 * 避免把数据库中的任意地址变成服务端请求目标（SSRF）。
 */
@Slf4j
@Component
public class AgentGatewayConfigResolver {

    public static final String MODULE_AGENT = "agent";
    public static final String MODULE_NAME = "智能体助手";
    public static final String DEFAULT_KEY = "default";

    @Autowired
    AiGatewayProperties properties;

    @Autowired
    AssistantBeanHolder assistantBeanHolder;

    public boolean isDefaultConfigured() {
        AiGatewayProperties.Agent defaults = properties.getAgent();
        return trimToNull(defaults.getApiUrl()) != null
                && trimToNull(defaults.getAuthToken()) != null
                && trimToNull(defaults.getAgentId()) != null;
    }

    public boolean isValidKey(String key) {
        return DEFAULT_KEY.equals(key) || parseId(key) != null;
    }

    /**
     * @return 解析结果；服务地址、Token 或 AgentId 缺失，或助手不存在/无权使用时返回 null
     */
    public AiGatewayConfigResolver.ResolvedConfig resolve(String userId, String assistantKey) {
        AiGatewayProperties.Agent defaults = properties.getAgent();
        String apiUrl = trimToNull(defaults.getApiUrl());

        if (DEFAULT_KEY.equals(assistantKey)) {
            return build(apiUrl, defaults.getAuthToken(), defaults.getAgentId());
        }

        Long iid = parseId(assistantKey);
        if (iid == null) {
            return null;
        }
        AssistantBean assistant = assistantBeanHolder.findUsable(userId, iid);
        if (assistant == null) {
            return null;
        }
        return build(apiUrl, assistant.getAuthToken(), assistant.getAgentId());
    }

    private static AiGatewayConfigResolver.ResolvedConfig build(
            String apiUrl, String authToken, String agentId) {
        String url = trimToNull(apiUrl);
        String token = trimToNull(authToken);
        String id = trimToNull(agentId);
        if (url == null || token == null || id == null) {
            return null;
        }
        return new AiGatewayConfigResolver.ResolvedConfig(trimUrl(url), token, id);
    }

    private static Long parseId(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimUrl(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
