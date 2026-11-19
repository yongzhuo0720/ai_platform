package com.aiproject.ai.permission.utils;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.heneng.hnsp.common.croe.model.Result;

/**
 * 超管校验的统一入口（2026-11-19 升级 P2-4）。
 *
 * <p>此前 10+ 个 Holder 各自写了一遍 {@code checkAdmin} 模板，
 * 判空口径和错误文案各不相同，改鉴权口径时要改十余处、容易漏改。
 * 这里把判定逻辑收敛到一处，文案由调用方传入，保持原有提示不变。</p>
 *
 * <p><b>返回约定与旧 checkAdmin 完全一致</b>：返回 {@code null} 表示校验通过，
 * 返回非 null 的 {@code Result.failed} 表示应直接把它返回给调用方。</p>
 *
 * <p>判空统一取更严格的口径（null 或 blank 都拒绝）；超管判定统一走
 * {@link MyUserBaseDbHolder#isAdmin(String)}——该方法内部已判空并判断 rid==99999。</p>
 */
public final class AdminGuard {

    private AdminGuard() {
    }

    /**
     * 校验当前用户是否为超管。
     *
     * @param uid        当前登录用户 id
     * @param userHolder 用户 Holder（提供 isAdmin 判定）
     * @param resource   被保护的资源名，用于拼错误文案（如"模型配置""数据集仓库"）
     * @return null=校验通过；非 null=拒绝原因，调用方应直接返回
     */
    public static Result<Boolean> requireAdmin(String uid, MyUserBaseDbHolder userHolder, String resource) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        if (userHolder == null || !userHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护" + (resource == null ? "该资源" : resource));
        }
        return null;
    }
}
