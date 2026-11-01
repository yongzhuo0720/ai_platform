package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.UserControllerLogBean;
import com.aiproject.ai.service.dto.ControllerLogQuery;
import com.aiproject.ai.service.mapper.UserControllerLogBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作日志读接口的权限与入参校验。
 *
 * <p>这张表记录的是全站所有人的接口调用行为，所以最要紧的两条是：
 * 不是超管就一条都查不到（而且要在校验阶段就返回，连 SQL 都不该发出去）；
 * 以及前端只能控制「翻第几页」和「日期区间」，控制不了排序、列名和 WHERE 条件。</p>
 */
class ControllerLogHolderTest {

    private static final Integer SUCCESS_CODE = com.heneng.hnsp.common.model.CodeEnum.SUCCESS.getCode();

    private static final String ADMIN_UID = "admin-uid";

    private UserControllerLogBeanMapper<UserControllerLogBean> mapper;

    private MyUserBaseDbHolder userHolder;

    private ControllerLogHolder holder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(UserControllerLogBeanMapper.class);
        userHolder = mock(MyUserBaseDbHolder.class);
        holder = new ControllerLogHolder();
        holder.logBeanMapper = mapper;
        holder.myUserBaseDbHolder = userHolder;

        when(userHolder.isAdmin(ADMIN_UID)).thenReturn(true);
        when(mapper.getTableName()).thenReturn("usercontrollerlog");
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminGetsPagedRows() {
        PageResult<UserControllerLogBean> page = new PageResult<UserControllerLogBean>();
        UserControllerLogBean row = new UserControllerLogBean();
        row.setUaccount("zhangsan");
        row.setAction("登录系统");
        page.setContent(Collections.singletonList(row));
        when(mapper.selectPage(any(PageRequest.class), any(UserControllerLogBean.class)))
                .thenReturn(Result.succeed(page, "查询成功"));

        Result<PageResult<UserControllerLogBean>> result = holder.query(ADMIN_UID, new ControllerLogQuery());

        assertTrue(ok(result), result.getResp_msg());
        assertEquals(1, result.getDatas().getContent().size());
        assertEquals("zhangsan", result.getDatas().getContent().get(0).getUaccount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "normal-user-uid"})
    @SuppressWarnings("unchecked")
    void nonAdminAndAnonymousGetNothingAndNoSql(String uid) {
        when(userHolder.isAdmin(uid)).thenReturn(false);

        Result<PageResult<UserControllerLogBean>> result = holder.query(uid, new ControllerLogQuery());

        assertFalse(ok(result), "非超管必须拒绝");
        assertNull(result.getDatas());
        verify(mapper, never()).selectPage(any(PageRequest.class), any(UserControllerLogBean.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullUidIsRejectedWithoutQuerying() {
        when(userHolder.isAdmin(null)).thenReturn(false);

        Result<PageResult<UserControllerLogBean>> result = holder.query(null, null);

        assertFalse(ok(result));
        verify(mapper, never()).selectPage(any(PageRequest.class), any(UserControllerLogBean.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagingDefaultsAndSortAreFixedOnServerSide() {
        stubEmptyPage();

        holder.query(ADMIN_UID, null);

        PageRequest sent = capturePageRequest();
        assertEquals(Integer.valueOf(1), sent.getPageNum());
        assertEquals(Integer.valueOf(20), sent.getPageSize());
        // 排序由后端固定：请求体里没有 order/desc，注入面就不存在
        assertEquals("iid", sent.getOrder());
        assertEquals("desc", sent.getDesc());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageSizeIsClampedAndBadPageNumFallsBackToOne() {
        stubEmptyPage();

        ControllerLogQuery query = new ControllerLogQuery();
        query.setPageNum(0);
        query.setPageSize(999999);
        holder.query(ADMIN_UID, query);

        PageRequest sent = capturePageRequest();
        assertEquals(Integer.valueOf(1), sent.getPageNum());
        assertEquals(Integer.valueOf(200), sent.getPageSize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-1", "2026/10/01", "2026-13-45", "'2026-10-01' or 1=1 --"})
    @SuppressWarnings("unchecked")
    void badDateFormatIsRejectedBeforeSql(String startDate) {
        ControllerLogQuery query = new ControllerLogQuery();
        query.setStartDate(startDate);
        query.setEndDate("2026-10-05");

        Result<PageResult<UserControllerLogBean>> result = holder.query(ADMIN_UID, query);

        assertFalse(ok(result), "非法日期必须拒绝: " + startDate);
        verify(mapper, never()).selectPage(any(PageRequest.class), any(UserControllerLogBean.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void halfRangeIsRejected() {
        ControllerLogQuery query = new ControllerLogQuery();
        query.setStartDate("2026-10-01");

        Result<PageResult<UserControllerLogBean>> result = holder.query(ADMIN_UID, query);

        assertFalse(ok(result));
        assertTrue(result.getResp_msg().contains("同时"), result.getResp_msg());
        verify(mapper, never()).selectPage(any(PageRequest.class), any(UserControllerLogBean.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reversedRangeIsRejected() {
        ControllerLogQuery query = new ControllerLogQuery();
        query.setStartDate("2026-10-05");
        query.setEndDate("2026-10-01");

        Result<PageResult<UserControllerLogBean>> result = holder.query(ADMIN_UID, query);

        assertFalse(ok(result));
        assertTrue(result.getResp_msg().contains("不能晚于"), result.getResp_msg());
    }

    @Test
    @SuppressWarnings("unchecked")
    void validRangeBecomesNormalizedTimeCondition() {
        stubEmptyPage();

        ControllerLogQuery query = new ControllerLogQuery();
        query.setStartDate(" 2026-10-01 ");
        query.setEndDate("2026-10-02 ");
        holder.query(ADMIN_UID, query);

        ArgumentCaptor<String> condition = ArgumentCaptor.forClass(String.class);
        verify(mapper).selectPage(any(PageRequest.class), any(UserControllerLogBean.class), condition.capture());

        String sql = condition.getValue();
        assertTrue(sql.contains("usercontrollerlog"), sql);
        assertTrue(sql.contains("createtime"), sql);
        assertTrue(sql.contains("2026-10-01 00:00:00"), sql);
        assertTrue(sql.contains("2026-10-02 23:59:59"), sql);
        assertFalse(sql.contains("--"), "条件里不该出现 SQL 注释符: " + sql);
        assertFalse(sql.toLowerCase().contains("or "), "条件里不该拼进布尔表达式: " + sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyDateMeansNoTimeCondition() {
        stubEmptyPage();

        holder.query(ADMIN_UID, new ControllerLogQuery());

        verify(mapper).selectPage(any(PageRequest.class), any(UserControllerLogBean.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapperReturningNullIsReportedAsFailure() {
        when(mapper.selectPage(any(PageRequest.class), any(UserControllerLogBean.class))).thenReturn(null);

        Result<PageResult<UserControllerLogBean>> result = holder.query(ADMIN_UID, new ControllerLogQuery());

        assertFalse(ok(result));
        assertNotNull(result.getResp_msg());
    }

    @SuppressWarnings("unchecked")
    private void stubEmptyPage() {
        PageResult<UserControllerLogBean> page = new PageResult<UserControllerLogBean>();
        page.setContent(Collections.<UserControllerLogBean>emptyList());
        when(mapper.selectPage(any(PageRequest.class), any(UserControllerLogBean.class)))
                .thenReturn(Result.succeed(page, "查询成功"));
    }

    private PageRequest capturePageRequest() {
        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(mapper).selectPage(captor.capture(), any(UserControllerLogBean.class));
        return captor.getValue();
    }

    private boolean ok(Result<?> result) {
        return result != null && SUCCESS_CODE.equals(result.getResp_code());
    }
}
