package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * RAG 评估用例：问题 + 标准答案所在文档 + 必须命中的关键词。
 *
 * <p>评估口径是文档级：前 k 条结果里出现 {@code expectedDoc} 即算召回命中；
 * 关键词用于进一步检查命中的分块是否真的是正确段落。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "RAG评估用例", description = "单条检索评估问题")
public class RagEvalCaseBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("所属数据集业务键")
    String datasetKey;

    @SqlColumn()
    @ApiModelProperty("所属用例集版本键")
    String caseSetKey;

    @SqlColumn(method = "text")
    @ApiModelProperty("问题")
    String question;

    @SqlColumn()
    @ApiModelProperty("标准答案所在文档名")
    String expectedDoc;

    @SqlColumn(method = "text")
    @ApiModelProperty("必须命中的关键词（JSON 数组）")
    String expectedKeywords;

    @SqlColumn()
    @ApiModelProperty("题型")
    String category;
}
