package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.AiUserQuotaBean;
import com.aiproject.ai.service.gateway.AiGatewayConfigResolver;
import com.aiproject.ai.service.gateway.AiGatewayGuard;
import com.aiproject.ai.service.gateway.AiGatewayProperties;
import com.aiproject.ai.service.gateway.ModelGatewayResolver;
import com.aiproject.ai.service.gateway.dto.AiGatewayConfigStatus;
import com.aiproject.ai.service.gateway.dto.AiGatewayLogQuery;
import com.aiproject.ai.service.gateway.dto.AiGatewayModelStat;
import com.aiproject.ai.service.gateway.dto.AiGatewayModuleStat;
import com.aiproject.ai.service.gateway.dto.AiGatewaySummary;
import com.aiproject.ai.service.gateway.dto.AiGatewayTrendPoint;
import com.aiproject.ai.service.gateway.dto.AiGatewayUserOption;
import com.aiproject.ai.service.gateway.dto.AiGatewayUserStat;
import com.aiproject.ai.service.gateway.dto.AiMyUsage;
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
    private static final int EXPORT_MAX_ROWS = 10000;
    private static final int RECENT_LOG_LIMIT = 20;
    private static final String UNKNOWN_MODEL = "未知模型";

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
    ModelGatewayResolver modelGatewayResolver;

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
                ModelGatewayResolver.MODULE_MODELS,
                ModelGatewayResolver.MODULE_NAME,
                modelGatewayResolver.hasDirectModels()));
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
        Map<String, long[]> userStats = scanLogs(null, range, summary);
        summary.setTopUsers(topUsers(userStats));
        return Result.succeed(summary, "查询成功");
    }

    /**
     * 分页扫描调用日志并聚合到 summary（趋势/模块/模型细分/延迟）。
     *
     * @param userIdOrNull 为 null 时统计全部用户，否则只统计该用户
     * @return 按用户聚合的 {调用次数, tokens}，仅供全量统计的排行榜使用
     */
    private Map<String, long[]> scanLogs(String userIdOrNull, DateRange range, AiGatewaySummary summary) {
        Map<String, AiGatewayTrendPoint> trend = initTrend(range.start, range.end);
        Map<String, AiGatewayModuleStat> modules = initModules();
        Map<String, AiGatewayModelStat> models = new LinkedHashMap<String, AiGatewayModelStat>();
        Map<String, long[]> modelLatency = new HashMap<String, long[]>();
        Map<String, long[]> userStats = new HashMap<String, long[]>();
        List<Long> latencies = new ArrayList<Long>();
        List<Long> firstTokens = new ArrayList<Long>();

        int pageNum = 1;
        int scanned = 0;

        while (pageNum <= 200) {
            PageRequest pageRequest = new PageRequest(pageNum, SCAN_PAGE_SIZE);
            pageRequest.setOrder("iid");
            pageRequest.setDesc("asc");
            AiCallLogBean where = new AiCallLogBean();
            where.setUserId(userIdOrNull);
            Result<PageResult<AiCallLogBean>> result = callLogBeanMapper.selectPage(
                    pageRequest, where, timeCondition(range));
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
                summary.setTotalCostCents(summary.getTotalCostCents()
                        + (row.getCostCents() == null ? 0L : row.getCostCents()));

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
                    latencies.add(row.getLatencyMs());
                }
                if (row.getFirstTokenMs() != null) {
                    firstTokens.add(row.getFirstTokenMs());
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

                String modelName = blankToNull(row.getModelName());
                if (modelName == null) {
                    modelName = UNKNOWN_MODEL;
                }
                AiGatewayModelStat modelStat = models.get(modelName);
                if (modelStat == null) {
                    modelStat = new AiGatewayModelStat();
                    modelStat.setModel(modelName);
                    models.put(modelName, modelStat);
                    modelLatency.put(modelName, new long[2]);
                }
                modelStat.setCalls(modelStat.getCalls() + 1);
                modelStat.setTokens(modelStat.getTokens() + tokens);
                if (failed || blocked) {
                    modelStat.setFailed(modelStat.getFailed() + 1);
                }
                long cost = row.getCostCents() == null ? 0L : row.getCostCents();
                modelStat.setCostCents(modelStat.getCostCents() + cost);
                // 该模型只要有调用且拿到了非零成本，就说明配了价格
                if (cost > 0) {
                    modelStat.setPriced(true);
                }
                if (row.getLatencyMs() != null) {
                    long[] latency = modelLatency.get(modelName);
                    latency[0] += row.getLatencyMs();
                    latency[1]++;
                }

                if (userIdOrNull == null && row.getUserId() != null) {
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

        if (!latencies.isEmpty()) {
            long latencySum = 0;
            for (Long value : latencies) {
                latencySum += value;
            }
            summary.setAvgLatencyMs(latencySum / latencies.size());
            summary.setP95LatencyMs(percentile(latencies, 0.95));
        }
        if (!firstTokens.isEmpty()) {
            long ftSum = 0;
            for (Long value : firstTokens) {
                ftSum += value;
            }
            summary.setAvgFirstTokenMs(ftSum / firstTokens.size());
        }
        if (summary.getTotalCalls() > 0) {
            summary.setSuccessRate(round2(summary.getSuccessCalls() * 100.0 / summary.getTotalCalls()));
        }
        summary.getTrend().addAll(trend.values());
        summary.getModules().addAll(modules.values());
        summary.getModels().addAll(models.values());
        Collections.sort(summary.getModels(), new Comparator<AiGatewayModelStat>() {
            @Override
            public int compare(AiGatewayModelStat a, AiGatewayModelStat b) {
                return Long.compare(b.getCalls(), a.getCalls());
            }
        });
        for (AiGatewayModelStat modelStat : summary.getModels()) {
            long[] latency = modelLatency.get(modelStat.getModel());
            if (latency != null && latency[1] > 0) {
                modelStat.setAvgLatencyMs(latency[0] / latency[1]);
            }
            // 有调用、消耗了 token、却算不出成本 → 该模型没配价格。
            // 汇总里标记出来，避免看板把「未配置」显示成「免费」。
            if (!modelStat.isPriced() && modelStat.getCalls() > 0 && modelStat.getTokens() > 0) {
                summary.setHasUnpricedModels(true);
            }
        }
        return userStats;
    }

    /** 最近调用记录（按 iid 倒序），用于「我的用量」页 */
    private List<AiCallLogBean> recentLogs(String uid, int limit) {
        PageRequest pageRequest = new PageRequest(1, limit);
        pageRequest.setOrder("iid");
        pageRequest.setDesc("desc");
        AiCallLogBean where = new AiCallLogBean();
        where.setUserId(uid);
        Result<PageResult<AiCallLogBean>> result = callLogBeanMapper.selectPage(pageRequest, where);
        if (result == null || result.getDatas() == null || result.getDatas().getContent() == null) {
            return new ArrayList<AiCallLogBean>();
        }
        return result.getDatas().getContent();
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

    /**
     * 普通用户的个人用量（不需管理员）：今日配额水位 + 区间内个人调用统计 + 最近调用记录。
     */
    public Result<AiMyUsage> myUsage(String uid, AiGatewayLogQuery query) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "未登录或登录已过期");
        }
        DateRange range = resolveRange(query);
        if (range == null) {
            return Result.failed(null, "日期格式不正确，应为 yyyy-MM-dd，且区间不超过 31 天");
        }

        AiMyUsage my = new AiMyUsage();
        long quota = quotaHolder.effectiveQuota(uid);
        long used = guard.usedTokens(uid);
        my.setUsedToday(used);
        my.setEffectiveQuota(quota);
        if (quota > 0) {
            my.setRemainingTokens(Math.max(0, quota - used));
            my.setUsagePercent(Math.min(100.0, round2(used * 100.0 / quota)));
        } else {
            // 配额为 0 表示不限制，剩余额度用 -1 表达
            my.setRemainingTokens(-1);
        }
        scanLogs(uid, range, my.getUsage());
        my.getRecentLogs().addAll(recentLogs(uid, RECENT_LOG_LIMIT));
        return Result.succeed(my, "查询成功");
    }

    /**
     * 审计日志 CSV 导出（仅管理员）。
     * 与 logs() 相同的过滤条件，最多导出 10000 条；内容以 Result&lt;String&gt; 返回，由前端打包下载。
     */
    public Result<String> exportLogs(String adminUid, AiGatewayLogQuery query) {
        if (!isAdmin(adminUid)) {
            return Result.failed(null, "仅管理员可访问 AI 用量看板");
        }
        DateRange range = resolveRange(query);
        if (range == null) {
            return Result.failed(null, "日期格式不正确，应为 yyyy-MM-dd，且区间不超过 31 天");
        }

        AiCallLogBean where = new AiCallLogBean();
        if (query != null) {
            where.setUserId(blankToNull(query.getUserId()));
            where.setModule(blankToNull(query.getModule()));
            where.setStatus(blankToNull(query.getStatus()));
        }

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF');
        csv.append("调用时间,用户,模块,端点,HTTP方法,状态,HTTP状态码,模型,输入tokens,输出tokens,总tokens,耗时(ms),失败原因,客户端IP");
        csv.append("\r\n");

        int pageNum = 1;
        int exported = 0;
        while (pageNum <= 50) {
            PageRequest pageRequest = new PageRequest(pageNum, SCAN_PAGE_SIZE);
            pageRequest.setOrder("iid");
            pageRequest.setDesc("desc");
            Result<PageResult<AiCallLogBean>> result = callLogBeanMapper.selectPage(
                    pageRequest, where, timeCondition(range));
            if (result == null || result.getDatas() == null) {
                break;
            }
            List<AiCallLogBean> rows = result.getDatas().getContent();
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (AiCallLogBean row : rows) {
                csv.append(csvCell(formatDateTime(row.getCreatetime()))).append(',');
                csv.append(csvCell(row.getUserId())).append(',');
                csv.append(csvCell(moduleLabel(row.getModule()))).append(',');
                csv.append(csvCell(row.getEndpoint())).append(',');
                csv.append(csvCell(row.getMethod())).append(',');
                csv.append(csvCell(statusLabel(row.getStatus()))).append(',');
                csv.append(csvCell(row.getHttpStatus() == null ? null : String.valueOf(row.getHttpStatus()))).append(',');
                csv.append(csvCell(row.getModelName())).append(',');
                csv.append(csvCell(row.getPromptTokens() == null ? null : String.valueOf(row.getPromptTokens()))).append(',');
                csv.append(csvCell(row.getCompletionTokens() == null ? null : String.valueOf(row.getCompletionTokens()))).append(',');
                csv.append(csvCell(row.getTotalTokens() == null ? null : String.valueOf(row.getTotalTokens()))).append(',');
                csv.append(csvCell(row.getLatencyMs() == null ? null : String.valueOf(row.getLatencyMs()))).append(',');
                csv.append(csvCell(row.getErrorMessage())).append(',');
                csv.append(csvCell(row.getClientIp()));
                csv.append("\r\n");
                exported++;
                if (exported >= EXPORT_MAX_ROWS) {
                    return Result.succeed(csv.toString(), "已按上限导出 " + EXPORT_MAX_ROWS + " 条");
                }
            }
            Integer total = result.getDatas().getTotalSize();
            if (total != null && pageNum * SCAN_PAGE_SIZE >= total) {
                break;
            }
            if (total == null && rows.size() < SCAN_PAGE_SIZE) {
                break;
            }
            pageNum++;
        }
        return Result.succeed(csv.toString(), "导出成功，共 " + exported + " 条");
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
                ModelGatewayResolver.MODULE_MODELS}) {
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

    private static long percentile(List<Long> values, double ratio) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * ratio) - 1;
        if (index < 0) {
            index = 0;
        }
        return sorted.get(index);
    }

    private static String moduleLabel(String module) {
        if (module == null || module.isEmpty()) {
            return "";
        }
        if ("chat".equals(module)) return "问答聊天";
        if ("knowledgeChat".equals(module)) return "知识库对话";
        if ("file".equals(module)) return "知识库文件";
        if ("models".equals(module)) return "模型直连";
        return module;
    }

    private static String statusLabel(String status) {
        if (status == null || status.isEmpty()) {
            return "";
        }
        if ("success".equals(status)) return "成功";
        if ("failed".equals(status)) return "失败";
        if ("blocked".equals(status)) return "已拦截";
        if ("aborted".equals(status)) return "已中断";
        return status;
    }

    /** CSV 单元格转义：含逗号/引号/换行时加引号包裹，引号翻倍 */
    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\"", "\"\"");
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text + "\"";
        }
        return text;
    }

    private static String formatDateTime(java.util.Date date) {
        if (date == null) {
            return "";
        }
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(date.toInstant().atZone(java.time.ZoneId.systemDefault()));
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
