package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiGatewayConfigStatus {
    private boolean enabled;
    private int rateLimitPerMinute;
    private long defaultDailyTokenQuota;
    private List<ModuleStatus> modules = new ArrayList<ModuleStatus>();

    @Data
    public static class ModuleStatus {
        private String module;
        private String moduleName;
        private boolean configured;

        public ModuleStatus(String module, String moduleName, boolean configured) {
            this.module = module;
            this.moduleName = moduleName;
            this.configured = configured;
        }
    }
}
