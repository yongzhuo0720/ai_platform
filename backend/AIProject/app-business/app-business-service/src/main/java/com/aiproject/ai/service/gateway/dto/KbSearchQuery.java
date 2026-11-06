package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

/** 本地知识库检索请求 */
@Data
public class KbSearchQuery {
    private String query;
    private String kbName;
    private Integer topK;
    /** null/true 跟随全局精排配置；false 仅本次检索关闭精排 */
    private Boolean rerankEnabled;
}
