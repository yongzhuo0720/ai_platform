package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 一次 Agent 运行里的单个步骤，用于「创建 → 执行 → 追踪 → 分析」闭环的追踪层。
 *
 * <p>{@code stepType} 区分 llm / tool / tool_result / retry / error / finish，
 * 前端时间线按 {@code stepNo} 顺序渲染。工具入参与结果都做截断后落库，
 * 避免把大段知识库正文写进步骤表。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "Agent步骤", description = "Agent 执行步骤明细")
public class AgentStepBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("所属运行业务键")
    String runKey;

    @SqlColumn()
    @ApiModelProperty("所属运行 iid")
    Long runIid;

    @SqlColumn()
    @ApiModelProperty("步骤序号，从 1 起")
    Integer stepNo;

    @SqlColumn()
    @ApiModelProperty("第几轮模型调用")
    Integer iteration;

    @SqlColumn()
    @ApiModelProperty("llm / tool / tool_result / retry / error / finish")
    String stepType;

    @SqlColumn()
    @ApiModelProperty("工具名")
    String toolName;

    @SqlColumn(method = "text")
    @ApiModelProperty("工具入参（截断）")
    String toolArgs;

    @SqlColumn(method = "text")
    @ApiModelProperty("工具结果（截断）")
    String toolResult;

    @SqlColumn()
    @ApiModelProperty("步骤状态")
    String status;

    @SqlColumn()
    @ApiModelProperty("本步提示词 Token")
    Long promptTokens;

    @SqlColumn()
    @ApiModelProperty("本步完成 Token")
    Long completionTokens;

    @SqlColumn()
    @ApiModelProperty("本步成本（毫分）")
    Long costCents;

    @SqlColumn()
    @ApiModelProperty("本步耗时（毫秒）")
    Long latencyMs;

    @SqlColumn(method = "text")
    @ApiModelProperty("错误原因")
    String errorMessage;

    @SqlColumn()
    @ApiModelProperty("链路请求 ID")
    String requestId;

    @SqlColumn()
    @ApiModelProperty("关联 aicalllogbean.iid，便于成本对账")
    Long callLogIid;
}
