package com.aiproject.ai.service.rag.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/** 启动一次评估 */
@Data
public class RagEvalRunRequest {

    @ApiModelProperty("数据集业务键")
    private String datasetKey;

    @ApiModelProperty("截断位置 k，1-20，默认 5")
    private Integer topK;
}
