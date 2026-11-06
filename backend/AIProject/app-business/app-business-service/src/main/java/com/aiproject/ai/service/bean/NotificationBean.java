package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 站内通知：后台异步任务结束后写给发起人的一条消息。
 *
 * <p>{@code eventKey} 是幂等键：唯一索引兜住「任务重复收尾 / 恢复任务与
 * 正常收尾并发」造成的重复通知。通知写入失败只记日志，不能让已经成功的
 * 文档处理或评估运行变成失败。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "站内通知", description = "后台异步任务的完成与失败提醒")
public class NotificationBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("幂等键，如 doc:12:ready")
    String eventKey;

    @SqlColumn()
    @ApiModelProperty("来源类别：doc / rag-eval")
    String category;

    @SqlColumn()
    @ApiModelProperty("级别：success / warning / error / info")
    String level;

    @SqlColumn()
    @ApiModelProperty("标题")
    String title;

    @SqlColumn(method = "text")
    @ApiModelProperty("正文")
    String content;

    @SqlColumn()
    @ApiModelProperty("站内跳转地址")
    String link;

    @SqlColumn()
    @ApiModelProperty("已读状态：unread / read")
    String readStatus;

    @SqlColumn()
    @ApiModelProperty("首次已读时间（毫秒时间戳）")
    Long readAt;
}
