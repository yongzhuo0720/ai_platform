package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlColumn;
import io.swagger.annotations.ApiModelProperty;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import lombok.Data;

/**
 * 对话分享快照：把一段会话冻结为只读页面，凭 token 访问。
 *
 * <p>快照而非引用：分享后原会话继续对话、删除都不影响已分享内容。
 * token 用随机 UUID，足够不可猜测；需要更强安全性时再加过期时间。</p>
 */
@Data
/** 审计字段（iid/lastuser/createtime/updatetime/enable/app）继承自 DbEntityBase，由 @HnMapper 映射同名列 */
public class ChatShareBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("分享 token（访问凭据）")
    String shareToken;

    @SqlColumn()
    @ApiModelProperty("分享标题")
    String title;

    @SqlColumn()
    @ApiModelProperty("会话内容 JSON 快照（消息数组）")
    String payload;

    @SqlColumn()
    @ApiModelProperty("创建人用户 ID")
    String userId;

    @SqlColumn()
    @ApiModelProperty("创建人显示名")
    String authorName;

    @SqlColumn()
    @ApiModelProperty("应用标识")
    String app;
}
