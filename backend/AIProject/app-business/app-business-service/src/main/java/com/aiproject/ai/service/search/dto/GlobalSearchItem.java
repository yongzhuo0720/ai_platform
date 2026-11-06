package com.aiproject.ai.service.search.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 一条搜索命中。
 *
 * <p>前端只负责渲染与跳转，标题、说明、目标路由全部由服务端给出，
 * 避免前端为每个资源类型再写一套拼接规则。</p>
 */
@Data
@ApiModel(value = "全局搜索命中项")
public class GlobalSearchItem {

    @ApiModelProperty("资源类型：agent / knowledge-base / rag-dataset / model / dataset")
    String category;

    @ApiModelProperty("展示标题")
    String title;

    @ApiModelProperty("补充说明")
    String subtitle;

    @ApiModelProperty("站内跳转路径")
    String route;
}
