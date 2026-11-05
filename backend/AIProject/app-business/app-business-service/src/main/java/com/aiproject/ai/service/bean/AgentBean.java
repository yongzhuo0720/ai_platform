package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * Agent 配置：一个 Agent = 一段 System Prompt + 一个模型 + 一组工具 + 执行护栏。
 *
 * <p>{@code agentKey} 是服务端生成的稳定业务键。HNSP 的 install 不回填自增 iid，
 * 用业务键关联运行记录，避免「插入后按名称反查最新一行」在并发下认错行。</p>
 *
 * <p>本类只负责配置持久化；执行循环、工具调用与 SSE 步骤事件在后续子阶段实现，
 * 这样数据层可以先独立验收，不会把编排逻辑和表结构耦合在一起。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "Agent配置", description = "Agent 工作台配置")
public class AgentBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("业务键，服务端生成")
    String agentKey;

    @SqlColumn()
    @ApiModelProperty("Agent 名称")
    String name;

    @SqlColumn(method = "text")
    @ApiModelProperty("说明")
    String description;

    @SqlColumn(method = "text")
    @ApiModelProperty("System Prompt")
    String systemPrompt;

    @SqlColumn()
    @ApiModelProperty("关联的直连模型 iid（modelsconfigbean）")
    Long modelIid;

    @SqlColumn()
    @ApiModelProperty("单次运行最大迭代步数")
    Integer maxIterations;

    @SqlColumn()
    @ApiModelProperty("单次运行 Token 预算")
    Long tokenBudget;

    @SqlColumn()
    @ApiModelProperty("单工具超时（毫秒）")
    Long toolTimeoutMs;

    @SqlColumn(method = "text")
    @ApiModelProperty("不可见角色 id 组（JSON 数组）")
    String roleiids;

    @SqlColumn(method = "text")
    @ApiModelProperty("不可见部门编号组（JSON 数组）")
    String unitsnos;

    @SqlColumn()
    @ApiModelProperty("可见性：public / only_me / partial_invisible")
    String permissionType;
}
