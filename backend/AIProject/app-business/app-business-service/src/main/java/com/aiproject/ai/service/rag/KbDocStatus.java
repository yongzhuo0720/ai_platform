package com.aiproject.ai.service.rag;

/**
 * 文档处理状态机：queued → chunking → embedding → ready / failed。
 *
 * <p>状态只由后台 Runner 推进；接口层只负责创建 queued 记录与把 failed 重新置为 queued。
 * 这样「谁改了什么状态」只有一个入口，排查卡住的任务时不需要翻多个写入点。</p>
 */
public final class KbDocStatus {

    public static final String QUEUED = "queued";
    public static final String CHUNKING = "chunking";
    public static final String EMBEDDING = "embedding";
    public static final String READY = "ready";
    public static final String FAILED = "failed";

    private KbDocStatus() {
    }

    /** 排队或处理中：文档列表需要轮询进度 */
    public static boolean isActive(String status) {
        return QUEUED.equals(status) || CHUNKING.equals(status) || EMBEDDING.equals(status);
    }

    public static boolean isReady(String status) {
        return READY.equals(status);
    }

    public static boolean isFailed(String status) {
        return FAILED.equals(status);
    }
}
