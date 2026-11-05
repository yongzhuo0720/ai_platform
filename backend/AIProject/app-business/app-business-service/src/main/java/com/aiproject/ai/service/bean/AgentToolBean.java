package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * Agent 可调用工具的定义与参数 Schema。
 *
 * <p>{@code jsonSchema} 是工具入参的 JSON Schema，执行器在调用前必须先校验，
 * 不能把模型生成的参数直接透传给工具——这是后续子阶段工具权限与安全防护的基础。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "Agent工具", description = "Agent 工具注册")
public class AgentToolBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("所属 Agent 的 iid")
    Long agentIid;

    @SqlColumn()
    @ApiModelProperty("工具名，模型侧 function.name")
    String name;

    @SqlColumn(method = "text")
    @ApiModelProperty("工具说明，会随 tools 一起下发给模型")
    String description;

    @SqlColumn(method = "text")
    @ApiModelProperty("入参 JSON Schema")
    String jsonSchema;

    @SqlColumn()
    @ApiModelProperty("工具类型：kb_search / http / builtin")
    String toolType;

    @SqlColumn(method = "text")
    @ApiModelProperty("HTTP 类工具的目标地址（白名单收敛）")
    String endpoint;

    @SqlColumn()
    @ApiModelProperty("单次执行超时（毫秒）")
    Integer timeoutMs;

    @SqlColumn()
    @ApiModelProperty("单步失败重试次数上限")
    Integer maxRetries;

    @SqlColumn(method = "text")
    @ApiModelProperty("允许使用的角色 id 组（JSON 数组）")
    String roleiids;

    @SqlColumn(method = "text")
    @ApiModelProperty("允许使用的部门编号组（JSON 数组）")
    String unitsnos;
}
