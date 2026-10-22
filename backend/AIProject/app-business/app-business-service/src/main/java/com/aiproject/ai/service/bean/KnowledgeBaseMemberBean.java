package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "知识库成员", description = "知识库成员关系")
public class KnowledgeBaseMemberBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty(value = "知识库ID")
    String kbId;

    @SqlColumn()
    @ApiModelProperty(value = "成员UID")
    String userId;
}
