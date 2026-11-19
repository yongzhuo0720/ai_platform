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
/**
 * <p><b>归属字段约定（2026-11-19 加固，勿违反）</b>：本表没有独立 ownerUid，
 * 归属复用基类字段 {@code lastuser}。该字段<b>仅在分块创建时写入一次</b>，
 * 之后所有 update 操作只能把它放在 WHERE 条件里做归属过滤，
 * <b>绝不可出现在 update 的 SET 部分</b>（即禁止 {@code update.setLastuser(...)}），
 * 否则等于允许改写归属、产生跨用户越权。分块写入由 KbRagHolder / KbDocRunner 完成，已遵循此约定。</p>
 */
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

    @SqlColumn(method = "text")
    @ApiModelProperty("分块正文")
    String content;

    @SqlColumn(method = "text")
    @ApiModelProperty("向量（JSON 浮点数组文本）")
    String embedding;

    @SqlColumn()
    @ApiModelProperty("生成该向量的模型名（用于识别切换模型后的旧分块）")
    String embeddingModel;

    @SqlColumn()
    @ApiModelProperty("向量维度")
    Integer embeddingDim;

    @SqlColumn()
    @ApiModelProperty("分块字符数")
    Integer tokenCount;
}
