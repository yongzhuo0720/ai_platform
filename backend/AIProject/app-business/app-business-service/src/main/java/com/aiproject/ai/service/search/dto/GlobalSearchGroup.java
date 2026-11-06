package com.aiproject.ai.service.search.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 按资源类型分组后的搜索结果。
 */
@Data
@ApiModel(value = "全局搜索分组")
public class GlobalSearchGroup {

    @ApiModelProperty("资源类型")
    String category;

    @ApiModelProperty("分组展示名")
    String label;

    @ApiModelProperty("命中项")
    List<GlobalSearchItem> items;

    public GlobalSearchGroup() {
    }

    public GlobalSearchGroup(String category, String label, List<GlobalSearchItem> items) {
        this.category = category;
        this.label = label;
        this.items = items;
    }
}
