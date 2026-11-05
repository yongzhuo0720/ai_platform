package com.aiproject.ai.service.rag.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/** 数据集编辑时提交的单条用例 */
@Data
public class RagEvalCaseInput {

    @ApiModelProperty("问题")
    private String question;

    @ApiModelProperty("标准答案所在文档名")
    private String expectedDoc;

    @ApiModelProperty("必须命中的关键词")
    private List<String> keywords;

    @ApiModelProperty("题型")
    private String category;
}
