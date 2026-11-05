package com.aiproject.ai.service.rag.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 新建 / 编辑评估数据集的请求。
 * <p>新建时 {@code datasetKey} 为空；编辑时必填。</p>
 */
@Data
public class RagEvalDatasetRequest {

    @ApiModelProperty("数据集业务键（编辑时必填）")
    private String datasetKey;

    @ApiModelProperty("数据集名称")
    private String name;

    @ApiModelProperty("知识库名称")
    private String kbName;

    @ApiModelProperty("数据集说明")
    private String description;

    @ApiModelProperty("用例列表")
    private List<RagEvalCaseInput> cases;
}
