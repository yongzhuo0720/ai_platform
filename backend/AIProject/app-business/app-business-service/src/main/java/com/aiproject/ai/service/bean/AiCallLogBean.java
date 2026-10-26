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

    /**
     * 本次调用折算的成本，单位「毫分」（1 分 = 1000 毫分）。
     *
     * <p>由 promptTokens / completionTokens 乘模型单价得出。
     * 模型未配置单价时为 0——治理看板据此提示「未配置价格」，
     * 而不是把 0 元当成真实的免费。</p>
     *
     * <p>存毫分而不是分：单次对话常不足 1 分（几百 token ×
     * 分/百万 级单价），用分会把小调用全部下取整成 0；
     * 前端展示时再折算成元。</p>
     */
    @SqlColumn()
    @ApiModelProperty("调用成本（分）")
    Long costCents;

    /**
     * 首字耗时 TTFT（毫秒）：从网关收到请求到写出第一块流式响应的间隔。
     *
     * <p>总耗时衡量整体吞吐，TTFT 衡量「用户等多久看到第一个字」——
     * 流式对话体验的核心指标，两者不可互相替代。仅流式调用有值，
     * 非流式 JSON 响应为 null。</p>
     */
    @SqlColumn()
    @ApiModelProperty("首字耗时（毫秒，仅流式）")
    Long firstTokenMs;

    /**
     * 全链路请求 ID。
     *
     * <p>浏览器生成后随请求头下发，网关透传给上游；审计日志落库。
     * 排查「某次回答为什么不对」时，用这一个 ID 能把
     * 浏览器控制台、网关日志、上游调用记录串成一条线——
     * 没有它就只能靠时间戳猜。</p>
     */
    @SqlColumn()
    @ApiModelProperty("全链路请求ID")
    String requestId;
}
