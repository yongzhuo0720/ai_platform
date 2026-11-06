package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型可观测健康面板的一次性快照。
 *
 * <p>只包含「配置是否完整、是否启用、价格是否配置」与今日调用统计，
 * 不包含服务地址与密钥；真实连通性由连接测试单独触发。</p>
 */
@Data
public class AiGatewayHealth {

    private boolean gatewayEnabled;
    private int rateLimitPerMinute;
    private long defaultDailyTokenQuota;
    private long quotaReserveTokens;
    private long checkedAt;

    private List<AiGatewayConfigStatus.ModuleStatus> modules =
            new ArrayList<AiGatewayConfigStatus.ModuleStatus>();
    private List<ModelHealth> models = new ArrayList<ModelHealth>();
    /** 今日调用汇总（与用量看板同一套聚合口径） */
    private AiGatewaySummary today;

    @Data
    public static class ModelHealth {
        private Long iid;
        private String name;
        private String upstreamModel;
        /** 是否参与直连路由（enable=false 的配置会被网关跳过） */
        private boolean enabled;
        /** 服务地址、密钥、上游模型名是否齐全 */
        private boolean ready;
        /** 输入或输出单价是否至少配置了一项 */
        private boolean priced;
        private boolean hasEmbedModel;
    }
}
