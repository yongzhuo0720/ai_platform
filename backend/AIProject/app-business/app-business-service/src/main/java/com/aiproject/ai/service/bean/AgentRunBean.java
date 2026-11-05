package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 一次 Agent 运行的汇总记录。
 *
 * <p>Token 与成本按 {@code runKey} 归集：后续执行循环会在每次模型调用时
 * 把 {@code runKey} 写进 {@code aicalllogbean}，从而复用现有的
 * {@code ModelPriceResolver} 与治理看板，不另起一套计价口径。</p>
 *
 * <p>本子阶段只建结构与只读查询，写入交给执行循环（子阶段 2）。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "Agent运行", description = "Agent 一次执行的汇总")
public class AgentRunBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("运行业务键")
    String runKey;

    @SqlColumn()
    @ApiModelProperty("所属 Agent 的 iid")
    Long agentIid;

    @SqlColumn()
    @ApiModelProperty("状态：queued/running/succeeded/failed/aborted")
    String status;

    @SqlColumn(method = "text")
    @ApiModelProperty("本轮用户输入")
    String question;

    @SqlColumn()
    @ApiModelProperty("实际迭代步数")
    Integer iterations;

    @SqlColumn()
    @ApiModelProperty("提示词 Token")
    Long promptTokens;

    @SqlColumn()
    @ApiModelProperty("完成 Token")
    Long completionTokens;

    @SqlColumn()
    @ApiModelProperty("总 Token")
    Long totalTokens;

    @SqlColumn()
    @ApiModelProperty("成本（毫分，与 ModelPriceResolver 口径一致）")
    Long costCents;

    @SqlColumn(method = "text")
    @ApiModelProperty("失败原因")
    String errorMessage;

    @SqlColumn()
    @ApiModelProperty("链路请求 ID")
    String requestId;

    @SqlColumn()
    @ApiModelProperty("开始时间（毫秒时间戳）")
    Long startedAt;

    @SqlColumn()
    @ApiModelProperty("结束时间（毫秒时间戳）")
    Long finishedAt;

    @SqlColumn()
    @ApiModelProperty("总耗时（毫秒）")
    Long durationMs;

    @SqlColumn(method = "text")
    @ApiModelProperty("最终回答")
    String answer;
}
