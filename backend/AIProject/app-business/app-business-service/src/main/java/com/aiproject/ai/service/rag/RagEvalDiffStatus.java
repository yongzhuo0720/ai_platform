package com.aiproject.ai.service.rag;

/** 逐题对比状态：只表达变化方向，不参与指标计算 */
public final class RagEvalDiffStatus {

    public static final String IMPROVED = "improved";
    public static final String REGRESSED = "regressed";
    public static final String MIXED = "mixed";
    public static final String UNCHANGED = "unchanged";
    public static final String UNKNOWN = "unknown";

    private RagEvalDiffStatus() {
    }
}
