package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.aiproject.ai.service.gateway.AgentGatewayConfigResolver;
import com.aiproject.ai.service.gateway.AiGatewayConfigResolver;
import com.aiproject.ai.service.gateway.AiGatewayGuard;
import com.aiproject.ai.service.gateway.AiGatewayProperties;
import com.aiproject.ai.service.gateway.dto.AiGatewayConfigStatus;
import com.aiproject.ai.service.gateway.dto.AiGatewayLogQuery;
import com.aiproject.ai.service.gateway.dto.AiGatewayModuleStat;
import com.aiproject.ai.service.gateway.dto.AiGatewaySummary;
import com.aiproject.ai.service.gateway.dto.AiGatewayTrendPoint;
import com.aiproject.ai.service.gateway.dto.AiGatewayUserOption;
import com.aiproject.ai.service.gateway.dto.AiGatewayUserStat;
import com.aiproject.ai.service.gateway.dto.AiQuotaOverview;
import com.aiproject.ai.service.gateway.dto.AiQuotaView;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.SqlFieldTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 网关后台统计。
 * <p>
 * 统计采用「分页扫描 + Java 聚合」：不拼复杂 SQL，避免注入与列名歧义；
 * 单次统计最多扫描 10 万条、区间最多 31 天，超限会返回 truncated=true。
 */
@Slf4j
@Service
public class AiGatewayAdminHolder {

    private static final int MAX_SCAN_ROWS = 100000;
    private static final int SCAN_PAGE_SIZE = 2000;
    private static final int MAX_RANGE_DAYS = 31;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    AiCallLogBeanMapper<AiCallLogBean> callLogBeanMapper;

    @Autowired
    AiUserQuotaHolder quotaHolder;

    @Autowired
    AiGatewayGuard guard;

    @Autowired
    AiGatewayProperties properties;

    @Autowired
    AiGatewayConfigResolver configResolver;

    @Autowired
    AgentGatewayConfigResolver agentConfigResolver;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    public boolean isAdmin(String uid) {
        return myUserBaseDbHolder.isAdmin(uid);
    }

    public Result<AiGatewayConfigStatus> configStatus(String adminUid) {
        if (!isAdmin(adminUid)) {
            return Result.failed(null, "仅管理员可访问 AI 用量看板");
        }
        AiGatewayConfigStatus status = new AiGatewayConfigStatus();
        status.setEnabled(properties.isEnabled());
        status.setRateLimitPerMinute(properties.getRateLimitPerMinute());
        status.setDefaultDailyTokenQuota(properties.getDefaultDailyTokenQuota());
        for (String module : new String[]{
                AiGatewayConfigResolver.MODULE_CHAT,
                AiGatewayConfigResolver.MODULE_KNOWLEDGE_CHAT,
                AiGatewayConfigResolver.MODULE_FILE}) {
            status.getModules().add(new AiGatewayConfigStatus.ModuleStatus(
                    module,
                    configResolver.moduleName(module),
                    configResolver.resolve(module) != null));
        }
        status.getModules().add(new AiGatewayConfigStatus.ModuleStatus(
                AgentGatewayConfigResolver.MODULE_AGENT,
                AgentGatewayConfigResolver.MODULE_NAME,
                agentConfigResolver.isDefaultConfigured()));
        return Result.succeed(status, "查询成功");
    }

    public Result<AiGatewaySummary> summary(String adminUid, AiGatewayLogQuery query) {
        if (!isAdmin(adminUid)) {
            return Result.failed(null, "仅管理员可访问 AI 用量看板");
        }
        DateRange range = resolveRange(query);
        if (range == null) {
            return Result.failed(null, "日期格式不正确，应为 yyyy-MM-dd，且区间不超过 31 天");
        }

        AiGatewaySummary summary = new AiGatewaySummary();
        Map<String, AiGatewayTrendPoint> trend = initTrend(range.start, range.end);
        Map<String, AiGatewayModuleStat> modules = initModules();
        Map<String, long[]> userStats = new HashMap<String, long[]>();

        long latencySum = 0;
        long latencyCount = 0;
        int pageNum = 1;
        int scanned = 0;

        while (pageNum <= 200) {
            PageRequest pageRequest = new PageRequest(pageNum, SCAN_PAGE_SIZE);
            pageRequest.setOrder("iid");
            pageRequest.setDesc("asc");
            Result<PageResult<AiCallLogBean>> result = callLogBeanMapper.selectPage(
                    pageRequest, new AiCallLogBean(), timeCondition(range));
            if (result == null || result.getDatas() == null) {
                break;
            }
            PageResult<AiCallLogBean> page = result.getDatas();
            List<AiCallLogBean> rows = page.getContent();
            if (rows == null || rows.isEmpty()) {
                break;
            }

            for (AiCallLogBean row : rows) {
                scanned++;
                summary.setTotalCalls(summary.getTotalCalls() + 1);
                long tokens = row.getTotalTokens() == null ? 0L : row.getTotalTokens();
                summary.setTotalTokens(summary.getTotalTokens() + tokens);

                boolean blocked = "blocked".equals(row.getStatus());
                boolean failed = "failed".equals(row.getStatus()) || "aborted".equals(row.getStatus());
                if (blocked) {
                    summary.setBlockedCalls(summary.getBlockedCalls() + 1);
                } else if (failed) {
                    summary.setFailedCalls(summary.getFailedCalls() + 1);
                } else {
                    summary.setSuccessCalls(summary.getSuccessCalls() + 1);
                }
                if (row.getLatencyMs() != null) {
                    latencySum += row.getLatencyMs();
                    latencyCount++;
                }

                String day = dayOf(row);
                if (day != null && trend.containsKey(day)) {
                    AiGatewayTrendPoint point = trend.get(day);
                    point.setCalls(point.getCalls() + 1);
                    point.setTokens(point.getTokens() + tokens);
                    if (failed || blocked) {
                        point.setFailed(point.getFailed() + 1);
                    }
                }

                String module = row.getModule() == null ? "unknown" : row.getModule();
                AiGatewayModuleStat moduleStat = modules.get(module);
                if (moduleStat == null) {
                    moduleStat = new AiGatewayModuleStat();
                    moduleStat.setModule(module);
                    modules.put(module, moduleStat);
                }
                moduleStat.setCalls(moduleStat.getCalls() + 1);
                moduleStat.setTokens(moduleStat.getTokens() + tokens);
                if (failed || blocked) {
                    moduleStat.setFailed(moduleStat.getFailed() + 1);
                }

                if (row.getUserId() != null) {
                    long[] stat = userStats.get(row.getUserId());
                    if (stat == null) {
                        stat = new long[2];
                        userStats.put(row.getUserId(), stat);
                    }
                    stat[0] = stat[0] + 1;
                    stat[1] = stat[1] + tokens;
                }
            }

            if (scanned >= MAX_SCAN_ROWS) {
                summary.setTruncated(true);
                break;
            }
            Integer total = page.getTotalSize();
            if (total != null && pageNum * SCAN_PAGE_SIZE >= total) {
                break;
            }
            if (total == null && rows.size() < SCAN_PAGE_SIZE) {
                break;
            }
            pageNum++;
        }

        if (latencyCount > 0) {
            summary.setAvgLatencyMs(latencySum / latencyCount);
        }
        if (summary.getTotalCalls() > 0) {
            summary.setSuccessRate(round2(summary.getSuccessCalls() * 100.0 / summary.getTotalCalls()));
        }
        summary.getTrend().addAll(trend.values());
        summary.getModules().addAll(modules.values());
        summary.setTopUsers(topUsers(userStats));
        return Result.succeed(summary, "查询成功");
    }

    public Result<PageResult<AiCallLogBean>> logs(String adminUid, AiGatewayLogQuery query) {
        if (!isAdmin(adminUid)) {
            return Result.failed(null, "仅管理员可访问 AI 用量看板");
        }
        DateRange range = resolveRange(query);
        if (range == null) {
            return Result.failed(null, "日期格式不正确，应为 yyyy-MM-dd，且区间不超过 31 天");
        }

        int pageNum = query != null && query.getPageNum() != null && query.getPageNum() > 0
                ? query.getPageNum() : 1;
        int pageSize = query != null && query.getPageSize() != null ? query.getPageSize() : 20;
        pageSize = Math.max(1, Math.min(200, pageSize));

        AiCallLogBean where = new AiCallLogBean();
        if (query != null) {
            where.setUserId(blankToNull(query.getUserId()));
            where.setModule(blankToNull(query.getModule()));
            where.setStatus(blankToNull(query.getStatus()));
        }

        PageRequest pageRequest = new PageRequest(pageNum, pageSize);
        pageRequest.setOrder("iid");
        pageRequest.setDesc("desc");
        Result<PageResult<AiCallLogBean>> result = callLogBeanMapper.selectPage(
                pageRequest, where, timeCondition(range));
        if (result == null) {
            return Result.failed(null, "查询失败");
        }
        return result;
    }

    public Result<AiQuotaOverview> quotas(String adminUid) {
        if (!isAdmin(adminUid)) {
            return Result.failed(null, "仅管理员可访问 AI 用量看板");
        }
        AiQuotaOverview overview = new AiQuotaOverview();
        overview.setDefaultDailyTokenQuota(properties.getDefaultDailyTokenQuota());

        List<AiUserQuotaBean> overrides = quotaHolder.listAll();
        for (AiUserQuotaBean item : overrides) {
            AiQuotaView view = new AiQuotaView();
            view.setUserId(item.getUserId());
            view.setUserName(displayName(item.getUserId()));
            view.setDailyTokenQuota(item.getDailyTokenQuota());
            view.setEffectiveQuota(quotaHolder.effectiveQuota(item.getUserId()));
            view.setUsedToday(guard.usedTokens(item.getUserId()));
            view.setRemark(item.getRemark());
            view.setCustom(true);
            overview.getItems().add(view);
        }
        Collections.sort(overview.getItems(), new Comparator<AiQuotaView>() {
            @Override
            public int compare(AiQuotaView a, AiQuotaView b) {
                return Long.compare(b.getUsedToday(), a.getUsedToday());
            }
        });
        return Result.succeed(overview, "查询成功");
    }

    public Result<Boolean> saveQuota(String adminUid, AiUserQuotaBean data) {
        if (!isAdmin(adminUid)) {
            return Result.failed(false, "仅管理员可维护 AI 配额");
        }
        return quotaHolder.save(adminUid, data);
    }

    public Result<Boolean> deleteQuota(String adminUid, AiUserQuotaBean data) {
        if (!isAdmin(adminUid)) {
            return Result.failed(false, "仅管理员可维护 AI 配额");
        }
        return quotaHolder.delete(data);
    }

    public Result<List<AiGatewayUserOption>> users(String adminUid) {
        if (!isAdmin(adminUid)) {
            return Result.failed(null, "仅管理员可访问 AI 用量看板");
        }
        PageRequest pageRequest = new PageRequest(1, 1000);
        pageRequest.setOrder("uaccess");
        pageRequest.setDesc("asc");
        Result<PageResult<MyUserBaseDb>> result = myUserBaseDbHolder.querys(
                adminUid, new MyUserBaseDb(), pageRequest);
        List<AiGatewayUserOption> options = new ArrayList<AiGatewayUserOption>();
        if (result != null && result.getDatas() != null && result.getDatas().getContent() != null) {
            for (MyUserBaseDb user : result.getDatas().getContent()) {
                AiGatewayUserOption option = new AiGatewayUserOption();
                option.setUid(user.getUid());
                option.setUaccess(user.getUaccess());
                option.setUname(user.getUname());
                options.add(option);
            }
        }
        return Result.succeed(options, "查询成功");
    }

    private List<AiGatewayUserStat> topUsers(Map<String, long[]> userStats) {
        List<Map.Entry<String, long[]>> entries = new ArrayList<Map.Entry<String, long[]>>(
                userStats.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        List<AiGatewayUserStat> result = new ArrayList<AiGatewayUserStat>();
        for (int i = 0; i < entries.size() && i < 10; i++) {
            Map.Entry<String, long[]> entry = entries.get(i);
            AiGatewayUserStat stat = new AiGatewayUserStat();
            stat.setUserId(entry.getKey());
            stat.setUserName(displayName(entry.getKey()));
            stat.setCalls(entry.getValue()[0]);
            stat.setTokens(entry.getValue()[1]);
            result.add(stat);
        }
        return result;
    }

    private String displayName(String uid) {
        MyUserBaseDb user = myUserBaseDbHolder.queryUserByUid(uid);
        if (user == null) {
            return uid;
        }
        if (user.getUname() != null && !user.getUname().isEmpty()) {
            return user.getUname();
        }
        return user.getUaccess() != null ? user.getUaccess() : uid;
    }

    private Map<String, AiGatewayTrendPoint> initTrend(LocalDate start, LocalDate end) {
        Map<String, AiGatewayTrendPoint> trend =
                new LinkedHashMap<String, AiGatewayTrendPoint>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            String day = DATE.format(cursor);
            AiGatewayTrendPoint point = new AiGatewayTrendPoint();
            point.setDate(day);
            trend.put(day, point);
            cursor = cursor.plusDays(1);
        }
        return trend;
    }

    private Map<String, AiGatewayModuleStat> initModules() {
        Map<String, AiGatewayModuleStat> modules = new LinkedHashMap<String, AiGatewayModuleStat>();
        for (String module : new String[]{
                AiGatewayConfigResolver.MODULE_CHAT,
                AiGatewayConfigResolver.MODULE_KNOWLEDGE_CHAT,
                AiGatewayConfigResolver.MODULE_FILE,
                AgentGatewayConfigResolver.MODULE_AGENT}) {
            AiGatewayModuleStat stat = new AiGatewayModuleStat();
            stat.setModule(module);
            modules.put(module, stat);
        }
        return modules;
    }

    private String dayOf(AiCallLogBean row) {
        if (row.getCreatetime() == null) {
            return null;
        }
        return DATE.format(row.getCreatetime().toLocalDateTime().toLocalDate());
    }

    private String timeCondition(DateRange range) {
        return SqlFieldTools.timeBetween(
                callLogBeanMapper.getTableName(),
                "createtime",
                range.start.format(DATE) + " 00:00:00",
                range.end.format(DATE) + " 23:59:59");
    }

    private DateRange resolveRange(AiGatewayLogQuery query) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        LocalDate end = today;

        if (query != null && (notBlank(query.getStartDate()) || notBlank(query.getEndDate()))) {
            if (!notBlank(query.getStartDate()) || !notBlank(query.getEndDate())) {
                return null;
            }
            try {
                start = LocalDate.parse(query.getStartDate().trim(), DATE);
                end = LocalDate.parse(query.getEndDate().trim(), DATE);
            } catch (Exception e) {
                return null;
            }
        }
        if (start.isAfter(end)) {
            return null;
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            return null;
        }
        return new DateRange(start, end);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value.trim() : null;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static class DateRange {
        private final LocalDate start;
        private final LocalDate end;

        private DateRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }
}
