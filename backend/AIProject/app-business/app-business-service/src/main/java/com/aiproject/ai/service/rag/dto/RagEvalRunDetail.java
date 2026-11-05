package com.aiproject.ai.service.rag.dto;

import com.aiproject.ai.service.bean.RagEvalRunBean;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/** 一次运行的详情：整体指标 + 逐题结果 + 上一次可对比运行 */
@Data
public class RagEvalRunDetail {

    @ApiModelProperty("本次运行")
    private RagEvalRunBean run;

    @ApiModelProperty("逐题结果")
    private List<RagEvalCaseResultDto> results;

    @ApiModelProperty("同一数据集上更早的一次成功/部分成功运行，用于对比")
    private RagEvalRunBean baseline;
}
