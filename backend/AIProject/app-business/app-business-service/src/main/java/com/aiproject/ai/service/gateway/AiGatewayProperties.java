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

    /**
     * 单个模块同时进行中的上游请求上限，<=0 表示不限制。
     * <p>
     * 按模块隔离而不是全局限一个数：一个用户开几十条长对话，不应该把
     * 文件上传或知识库检索一起拖死。超限直接 429，不排队——
     * 排队只会把 Web 线程也一起占满，连登录接口都受影响。
     * <p>
     * 这是「单实例」上限；多实例部署时实际并发 = 本值 × 实例数。
     */
    private int maxConcurrentPerModule = 32;

    /**
     * 单次流式 / 透传响应的总时长上限（毫秒），<=0 表示不限制。
     * <p>
     * readTimeoutMs 约束的是「两次读取之间」的间隔，管不住一个持续吐字
     * 但永远不结束的上游；这里给整条响应一个硬截止时间，到点断流并按
     * 已转发正文计费，避免长连接永久占用线程与配额预扣。
     */
    private int maxStreamMs = 600000;

    /**
     * 流式响应空闲时发送心跳的间隔（毫秒），<=0 表示不发心跳。
     * <p>
     * 作用有两个：让 Nginx 等中间层不把长时间无数据的 SSE 当死连接掐掉；
     * 更重要的是借这次写入探测浏览器是否已经关页面——读阻塞在上游时，
     * 只有写客户端才能知道连接是否还在。
     */
    private int sseHeartbeatMs = 15000;

    /**
     * 同一上游连续失败多少次后熔断，<=0 表示关闭熔断。
     * <p>
     * 只统计「上游不可用」类失败：连接失败、读超时、5xx。
     * 客户端主动断开、参数错误、上游 4xx 都不计入——它们说明请求本身有问题，
     * 不是上游挂了；把它们算进去会让熔断器误伤正常流量。
     */
    private int circuitFailureThreshold = 5;

    /**
     * 熔断后多久进入半开状态（毫秒），期间新请求直接 503。
     * 半开时只放一个探针请求，成功则恢复，失败则重新计时。
     */
    private int circuitOpenMs = 30000;

    /**
     * 半开探针的最长等待时间（毫秒）。探针在这个时间内没有成功/失败结论，
     * 按失败处理并重新进入冷却，避免一个卡死的探针把后续请求永久挡在门外。
     * 应小于上游读超时，否则探针会先被 HTTP 超时终结，这个兜底不会生效。
     */
    private int circuitProbeTimeoutMs = 60000;

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
