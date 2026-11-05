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
    @ApiModelProperty("状态：queued/chunking/embedding/ready/failed")
    String status;

    @SqlColumn()
    @ApiModelProperty("文档标识（异步任务定位用，不对外暴露）")
    String docKey;

    @SqlColumn()
    @ApiModelProperty("正文 SHA-256，用于同库去重")
    String contentHash;

    @SqlColumn()
    @ApiModelProperty("文件类型：txt/md/pdf/docx 等")
    String fileType;

    @SqlColumn()
    @ApiModelProperty("原始文件字节数")
    Long sizeBytes;

    @SqlColumn()
    @ApiModelProperty("处理进度 0-100")
    Integer progress;

    @SqlColumn(method = "text")
    @ApiModelProperty("失败原因（仅失败态有值）")
    String errorMsg;

    @SqlColumn()
    @ApiModelProperty("已尝试次数（含首次处理）")
    Integer attemptCount;

    @SqlColumn()
    @ApiModelProperty("开始处理时间（毫秒时间戳，兼作排队时间）")
    Long startedAt;

    @SqlColumn()
    @ApiModelProperty("处理结束时间（毫秒时间戳）")
    Long finishedAt;

    @SqlColumn()
    @ApiModelProperty("生成向量的模型名")
    String embeddingModel;
}
