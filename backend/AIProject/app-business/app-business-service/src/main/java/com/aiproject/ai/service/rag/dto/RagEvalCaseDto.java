package com.aiproject.ai.service.rag.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/** 返回给前端的用例（关键词已从 JSON 解析为数组） */
@Data
public class RagEvalCaseDto {

    @ApiModelProperty("用例 iid")
    private Long iid;

    @ApiModelProperty("问题")
    private String question;

    @ApiModelProperty("标准答案所在文档名")
    private String expectedDoc;

    @ApiModelProperty("必须命中的关键词")
    private List<String> keywords;

    @ApiModelProperty("题型")
    private String category;
}
