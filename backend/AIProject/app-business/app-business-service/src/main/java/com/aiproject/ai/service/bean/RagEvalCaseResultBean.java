package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 评估运行中的单题结果，用于失败分析与历史回溯。
 *
 * <p>保存问题、期望文档、命中文档列表与各项指标的快照：数据集后来被编辑或删除，
 * 这条历史记录仍然自洽，不会出现「报告还在、题目已经被改了」的错位。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "RAG评估逐题结果", description = "单条检索评估明细")
public class RagEvalCaseResultBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("所属运行业务键")
    String runKey;

    @SqlColumn()
    @ApiModelProperty("用例序号，从 0 开始")
    Integer caseIndex;

    @SqlColumn(method = "text")
    @ApiModelProperty("问题快照")
    String question;

    @SqlColumn()
    @ApiModelProperty("期望文档快照")
    String expectedDoc;

    @SqlColumn(method = "text")
    @ApiModelProperty("关键词快照（JSON 数组）")
    String expectedKeywords;

    @SqlColumn()
    @ApiModelProperty("题型快照")
    String category;

    @SqlColumn(method = "text")
    @ApiModelProperty("前 k 条命中文档名（JSON 数组，保留重复与顺序）")
    String retrievedDocs;

    @SqlColumn()
    @ApiModelProperty("首条命中文档名")
    String topDoc;

    @SqlColumn()
    @ApiModelProperty("期望文档首次出现的排名，0 表示未命中")
    Integer firstHitRank;

    @SqlColumn()
    @ApiModelProperty("Recall@k")
    Double recall;

    @SqlColumn()
    @ApiModelProperty("Precision@k")
    Double precision;

    @SqlColumn()
    @ApiModelProperty("倒数排名 RR")
    Double reciprocalRank;

    @SqlColumn()
    @ApiModelProperty("NDCG@k")
    Double ndcg;

    @SqlColumn()
    @ApiModelProperty("关键词是否全部出现在期望文档的命中分块中")
    Boolean keywordHit;

    @SqlColumn()
    @ApiModelProperty("该用例是否标注了关键词")
    Boolean keywordEvaluated;

    @SqlColumn()
    @ApiModelProperty("单题检索耗时（毫秒）")
    Long durationMs;

    @SqlColumn(method = "text")
    @ApiModelProperty("检索失败原因")
    String errorMessage;
}
