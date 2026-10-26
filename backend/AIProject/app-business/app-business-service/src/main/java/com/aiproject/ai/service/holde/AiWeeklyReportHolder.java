package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.AiWeeklyReportBean;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import com.aiproject.ai.service.mapper.AiWeeklyReportBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.SqlFieldTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 调用周报生成与查询。
 *
 * <p>聚合口径与治理看板一致（按 aicalllogbean 时间范围扫描），
 * 但落成一行快照留档：管理员看周趋势不用再选日期翻日志。</p>
 */
@Slf4j
@Service
public class AiWeeklyReportHolder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int PAGE_SIZE = 500;

    @Autowired
    AiCallLogBeanMapper<AiCallLogBean> callLogBeanMapper;

    @Autowired
    AiWeeklyReportBeanMapper<AiWeeklyReportBean> reportMapper;

    /** 生成指定周（缺省=上一自然周）的周报；重复生成覆盖旧快照 */
    public Result<AiWeeklyReportBean> generate(String weekStartText) {
        LocalDate monday;
        if (weekStartText != null && !weekStartText.trim().isEmpty()) {
            try {
                monday = LocalDate.parse(weekStartText.trim(), DATE);
            } catch (Exception e) {
                return Result.failed(null, "日期格式应为 yyyy-MM-dd");
            }
        } else {
            monday = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(1);
        }
        // 对齐到所在周的周一
        monday = monday.with(DayOfWeek.MONDAY);
        LocalDate sunday = monday.plusDays(6);

        String start = monday.format(DATE) + " 00:00:00";
        String end = sunday.format(DATE) + " 23:59:59";
        String where = SqlFieldTools.timeBetween(callLogBeanMapper.getTableName(), "createtime", start, end);

        long total = 0, success = 0, failed = 0, tokens = 0, cost = 0, latencySum = 0, latencyN = 0, ftSum = 0, ftN = 0;
        Map<String, Long> byUser = new HashMap<String, Long>();
        Map<String, Long> byModel = new HashMap<String, Long>();

        int pageNum = 1;
        while (pageNum <= 100) {
            PageRequest pageRequest = new PageRequest(pageNum, PAGE_SIZE);
            pageRequest.setOrder("iid");
            pageRequest.setDesc("asc");
            AiCallLogBean empty = new AiCallLogBean();
            Result<PageResult<AiCallLogBean>> result = callLogBeanMapper.selectPage(pageRequest, empty, where);
            if (result == null || result.getDatas() == null) {
                break;
            }
            List<AiCallLogBean> rows = result.getDatas().getContent();
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (AiCallLogBean row : rows) {
                total++;
                boolean ok = "success".equals(row.getStatus());
                if (ok) {
                    success++;
                } else {
                    failed++;
                }
                if (row.getTotalTokens() != null) {
                    tokens += row.getTotalTokens();
                }
                if (row.getCostCents() != null) {
                    cost += row.getCostCents();
                }
                if (row.getLatencyMs() != null) {
                    latencySum += row.getLatencyMs();
                    latencyN++;
                }
                if (row.getFirstTokenMs() != null) {
                    ftSum += row.getFirstTokenMs();
                    ftN++;
                }
                if (ok) {
                    count(byUser, row.getUserId());
                    count(byModel, row.getModelName());
                }
            }
            if (rows.size() < PAGE_SIZE) {
                break;
            }
            pageNum++;
        }

        AiWeeklyReportBean report = new AiWeeklyReportBean();
        report.setWeekStart(monday.format(DATE));
        report.setWeekEnd(sunday.format(DATE));
        report.setTotalCalls(total);
        report.setSuccessCalls(success);
        report.setFailedCalls(failed);
        report.setTotalTokens(tokens);
        report.setCostCents(cost);
        report.setAvgLatencyMs(latencyN == 0 ? 0L : latencySum / latencyN);
        report.setAvgFirstTokenMs(ftN == 0 ? 0L : ftSum / ftN);
        report.setTopUser(topKey(byUser));
        report.setTopModel(topKey(byModel));
        report.setLastuser("system");

        // 覆盖式写入：同周重复生成只保留最新一份
        AiWeeklyReportBean whereBean = new AiWeeklyReportBean();
        whereBean.setWeekStart(report.getWeekStart());
        reportMapper.delete(whereBean);
        reportMapper.install(report);
        log.info("[周报] 已生成 {} ~ {}：{} 次调用 / {} tokens / {} 毫分",
                report.getWeekStart(), report.getWeekEnd(), total, tokens, cost);
        return Result.succeed(report, "周报已生成");
    }

    /** 周报列表（按周倒序） */
    public Result<List<AiWeeklyReportBean>> list() {
        List<AiWeeklyReportBean> rows = reportMapper.select(null, new AiWeeklyReportBean());
        if (rows != null) {
            rows.sort(new Comparator<AiWeeklyReportBean>() {
                @Override
                public int compare(AiWeeklyReportBean a, AiWeeklyReportBean b) {
                    return String.valueOf(b.getWeekStart()).compareTo(String.valueOf(a.getWeekStart()));
                }
            });
        }
        return Result.succeed(rows);
    }

    private static void count(Map<String, Long> map, String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        Long old = map.get(key);
        map.put(key, old == null ? 1L : old + 1);
    }

    private static String topKey(Map<String, Long> map) {
        String top = null;
        long max = -1;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                top = e.getKey();
            }
        }
        return top;
    }
}
