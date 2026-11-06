package com.aiproject.ai.service.search.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局搜索结果。
 *
 * <p>{@code degraded} 为 true 表示至少有一个数据源查询失败；
 * 此时仍返回其它数据源的结果，前端据此提示「部分结果不可用」，
 * 而不是让一次聚合搜索整体失败。</p>
 */
@Data
@ApiModel(value = "全局搜索结果")
public class GlobalSearchResponse {

    @ApiModelProperty("实际使用的关键词")
    String keyword;

    @ApiModelProperty("命中的总条数")
    int total;

    @ApiModelProperty("是否有关键数据源查询失败，结果不完整")
    boolean degraded;

    @ApiModelProperty("按资源类型分组")
    List<GlobalSearchGroup> groups = new ArrayList<GlobalSearchGroup>();
}
