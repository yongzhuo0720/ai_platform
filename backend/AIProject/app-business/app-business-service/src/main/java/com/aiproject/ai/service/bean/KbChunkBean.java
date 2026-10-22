package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 本地知识库文档分块（RAG）。
 * <p>
 * embedding 以 JSON 浮点数组文本存储；相似度检索在服务端内存中做余弦计算，
 * 文档规模增大后可平滑迁移到 pgvector（向量列 + 索引）。
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "知识库文档分块", description = "本地RAG文档分块与向量")
public class KbChunkBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("所属文档iid")
    Long docIid;

    @SqlColumn()
    @ApiModelProperty("所属知识库名称")
    String kbName;

    @SqlColumn()
    @ApiModelProperty("块序号，从0开始")
    Integer chunkIndex;

    @SqlColumn()
    @ApiModelProperty("分块正文")
    String content;

    @SqlColumn()
    @ApiModelProperty("向量（JSON 浮点数组文本）")
    String embedding;

    @SqlColumn()
    @ApiModelProperty("分块字符数")
    Integer tokenCount;
}
