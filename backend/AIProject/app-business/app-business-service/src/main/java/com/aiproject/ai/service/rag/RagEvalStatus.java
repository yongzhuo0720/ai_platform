package com.aiproject.ai.service.rag;

/** 评估运行状态。queued/running 为活动状态，其余为终态。 */
public final class RagEvalStatus {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String PARTIAL = "partial";
    public static final String FAILED = "failed";

    private RagEvalStatus() {
    }

    public static boolean isActive(String status) {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }

    public static boolean isComparable(String status) {
        return SUCCESS.equals(status) || PARTIAL.equals(status);
    }
}
