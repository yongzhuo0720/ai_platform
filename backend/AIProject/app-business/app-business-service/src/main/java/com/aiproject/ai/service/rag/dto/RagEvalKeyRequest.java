package com.aiproject.ai.service.rag.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/** 按业务键查询 / 删除 */
@Data
public class RagEvalKeyRequest {

    @ApiModelProperty("数据集业务键")
    private String datasetKey;

    @ApiModelProperty("运行业务键")
    private String runKey;

    @ApiModelProperty("手动指定的基线运行键；为空时自动选择更早的可对比运行")
    private String baselineRunKey;
}
