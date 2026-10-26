package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlColumn;
import io.swagger.annotations.ApiModelProperty;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import lombok.Data;

/**
 * 云端会话：直连模型（OpenAI 兼容）的会话持久化。
 *
 * <p>直连协议无状态，会话原本只存浏览器 localStorage——换设备/清缓存即丢。
 * 这张表把整个会话（含消息与思考过程）以 JSON 快照按 (userId, convId) upsert，
 * 前端登录后从云端拉取，实现多端同步。</p>
 */
@Data
/** 审计字段（iid/lastuser/createtime/updatetime/enable/app）继承自 DbEntityBase，由 @HnMapper 映射同名列 */
public class ChatConversationBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("会话 ID（前端生成的客户端 ID）")
    String convId;

    @SqlColumn()
    @ApiModelProperty("所属用户")
    String userId;

    @SqlColumn()
    @ApiModelProperty("会话标题")
    String title;

    @SqlColumn()
    @ApiModelProperty("模型显示名")
    String modelName;

    @SqlColumn()
    @ApiModelProperty("是否置顶")
    Boolean pinned;

    @SqlColumn()
    @ApiModelProperty("会话内容 JSON 快照（消息、思考过程、引用来源）")
    String payload;

    @SqlColumn()
    @ApiModelProperty("更新时间（毫秒时间戳，客户端时钟，用于排序）")
    Long clientUpdatedAt;

    @SqlColumn()
    @ApiModelProperty("应用标识")
    String app;
}
