package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.aiproject.ai.service.gateway.AiGatewayProperties;
import com.aiproject.ai.service.mapper.AiUserQuotaBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 用户 AI 每日 Token 配额。
 */
@Slf4j
@Service
public class AiUserQuotaHolder {

    @Autowired
    AiUserQuotaBeanMapper<AiUserQuotaBean> quotaBeanMapper;

    @Autowired
    AiGatewayProperties properties;

    public AiUserQuotaBean find(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        AiUserQuotaBean where = new AiUserQuotaBean();
        where.setUserId(userId);
        List<AiUserQuotaBean> list = quotaBeanMapper.select(null, where);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    /**
     * 生效配额：
     * 有用户覆盖且值非空 → 用覆盖值（0 表示不限制）；
     * 否则使用全局默认（0 表示不限制）。
     */
    public long effectiveQuota(String userId) {
        AiUserQuotaBean override = find(userId);
        if (override != null && override.getDailyTokenQuota() != null) {
            return override.getDailyTokenQuota();
        }
        return properties.getDefaultDailyTokenQuota();
    }

    public List<AiUserQuotaBean> listAll() {
        List<AiUserQuotaBean> list = quotaBeanMapper.select(null, new AiUserQuotaBean());
        return list == null ? Collections.<AiUserQuotaBean>emptyList() : list;
    }

    public Result<Boolean> save(String adminUid, AiUserQuotaBean data) {
        if (data == null || data.getUserId() == null || data.getUserId().trim().isEmpty()) {
            return Result.failed(false, "用户不能为空");
        }
        if (data.getDailyTokenQuota() == null || data.getDailyTokenQuota() < 0) {
            return Result.failed(false, "配额必须大于或等于 0");
        }
        // 成本预算校验（单位分，null/0=不限）：负数无意义，直接拒绝
        if (data.getDailyCostBudgetCents() != null && data.getDailyCostBudgetCents() < 0) {
            return Result.failed(false, "成本预算必须大于或等于 0");
        }
        // 告警阈值夹到 1-100，避免填 0 或 1000 导致告警永不触发/永远触发。
        // 用 CostBudgetEvaluator 的默认 80 作为 null 时的兜底（判定层也会再兜一次）。
        Integer pct = data.getAlertThresholdPct();
        if (pct != null) {
            if (pct < 1 || pct > 100) {
                return Result.failed(false, "告警阈值必须在 1-100 之间");
            }
        }
        String userId = data.getUserId().trim();
        data.setUserId(userId);
        data.setLastuser(adminUid);

        AiUserQuotaBean where = new AiUserQuotaBean();
        where.setUserId(userId);
        List<AiUserQuotaBean> existing = quotaBeanMapper.select(null, where);
        if (existing == null || existing.isEmpty()) {
            if (quotaBeanMapper.install(data) <= 0) {
                return Result.failed(false, "保存失败");
            }
        } else {
            if (quotaBeanMapper.update(data, where) <= 0) {
                return Result.failed(false, "保存失败");
            }
        }
        return Result.succeed(true, "保存成功");
    }

    public Result<Boolean> delete(AiUserQuotaBean data) {
        if (data == null || data.getUserId() == null || data.getUserId().trim().isEmpty()) {
            return Result.failed(false, "用户不能为空");
        }
        AiUserQuotaBean where = new AiUserQuotaBean();
        where.setUserId(data.getUserId().trim());
        if (quotaBeanMapper.delete(where) <= 0) {
            return Result.failed(false, "删除失败");
        }
        return Result.succeed(true, "删除成功");
    }
}
