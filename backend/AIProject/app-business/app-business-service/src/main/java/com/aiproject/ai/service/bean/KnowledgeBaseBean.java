package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "知识库权限", description = "知识库权限")
public class KnowledgeBaseBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty(value = "Dify知识库ID")
    String kbId;

    @SqlColumn()
    @ApiModelProperty(value = "所有者UID")
    String ownerUid;

    @SqlColumn()
    @ApiModelProperty(value = "权限类型: public/only_me/partial_invisible")
    String permissionType;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "部门拉黑列表(JSON)")
    String unitsnos;

    @SqlColumn(method = "text")
    @ApiModelProperty(value = "角色拉黑列表(JSON)")
    String roleiids;

    @SqlColumn()
    @ApiModelProperty(value = "本系统别名/备注（独立于Dify名称）")
    String name;
}
