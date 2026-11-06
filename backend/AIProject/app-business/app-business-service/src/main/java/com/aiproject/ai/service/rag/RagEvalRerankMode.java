package com.aiproject.ai.service.rag;

/**
 * 评估运行的精排策略。
 *
 * <p>auto 表示跟随全局 rerank 配置（未配置或调用失败时自动降级）；
 * off 表示本次运行主动关闭精排，用于同一数据集上的 A/B 对比。</p>
 */
public final class RagEvalRerankMode {

    public static final String AUTO = "auto";
    public static final String OFF = "off";

    private RagEvalRerankMode() {
    }

    public static String normalize(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return AUTO;
        }
        return mode.trim().toLowerCase();
    }

    public static boolean isValid(String mode) {
        String normalized = normalize(mode);
        return AUTO.equals(normalized) || OFF.equals(normalized);
    }

    public static boolean disabled(String mode) {
        return OFF.equals(normalize(mode));
    }
}
