package com.aiproject.ai.service.rag.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 一次运行相对于基线的逐题差异。
 *
 * <p>只比较同一用例集内的题目：用例集版本不同时后端不计算差异，
 * 避免把不同题目之间的分数当成「退化」。</p>
 */
@Data
public class RagEvalCaseDiffDto {

    @ApiModelProperty("用例序号（同一用例集内稳定）")
    private Integer caseIndex;

    @ApiModelProperty("问题")
    private String question;

    @ApiModelProperty("基线 Recall")
    private Double baselineRecall;

    @ApiModelProperty("本次 Recall")
    private Double currentRecall;

    @ApiModelProperty("Recall 差值（本次 - 基线）")
    private Double recallDelta;

    @ApiModelProperty("基线首位命中排名，0 表示前 K 条未命中")
    private Integer baselineFirstHitRank;

    @ApiModelProperty("本次首位命中排名，0 表示前 K 条未命中")
    private Integer currentFirstHitRank;

    @ApiModelProperty("基线关键词是否命中，null 表示未标注关键词")
    private Boolean baselineKeywordHit;

    @ApiModelProperty("本次关键词是否命中，null 表示未标注关键词")
    private Boolean currentKeywordHit;

    @ApiModelProperty("对比状态：improved/regressed/mixed/unchanged/unknown")
    private String status;

    @ApiModelProperty("变化说明")
    private String note;
}
