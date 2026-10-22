package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

/**
 * 后台 AI 看板查询条件。
 */
@Data
public class AiGatewayLogQuery {
    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String userId;
    private String module;
    private String status;
    /** yyyy-MM-dd */
    private String startDate;
    /** yyyy-MM-dd */
    private String endDate;
}
