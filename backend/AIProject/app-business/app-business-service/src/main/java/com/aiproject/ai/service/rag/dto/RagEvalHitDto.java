package com.aiproject.ai.service.rag.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 单条 Top-K 命中明细。
 *
 * <p>只保留排查「为什么这题没召回」所需的字段：排名、文档、分块、
 * 三路分数与命中来源。正文只存预览而不是全文，避免评估明细表被
 * 知识库原文撑大；需要看全文时按 docName + chunkIndex 回知识库定位。</p>
 */
@Data
public class RagEvalHitDto {

    @ApiModelProperty("排名，从 1 开始")
    private Integer rank;

    @ApiModelProperty("文档名快照")
    private String docName;

    @ApiModelProperty("知识库名")
    private String kbName;

    @ApiModelProperty("分块序号")
    private Integer chunkIndex;

    @ApiModelProperty("融合排序分（RRF），仅用于排序，不是相似度")
    private Double score;

    @ApiModelProperty("向量路余弦分，-1 表示未参与向量路")
    private Double denseScore;

    @ApiModelProperty("关键词路词项重叠数，-1 表示未参与关键词路")
    private Integer lexicalScore;

    @ApiModelProperty("命中来源：both/dense/lexical")
    private String channel;

    @ApiModelProperty("分块正文预览（截断）")
    private String contentPreview;
}
