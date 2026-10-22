package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * AI 调用审计日志。
 * <p>
 * 只记录调用元数据（模块、端点、状态、tokens、耗时等），
 * 不记录用户提问与模型回答正文，避免把业务数据落进日志表。
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "AI调用日志", description = "AI网关调用审计日志")
public class AiCallLogBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("调用用户uid")
    String userId;

    @SqlColumn()
    @ApiModelProperty("模块：chat/knowledgeChat/file")
    String module;

    @SqlColumn()
    @ApiModelProperty("Dify 端点，如 chat-messages")
    String endpoint;

    @SqlColumn()
    @ApiModelProperty("HTTP 方法")
    String method;

    @SqlColumn()
    @ApiModelProperty("状态：success/failed/blocked/aborted")
    String status;

    @SqlColumn()
    @ApiModelProperty("上游或网关返回的 HTTP 状态码")
    Integer httpStatus;

    @SqlColumn()
    @ApiModelProperty("输入 tokens")
    Long promptTokens;

    @SqlColumn()
    @ApiModelProperty("输出 tokens")
    Long completionTokens;

    @SqlColumn()
    @ApiModelProperty("总 tokens")
    Long totalTokens;

    @SqlColumn()
    @ApiModelProperty("耗时毫秒")
    Long latencyMs;

    @SqlColumn()
    @ApiModelProperty("模型名称")
    String modelName;

    @SqlColumn()
    @ApiModelProperty("Dify 会话ID")
    String conversationId;

    @SqlColumn()
    @ApiModelProperty("Dify 消息ID")
    String messageId;

    @SqlColumn()
    @ApiModelProperty("失败原因（截断）")
    String errorMessage;

    @SqlColumn()
    @ApiModelProperty("客户端IP")
    String clientIp;
}
