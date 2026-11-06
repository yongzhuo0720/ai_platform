package com.aiproject.ai.service.search.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 全局搜索请求：只接受一个关键词。
 *
 * <p>分类与每类条数由服务端固定，避免客户端用超大 limit 拉全量数据。</p>
 */
@Data
@ApiModel(value = "全局搜索请求", description = "跨资源搜索")
public class GlobalSearchRequest {

    @ApiModelProperty("搜索关键词，最多 50 个字符")
    String keyword;
}
