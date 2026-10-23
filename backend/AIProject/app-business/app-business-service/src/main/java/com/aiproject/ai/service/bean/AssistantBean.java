package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "助手", description = "自定义助手配置（多智能体统一管理）")
public class AssistantBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty(value = "助手名称")
    String name;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "提示词")
    String prompt;

    @SqlColumn()
    @ApiModelProperty(value = "用户ID")
    String userId;

    @SqlColumn()
    @ApiModelProperty(value = "智能体服务类型（如 qwen_paw、wanwu 等，预留扩展）")
    String type;

    @SqlColumn()
    @ApiModelProperty(value = "智能体 ID（对应接口 Header 中的 X-Agent-Id）")
    String agentId;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "接口地址（智能体服务 API 基础 URL）")
    String apiUrl;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "认证 Token（Authorization Bearer）")
    String authToken;

    @SqlColumn()
    @ApiModelProperty(value = "调用渠道（如 console 等）")
    String channel;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "权限类型: public=所有人可见 / partial_invisible=部分不可见")
    String permissionType;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "不可见角色 id 组（JSON 数组）")
    String roleiids;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "不可见部门编号组（JSON 数组）")
    String unitsnos;
}
