package com.aiproject.ai.service.notification.dto;

import com.aiproject.ai.service.bean.NotificationBean;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 通知列表 + 未读数：铃铛角标和面板一次拿到，避免两个接口的时序差。 */
@Data
public class NotificationListResult {

    private List<NotificationBean> items = new ArrayList<NotificationBean>();
    private int unreadCount;
    private int totalSize;
    private int pageNum;
    private int pageSize;
}
