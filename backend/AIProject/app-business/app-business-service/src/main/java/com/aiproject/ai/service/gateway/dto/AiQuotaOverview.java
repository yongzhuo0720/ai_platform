package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiQuotaOverview {
    private long defaultDailyTokenQuota;
    private List<AiQuotaView> items = new ArrayList<AiQuotaView>();
}
