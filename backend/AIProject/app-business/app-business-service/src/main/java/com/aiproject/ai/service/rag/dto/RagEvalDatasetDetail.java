package com.aiproject.ai.service.rag.dto;

import com.aiproject.ai.service.bean.RagEvalDatasetBean;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/** 数据集详情：元信息 + 当前生效的用例集 */
@Data
public class RagEvalDatasetDetail {

    @ApiModelProperty("数据集元信息")
    private RagEvalDatasetBean dataset;

    @ApiModelProperty("用例列表")
    private List<RagEvalCaseDto> cases;
}
