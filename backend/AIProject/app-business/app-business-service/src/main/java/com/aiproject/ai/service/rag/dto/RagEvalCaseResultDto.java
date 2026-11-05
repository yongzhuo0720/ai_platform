package com.aiproject.ai.service.rag.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/** 逐题结果 */
@Data
public class RagEvalCaseResultDto {

    @ApiModelProperty("用例序号")
    private Integer caseIndex;

    @ApiModelProperty("问题")
    private String question;

    @ApiModelProperty("期望文档")
    private String expectedDoc;

    @ApiModelProperty("关键词")
    private List<String> keywords;

    @ApiModelProperty("题型")
    private String category;

    @ApiModelProperty("前 k 条命中文档（保留顺序与重复）")
    private List<String> retrievedDocs;

    @ApiModelProperty("首条命中文档")
    private String topDoc;

    @ApiModelProperty("期望文档首次出现的排名，0 表示未命中")
    private Integer firstHitRank;

    @ApiModelProperty("Recall@k")
    private Double recall;

    @ApiModelProperty("Precision@k")
    private Double precision;

    @ApiModelProperty("RR")
    private Double reciprocalRank;

    @ApiModelProperty("NDCG@k")
    private Double ndcg;

    @ApiModelProperty("关键词是否全部命中")
    private Boolean keywordHit;

    @ApiModelProperty("是否参与关键词统计")
    private Boolean keywordEvaluated;

    @ApiModelProperty("单题耗时（毫秒）")
    private Long durationMs;

    @ApiModelProperty("失败原因")
    private String errorMessage;
}
