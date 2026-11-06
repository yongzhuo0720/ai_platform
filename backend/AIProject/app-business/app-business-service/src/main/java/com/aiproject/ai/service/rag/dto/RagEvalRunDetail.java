package com.aiproject.ai.service.rag.dto;

import com.aiproject.ai.service.bean.RagEvalRunBean;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/** 一次运行的详情：整体指标 + 逐题结果 + 对比基线 */
@Data
public class RagEvalRunDetail {

    @ApiModelProperty("本次运行")
    private RagEvalRunBean run;

    @ApiModelProperty("逐题结果")
    private List<RagEvalCaseResultDto> results;

    @ApiModelProperty("对比基线：手动指定或自动选择的一次成功/部分成功运行")
    private RagEvalRunBean baseline;

    @ApiModelProperty("基线是否为用户手动指定")
    private Boolean baselineExplicit;

    @ApiModelProperty("两次运行是否严格可比：用例集、TopK、指标口径一致且版本已知")
    private Boolean baselineCompatible;

    @ApiModelProperty("不可比或需注意的原因")
    private List<String> baselineWarnings;

    @ApiModelProperty("逐题差异（仅在两次运行用例集版本一致时计算）")
    private List<RagEvalCaseDiffDto> caseDiffs;

    @ApiModelProperty("包含退化项的题目数（mixed 同时计入退化与提升）")
    private Integer regressedCount;

    @ApiModelProperty("包含提升项的题目数（mixed 同时计入退化与提升）")
    private Integer improvedCount;
}
