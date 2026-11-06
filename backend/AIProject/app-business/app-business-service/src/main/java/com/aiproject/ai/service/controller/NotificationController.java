package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.holde.NotificationHolder;
import com.aiproject.ai.service.notification.dto.NotificationListQuery;
import com.aiproject.ai.service.notification.dto.NotificationListResult;
import com.aiproject.ai.service.notification.dto.NotificationReadRequest;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知：后台异步任务结束后由系统写入，当前用户读取。
 *
 * <p>归属校验全部在 Holder 内完成，Controller 只负责取登录用户。</p>
 */
@RestController
@RequestMapping("/api/notification")
@Api(value = "站内通知", tags = "站内通知")
public class NotificationController {

    @Autowired
    NotificationHolder notificationHolder;

    @ApiOperation(value = "通知列表", httpMethod = "POST", notes = "含未读总数，按时间倒序")
    @PostMapping("/list")
    public Result<NotificationListResult> list(@LoginUserId String uid,
                                               @RequestBody(required = false) NotificationListQuery query) {
        return notificationHolder.list(uid, query);
    }

    @ApiOperation(value = "未读数", httpMethod = "POST")
    @PostMapping("/unreadCount")
    public Result<Integer> unreadCount(@LoginUserId String uid) {
        return notificationHolder.unreadCount(uid);
    }

    @ApiOperation(value = "标记单条已读", httpMethod = "POST")
    @PostMapping("/markRead")
    public Result<Boolean> markRead(@LoginUserId String uid,
                                    @RequestBody(required = false) NotificationReadRequest data) {
        return notificationHolder.markRead(uid, data == null ? null : data.getIid());
    }

    @ApiOperation(value = "全部标记已读", httpMethod = "POST")
    @PostMapping("/markAllRead")
    public Result<Integer> markAllRead(@LoginUserId String uid) {
        return notificationHolder.markAllRead(uid);
    }
}
