package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.NotificationBean;
import com.aiproject.ai.service.mapper.NotificationBeanMapper;
import com.aiproject.ai.service.notification.NotificationTypes;
import com.aiproject.ai.service.notification.dto.NotificationListQuery;
import com.aiproject.ai.service.notification.dto.NotificationListResult;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知中心的单元测试。
 *
 * <p>重点验证四件事：写入必须归属于发起人且幂等；查询绝不允许跨用户；
 * 已读操作保持幂等；通知通道自身故障不能影响主任务。</p>
 */
@ExtendWith(MockitoExtension.class)
class NotificationHolderTest {

    private static final String UID = "u1";

    @Mock
    NotificationBeanMapper<NotificationBean> notificationMapper;

    @InjectMocks
    NotificationHolder holder;

    @Test
    void push_shouldPersistUnreadNotificationForOwner() {
        when(notificationMapper.install(any(NotificationBean.class))).thenReturn(1);
        when(notificationMapper.count(any(NotificationBean.class))).thenReturn(0);

        NotificationBean notice = notice("doc:100:ready", "文档处理完成", "《制度.txt》已完成处理");
        assertTrue(holder.push(UID, notice));

        ArgumentCaptor<NotificationBean> captor =
                ArgumentCaptor.forClass(NotificationBean.class);
        verify(notificationMapper).install(captor.capture());
        NotificationBean saved = captor.getValue();
        assertEquals(UID, saved.getLastuser());
        assertEquals("doc:100:ready", saved.getEventKey());
        assertEquals(NotificationTypes.CATEGORY_DOC, saved.getCategory());
        assertEquals(NotificationTypes.LEVEL_SUCCESS, saved.getLevel());
        assertEquals(NotificationTypes.READ_STATUS_UNREAD, saved.getReadStatus());
        assertEquals(Boolean.TRUE, saved.getEnable());
        assertEquals("/knowledge-bases", saved.getLink());
    }

    @Test
    void push_shouldRejectBlankTitleWithoutTouchingDatabase() {
        NotificationBean notice = notice("k1", "", "正文");

        assertFalse(holder.push(UID, notice));

        verify(notificationMapper, never()).install(any(NotificationBean.class));
    }

    @Test
    void push_shouldSwallowInstallFailure() {
        when(notificationMapper.install(any(NotificationBean.class)))
                .thenThrow(new RuntimeException("duplicate key"));

        assertFalse(holder.push(UID, notice("k1", "标题", "正文")));
    }

    @Test
    void push_shouldTrimOldestNotificationsWhenOverRetentionLimit() {
        when(notificationMapper.install(any(NotificationBean.class))).thenReturn(1);
        when(notificationMapper.count(any(NotificationBean.class))).thenReturn(201);
        NotificationBean oldest = notice("old", "旧通知", "内容");
        oldest.setIid(1L);
        when(notificationMapper.select(any(PageRequest.class), any(NotificationBean.class)))
                .thenReturn(Collections.singletonList(oldest));
        when(notificationMapper.delete(anyList())).thenReturn(1);

        assertTrue(holder.push(UID, notice("new", "新通知", "内容")));

        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(notificationMapper).select(pageCaptor.capture(), any(NotificationBean.class));
        assertEquals(Integer.valueOf(1), pageCaptor.getValue().getPageSize(),
                "只清理溢出的 1 条，不能把整页都删掉");
        assertEquals("asc", pageCaptor.getValue().getDesc(), "从最旧的开始删");
        verify(notificationMapper).delete(anyList());
    }

    @Test
    void list_shouldFilterByOwnerAndReturnUnreadCount() {
        NotificationBean row = notice("k1", "标题", "内容");
        row.setIid(9L);
        PageResult<NotificationBean> page = new PageResult<NotificationBean>();
        page.setContent(Collections.singletonList(row));
        page.setTotalSize(5);
        when(notificationMapper.selectPage(any(PageRequest.class),
                any(NotificationBean.class))).thenReturn(Result.succeed(page, "ok"));
        when(notificationMapper.count(any(NotificationBean.class))).thenReturn(2);

        NotificationListQuery query = new NotificationListQuery();
        query.setPageNum(2);
        query.setPageSize(999);
        query.setOnlyUnread(Boolean.TRUE);
        Result<NotificationListResult> result = holder.list(UID, query);

        assertEquals(0, result.getResp_code());
        assertEquals(1, result.getDatas().getItems().size());
        assertEquals(5, result.getDatas().getTotalSize());
        assertEquals(2, result.getDatas().getUnreadCount());
        assertEquals(2, result.getDatas().getPageNum());
        assertEquals(50, result.getDatas().getPageSize(), "页大小必须夹到 1-50");

        ArgumentCaptor<NotificationBean> whereCaptor =
                ArgumentCaptor.forClass(NotificationBean.class);
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(notificationMapper).selectPage(pageCaptor.capture(), whereCaptor.capture());
        assertEquals(UID, whereCaptor.getValue().getLastuser());
        assertEquals(NotificationTypes.READ_STATUS_UNREAD, whereCaptor.getValue().getReadStatus());
        assertEquals("iid", pageCaptor.getValue().getOrder());
        assertEquals("desc", pageCaptor.getValue().getDesc());
    }

    @Test
    void markRead_shouldBeIdempotentForOwnNotification() {
        when(notificationMapper.update(any(NotificationBean.class),
                any(NotificationBean.class))).thenReturn(0);
        when(notificationMapper.select(any(PageRequest.class), any(NotificationBean.class)))
                .thenReturn(Collections.singletonList(notice("k1", "标题", "内容")));

        Result<Boolean> result = holder.markRead(UID, 9L);

        assertEquals(0, result.getResp_code());
        assertTrue(result.getDatas(), "已读再次标记应保持幂等");
        ArgumentCaptor<NotificationBean> whereCaptor =
                ArgumentCaptor.forClass(NotificationBean.class);
        verify(notificationMapper).update(any(NotificationBean.class), whereCaptor.capture());
        assertEquals(UID, whereCaptor.getValue().getLastuser());
        assertEquals(NotificationTypes.READ_STATUS_UNREAD, whereCaptor.getValue().getReadStatus(),
                "只允许把未读改成已读，不能重置已读时间");
    }

    @Test
    void markRead_shouldRejectForeignOrMissingNotification() {
        when(notificationMapper.update(any(NotificationBean.class),
                any(NotificationBean.class))).thenReturn(0);
        when(notificationMapper.select(any(PageRequest.class), any(NotificationBean.class)))
                .thenReturn(new ArrayList<NotificationBean>());

        Result<Boolean> result = holder.markRead("u2", 9L);

        assertFalse(result.getResp_code() == 0, "别人的通知不能因为幂等就返回成功");
        assertEquals("通知不存在或不属于当前用户", result.getResp_msg());
    }

    @Test
    void markAllRead_shouldOnlyTouchCurrentUserUnreadRows() {
        when(notificationMapper.update(any(NotificationBean.class),
                any(NotificationBean.class))).thenReturn(3);

        Result<Integer> result = holder.markAllRead(UID);

        assertEquals(0, result.getResp_code());
        assertEquals(3, result.getDatas());
        ArgumentCaptor<NotificationBean> whereCaptor =
                ArgumentCaptor.forClass(NotificationBean.class);
        verify(notificationMapper).update(any(NotificationBean.class), whereCaptor.capture());
        assertEquals(UID, whereCaptor.getValue().getLastuser());
        assertEquals(NotificationTypes.READ_STATUS_UNREAD, whereCaptor.getValue().getReadStatus());
    }

    @Test
    void unreadCount_shouldDegradeToZeroOnDatabaseFailure() {
        when(notificationMapper.count(any(NotificationBean.class)))
                .thenThrow(new RuntimeException("db down"));

        Result<Integer> result = holder.unreadCount(UID);

        assertEquals(0, result.getResp_code());
        assertEquals(0, result.getDatas(), "角标查询失败不能把页面打成错误页");
    }

    private static NotificationBean notice(String eventKey, String title, String content) {
        NotificationBean notice = new NotificationBean();
        notice.setEventKey(eventKey);
        notice.setCategory(NotificationTypes.CATEGORY_DOC);
        notice.setLevel(NotificationTypes.LEVEL_SUCCESS);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setLink("/knowledge-bases");
        return notice;
    }
}
