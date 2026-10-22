package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 本地知识库文档（RAG）：解析入库的原文档元信息，正文切块存 kbchunkbean。
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "知识库文档", description = "本地RAG文档")
public class KbDocBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("所属知识库名称")
    String kbName;

    @SqlColumn()
    @ApiModelProperty("文档名称")
    String name;

    @SqlColumn()
    @ApiModelProperty("原始字符数")
    Long charCount;

    @SqlColumn()
    @ApiModelProperty("分块数量")
    Integer chunkCount;

    @SqlColumn()
    @ApiModelProperty("状态：ready/pending")
    String status;
}
