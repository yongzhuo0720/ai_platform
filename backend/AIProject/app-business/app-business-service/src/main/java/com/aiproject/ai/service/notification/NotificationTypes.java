package com.aiproject.ai.service.notification;

/** 通知的类别、级别与已读状态取值：生产者与查询方共用，避免各写各的字符串。 */
public final class NotificationTypes {

    public static final String CATEGORY_DOC = "doc";
    public static final String CATEGORY_RAG_EVAL = "rag-eval";
    public static final String CATEGORY_SYSTEM = "system";

    public static final String LEVEL_SUCCESS = "success";
    public static final String LEVEL_WARNING = "warning";
    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_INFO = "info";

    public static final String READ_STATUS_UNREAD = "unread";
    public static final String READ_STATUS_READ = "read";

    private NotificationTypes() {
    }
}
