package com.aiproject.ai.service.notification.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/** 通知列表查询参数 */
@Data
public class NotificationListQuery {

    @ApiModelProperty("页码，从 1 开始，默认 1")
    private Integer pageNum;

    @ApiModelProperty("每页条数，1-50，默认 10")
    private Integer pageSize;

    @ApiModelProperty("只看未读")
    private Boolean onlyUnread;
}
