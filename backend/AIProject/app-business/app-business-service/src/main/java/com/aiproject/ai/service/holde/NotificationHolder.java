package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.NotificationBean;
import com.aiproject.ai.service.mapper.NotificationBeanMapper;
import com.aiproject.ai.service.notification.NotificationTypes;
import com.aiproject.ai.service.notification.dto.NotificationListQuery;
import com.aiproject.ai.service.notification.dto.NotificationListResult;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 站内通知的读写入口。
 *
 * <p>通知是异步任务的「售后」：文档处理、RAG 评估都要跑几十秒到几分钟，
 * 用户关掉页面后没有别的渠道知道结果。这里的写入口 {@link #push} 刻意不抛异常，
 * 因为通知失败不应该让已经成功的主任务回滚或报错。</p>
 *
 * <p>权限模型与评估工作台一致：每条通知带 {@code lastuser}，
 * 查询、已读、全部已读都强制带当前用户条件，没有跨用户入口。</p>
 */
@Slf4j
@Service
public class NotificationHolder {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    /** 每人最多保留的通知条数，超出后从最旧的开始清理，避免表无限增长 */
    private static final int MAX_PER_USER = 200;
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_CONTENT_CHARS = 2000;
    private static final int MAX_LINK_CHARS = 255;
    private static final int MAX_EVENT_KEY_CHARS = 128;

    @Autowired
    NotificationBeanMapper<NotificationBean> notificationMapper;

    /**
     * 幂等写入一条通知。
     *
     * @return true 表示写入成功；重复事件（eventKey 冲突）、参数缺失或任何异常都返回 false
     */
    public boolean push(String uid, NotificationBean notice) {
        if (notLoggedIn(uid) || notice == null) {
            return false;
        }
        String title = limit(trim(notice.getTitle()), MAX_TITLE_CHARS);
        if (title.isEmpty()) {
            return false;
        }
        try {
            NotificationBean row = new NotificationBean();
            String eventKey = limit(trim(notice.getEventKey()), MAX_EVENT_KEY_CHARS);
            row.setEventKey(eventKey.isEmpty()
                    ? UUID.randomUUID().toString().replace("-", "") : eventKey);
            row.setCategory(defaultIfBlank(notice.getCategory(),
                    NotificationTypes.CATEGORY_SYSTEM));
            row.setLevel(normalizeLevel(notice.getLevel()));
            row.setTitle(title);
            row.setContent(limit(trim(notice.getContent()), MAX_CONTENT_CHARS));
            row.setLink(limit(trim(notice.getLink()), MAX_LINK_CHARS));
            row.setReadStatus(NotificationTypes.READ_STATUS_UNREAD);
            row.setLastuser(uid);
            row.setEnable(Boolean.TRUE);
            if (notificationMapper.install(row) <= 0) {
                return false;
            }
            trimOverflow(uid);
            return true;
        } catch (Exception e) {
            // eventKey 唯一索引冲突是正常幂等路径；其他异常也不能影响主任务
            log.warn("[Notification] 写入通知失败, uid={}, eventKey={}: {}",
                    uid, notice.getEventKey(), e.getMessage());
            return false;
        }
    }

    public Result<NotificationListResult> list(String uid, NotificationListQuery query) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        int pageNum = query != null && query.getPageNum() != null && query.getPageNum() > 0
                ? query.getPageNum() : 1;
        int pageSize = query != null && query.getPageSize() != null
                ? query.getPageSize() : DEFAULT_PAGE_SIZE;
        pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        boolean onlyUnread = query != null && Boolean.TRUE.equals(query.getOnlyUnread());

        try {
            NotificationBean where = new NotificationBean();
            where.setLastuser(uid);
            if (onlyUnread) {
                where.setReadStatus(NotificationTypes.READ_STATUS_UNREAD);
            }
            PageRequest page = new PageRequest(pageNum, pageSize);
            page.setOrder("iid");
            page.setDesc("desc");
            Result<PageResult<NotificationBean>> pageResult =
                    notificationMapper.selectPage(page, where);
            if (pageResult == null || pageResult.getDatas() == null) {
                return Result.failed(null, "查询通知失败，请稍后重试");
            }
            PageResult<NotificationBean> pageData = pageResult.getDatas();
            NotificationListResult data = new NotificationListResult();
            data.setItems(pageData.getContent() == null
                    ? new ArrayList<NotificationBean>() : pageData.getContent());
            data.setTotalSize(pageData.getTotalSize() == null ? 0 : pageData.getTotalSize());
            data.setPageNum(pageNum);
            data.setPageSize(pageSize);
            data.setUnreadCount(countUnread(uid));
            return Result.succeed(data, "查询成功");
        } catch (Exception e) {
            log.error("[Notification] 查询通知失败, uid={}: {}", uid, e.getMessage(), e);
            return Result.failed(null, "查询通知失败，请稍后重试");
        }
    }

    public Result<Integer> unreadCount(String uid) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        return Result.succeed(countUnread(uid), "查询成功");
    }

    public Result<Boolean> markRead(String uid, Long iid) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (iid == null || iid <= 0) {
            return Result.failed(null, "通知标识不能为空");
        }
        try {
            NotificationBean patch = new NotificationBean();
            patch.setReadStatus(NotificationTypes.READ_STATUS_READ);
            patch.setReadAt(System.currentTimeMillis());
            NotificationBean where = new NotificationBean();
            where.setIid(iid);
            where.setLastuser(uid);
            where.setReadStatus(NotificationTypes.READ_STATUS_UNREAD);
            if (notificationMapper.update(patch, where) > 0) {
                return Result.succeed(Boolean.TRUE, "已读");
            }
            // 已经是已读状态时保持幂等；但必须确认这条通知确实属于当前用户
            NotificationBean existsWhere = new NotificationBean();
            existsWhere.setIid(iid);
            existsWhere.setLastuser(uid);
            List<NotificationBean> exists = notificationMapper.select(
                    new PageRequest(1, 1), existsWhere);
            if (exists != null && !exists.isEmpty()) {
                return Result.succeed(Boolean.TRUE, "已读");
            }
            return Result.failed(null, "通知不存在或不属于当前用户");
        } catch (Exception e) {
            log.error("[Notification] 标记已读失败, uid={}, iid={}: {}", uid, iid,
                    e.getMessage(), e);
            return Result.failed(null, "标记已读失败，请稍后重试");
        }
    }

    public Result<Integer> markAllRead(String uid) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        try {
            NotificationBean patch = new NotificationBean();
            patch.setReadStatus(NotificationTypes.READ_STATUS_READ);
            patch.setReadAt(System.currentTimeMillis());
            NotificationBean where = new NotificationBean();
            where.setLastuser(uid);
            where.setReadStatus(NotificationTypes.READ_STATUS_UNREAD);
            int rows = notificationMapper.update(patch, where);
            return Result.succeed(Math.max(0, rows), "已全部标记为已读");
        } catch (Exception e) {
            log.error("[Notification] 全部已读失败, uid={}: {}", uid, e.getMessage(), e);
            return Result.failed(null, "操作失败，请稍后重试");
        }
    }

    private int countUnread(String uid) {
        try {
            NotificationBean where = new NotificationBean();
            where.setLastuser(uid);
            where.setReadStatus(NotificationTypes.READ_STATUS_UNREAD);
            return Math.max(0, notificationMapper.count(where));
        } catch (Exception e) {
            // 角标失败不应该把整个列表查询拖垮
            log.warn("[Notification] 查询未读数失败, uid={}: {}", uid, e.getMessage());
            return 0;
        }
    }

    /** 超出保留上限时从最旧的开始删；正常规模下只会在第 201 条时触发一次 */
    private void trimOverflow(String uid) {
        try {
            NotificationBean where = new NotificationBean();
            where.setLastuser(uid);
            int total = notificationMapper.count(where);
            if (total <= MAX_PER_USER) {
                return;
            }
            int overflow = Math.min(total - MAX_PER_USER, MAX_PER_USER);
            PageRequest page = new PageRequest(1, overflow);
            page.setOrder("iid");
            page.setDesc("asc");
            List<NotificationBean> oldest = notificationMapper.select(page, where);
            if (oldest != null && !oldest.isEmpty()) {
                notificationMapper.delete(oldest);
            }
        } catch (Exception e) {
            log.warn("[Notification] 清理旧通知失败, uid={}: {}", uid, e.getMessage());
        }
    }

    private static String normalizeLevel(String level) {
        String value = trim(level).toLowerCase();
        if (NotificationTypes.LEVEL_SUCCESS.equals(value)
                || NotificationTypes.LEVEL_WARNING.equals(value)
                || NotificationTypes.LEVEL_ERROR.equals(value)
                || NotificationTypes.LEVEL_INFO.equals(value)) {
            return value;
        }
        return NotificationTypes.LEVEL_INFO;
    }

    private static String defaultIfBlank(String value, String fallback) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean notLoggedIn(String uid) {
        return uid == null || uid.trim().isEmpty();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
