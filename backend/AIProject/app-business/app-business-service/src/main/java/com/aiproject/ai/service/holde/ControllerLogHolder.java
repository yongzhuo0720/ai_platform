package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.UserControllerLogBean;
import com.aiproject.ai.service.dto.ControllerLogQuery;
import com.aiproject.ai.service.mapper.UserControllerLogBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.SqlFieldTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 后台操作日志查询。
 *
 * <p><b>为什么要自己写这一个接口。</b>项目原本调用的是框架模块里的
 * {@code /userControllerlog/getControllerLogByTimeAdmin}，而该模块在
 * {@code app-business-permission/pom.xml} 里是注释掉的——也就是说前端「操作日志」页
 * 一直是个 404。框架那个 Admin 版本本身也有问题：同名的非 Admin 接口会先按
 * {@code uaccount = uid} 过滤只看自己，Admin 版本则把请求体里的条件原样透传，
 * 谁调都能拿到全站记录。这里重写一遍，把「谁能看」和「看什么列」都收死后端。</p>
 *
 * <p><b>权限。</b>操作日志是跨用户的行为审计，普通用户看不到别人的操作，
 * 因此只有超管（rid=99999）可以查，判定复用
 * {@link MyUserBaseDbHolder#isAdmin(String)}，与 AI 治理看板同一个标准。</p>
 *
 * <p><b>可返回的列由实体决定。</b>{@link UserControllerLogBean} 不映射
 * {@code params}，所以请求参数（可能含登录口令）不在这条链路上出现。</p>
 */
@Slf4j
@Service
public class ControllerLogHolder {

    /** 单页最多 200 条：日志表是全站写入最频繁的表，不能让一次查询拖走几万行 */
    private static final int MAX_PAGE_SIZE = 200;

    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    UserControllerLogBeanMapper<UserControllerLogBean> logBeanMapper;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    public Result<PageResult<UserControllerLogBean>> query(String adminUid, ControllerLogQuery query) {
        if (!myUserBaseDbHolder.isAdmin(adminUid)) {
            return Result.failed(null, "仅管理员可查看操作日志");
        }

        String timeCondition;
        try {
            timeCondition = buildTimeCondition(query);
        } catch (IllegalArgumentException e) {
            return Result.failed(null, e.getMessage());
        }

        int pageNum = query != null && query.getPageNum() != null && query.getPageNum() > 0
                ? query.getPageNum() : 1;
        int pageSize = query != null && query.getPageSize() != null
                ? query.getPageSize() : DEFAULT_PAGE_SIZE;
        pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));

        PageRequest pageRequest = new PageRequest(pageNum, pageSize);
        pageRequest.setOrder("iid");
        pageRequest.setDesc("desc");

        // where 传空实体：本接口不提供按账号/接口地址过滤，全站记录按时间倒序分页
        UserControllerLogBean where = new UserControllerLogBean();
        Result<PageResult<UserControllerLogBean>> result = timeCondition == null
                ? logBeanMapper.selectPage(pageRequest, where)
                : logBeanMapper.selectPage(pageRequest, where, timeCondition);
        if (result == null) {
            log.warn("[ControllerLogHolder] 操作日志查询无返回, uid={}", adminUid);
            return Result.failed(null, "查询失败");
        }
        return result;
    }

    /**
     * 把日期区间拼成框架的 timeBetween 条件；没填日期就是不限时间。
     *
     * <p>入参先按 {@code yyyy-MM-dd} 严格解析成 {@link LocalDate} 再格式化回去，
     * 因此拼进 SQL 的只有规范化的数字与连字符，请求体里的原始文本不会残留，
     * 这一列也不接受任何来自前端的排序或列名。</p>
     */
    private String buildTimeCondition(ControllerLogQuery query) {
        String startText = query == null ? null : trimToNull(query.getStartDate());
        String endText = query == null ? null : trimToNull(query.getEndDate());
        if (startText == null && endText == null) {
            return null;
        }
        if (startText == null || endText == null) {
            throw new IllegalArgumentException("开始日期与结束日期需同时填写");
        }
        LocalDate start = parse(startText);
        LocalDate end = parse(endText);
        if (start == null || end == null) {
            throw new IllegalArgumentException("日期格式不正确，应为 yyyy-MM-dd");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        return SqlFieldTools.timeBetween(
                logBeanMapper.getTableName(),
                "createtime",
                start.format(DATE) + " 00:00:00",
                end.format(DATE) + " 23:59:59");
    }

    private LocalDate parse(String text) {
        try {
            return LocalDate.parse(text, DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
