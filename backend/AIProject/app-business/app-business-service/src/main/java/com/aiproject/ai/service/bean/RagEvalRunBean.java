package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 一次 RAG 评估运行的整体结果。
 *
 * <p>运行开始时快照数据集名与知识库名，之后编辑数据集不会改写历史；
 * {@code processed/total} 用于前端展示真实进度，而不是只转圈。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "RAG评估运行", description = "一次检索评估的整体指标")
public class RagEvalRunBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("运行业务键")
    String runKey;

    @SqlColumn()
    @ApiModelProperty("数据集业务键")
    String datasetKey;

    @SqlColumn()
    @ApiModelProperty("数据集名称快照")
    String datasetName;

    @SqlColumn()
    @ApiModelProperty("知识库名称快照")
    String kbName;

    @SqlColumn()
    @ApiModelProperty("截断位置 k")
    Integer topK;

    @SqlColumn()
    @ApiModelProperty("状态：queued/running/success/partial/failed")
    String status;

    @SqlColumn()
    @ApiModelProperty("用例总数")
    Integer total;

    @SqlColumn()
    @ApiModelProperty("已处理用例数")
    Integer processed;

    @SqlColumn()
    @ApiModelProperty("Recall@k")
    Double recall;

    @SqlColumn()
    @ApiModelProperty("Precision@k")
    Double precision;

    @SqlColumn()
    @ApiModelProperty("MRR")
    Double mrr;

    @SqlColumn()
    @ApiModelProperty("NDCG@k")
    Double ndcg;

    @SqlColumn()
    @ApiModelProperty("关键词命中率（仅在标注关键词的用例上计算）")
    Double keywordHitRate;

    @SqlColumn()
    @ApiModelProperty("完全漏召数量")
    Integer missCount;

    @SqlColumn()
    @ApiModelProperty("参与关键词统计的用例数")
    Integer keywordEvaluatedCount;

    @SqlColumn()
    @ApiModelProperty("本次检索使用的向量模型")
    String embeddingModel;

    @SqlColumn(method = "text")
    @ApiModelProperty("失败或降级说明")
    String errorMessage;

    @SqlColumn()
    @ApiModelProperty("开始时间（毫秒时间戳）")
    Long startedAt;

    @SqlColumn()
    @ApiModelProperty("结束时间（毫秒时间戳）")
    Long finishedAt;

    @SqlColumn()
    @ApiModelProperty("总耗时（毫秒）")
    Long durationMs;
}
