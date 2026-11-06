package com.aiproject.ai.service.notification.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/** 标记单条通知已读 */
@Data
public class NotificationReadRequest {

    @ApiModelProperty("通知 iid")
    private Long iid;
}
