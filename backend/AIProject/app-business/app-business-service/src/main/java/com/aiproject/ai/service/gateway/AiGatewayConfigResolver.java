package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.DifyConfigBean;
import com.aiproject.ai.service.holde.DifyConfigBeanHolder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 解析某个网关模块对应的 Dify 地址与密钥。
 * <p>
 * 优先级：数据库配置 > application 配置/环境变量。
 */
@Slf4j
@Component
public class AiGatewayConfigResolver {

    public static final String MODULE_CHAT = "chat";
    public static final String MODULE_KNOWLEDGE_CHAT = "knowledgeChat";
    public static final String MODULE_FILE = "file";

    private static final String NAME_CHAT = "问答聊天对话";
    private static final String NAME_KNOWLEDGE_CHAT = "知识库对话/工作流";
    private static final String NAME_KNOWLEDGE_FILE = "知识库文件";

    @Autowired
    DifyConfigBeanHolder difyConfigBeanHolder;

    @Autowired
    AiGatewayProperties properties;

    public boolean isSupported(String module) {
        return MODULE_CHAT.equals(module)
                || MODULE_KNOWLEDGE_CHAT.equals(module)
                || MODULE_FILE.equals(module);
    }

    public String moduleName(String module) {
        if (MODULE_CHAT.equals(module)) {
            return NAME_CHAT;
        }
        if (MODULE_KNOWLEDGE_CHAT.equals(module)) {
            return NAME_KNOWLEDGE_CHAT;
        }
        if (MODULE_FILE.equals(module)) {
            return NAME_KNOWLEDGE_FILE;
        }
        return null;
    }

    /**
     * @return 解析结果；未配置时返回 null
     */
    public ResolvedConfig resolve(String module) {
        String moduleName = moduleName(module);
        if (moduleName == null) {
            return null;
        }

        try {
            DifyConfigBean where = new DifyConfigBean();
            where.setModuleName(moduleName);
            DifyConfigBean db = difyConfigBeanHolder.query(where);
            if (db != null && notBlank(db.getApiUrl()) && notBlank(db.getApiKey())) {
                return new ResolvedConfig(trimUrl(db.getApiUrl()), db.getApiKey().trim());
            }
        } catch (Exception e) {
            log.warn("[AiGateway] 读取数据库 Dify 配置失败, module={}: {}", module, e.getMessage());
        }

        AiGatewayProperties.Module fallback = properties.getDify().byModule(module);
        if (fallback != null && notBlank(fallback.getApiUrl()) && notBlank(fallback.getApiKey())) {
            return new ResolvedConfig(trimUrl(fallback.getApiUrl()), fallback.getApiKey().trim());
        }
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimUrl(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    @Getter
    public static class ResolvedConfig {
        private final String apiUrl;
        private final String apiKey;
        /** 智能体服务需要的 X-Agent-Id；Dify 模块为 null */
        private final String agentId;

        public ResolvedConfig(String apiUrl, String apiKey) {
            this(apiUrl, apiKey, null);
        }

        public ResolvedConfig(String apiUrl, String apiKey, String agentId) {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
            this.agentId = agentId;
        }
    }
}
