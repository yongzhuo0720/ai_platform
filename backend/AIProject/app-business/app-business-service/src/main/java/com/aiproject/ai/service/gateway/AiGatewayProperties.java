package com.aiproject.ai.service.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 网关配置。
 * <p>
 * Dify 的 URL / Key 优先取数据库 difyconfigbean（后台管理页面维护）；
 * 数据库没配时回退到这里的 dify.* 属性，便于本地开发通过环境变量注入，
 * 避免把密钥写进仓库。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.gateway")
public class AiGatewayProperties {

    /** 网关总开关 */
    private boolean enabled = true;

    /** 每用户每分钟最大请求数，<=0 表示不限制 */
    private int rateLimitPerMinute = 120;

    /** 默认每日 Token 配额，<=0 表示不限制 */
    private long defaultDailyTokenQuota = 200000;

    /**
     * 每次请求开始前预扣的 Token 数，<=0 表示关闭预扣（退回「完成后才计费」）。
     * <p>
     * 并发请求在「检查额度」与「完成后扣额度」之间存在窗口，先查后加会让多个
     * 请求同时通过检查、完成后一起超额。预扣把窗口缩小到一次 Redis 自增：
     * 请求结束再按上游返回的真实 usage 结算（多退少补），失败或中断则全额释放。
     */
    private long quotaReserveTokens = 1500;

    /** 连接 Dify 超时（毫秒） */
    private int connectTimeoutMs = 10000;

    /** 读取 Dify 响应超时（毫秒），流式对话需要留足时间 */
    private int readTimeoutMs = 300000;

    /** 日志中错误信息最大长度 */
    private int maxErrorLength = 1000;

    private Dify dify = new Dify();

    @Data
    public static class Dify {
        private Module chat = new Module();
        private Module knowledgeChat = new Module();
        private Module file = new Module();

        public Module byModule(String module) {
            if ("chat".equals(module)) {
                return chat;
            }
            if ("knowledgeChat".equals(module)) {
                return knowledgeChat;
            }
            if ("file".equals(module)) {
                return file;
            }
            return null;
        }
    }

    @Data
    public static class Module {
        private String apiUrl;
        private String apiKey;
    }
}
