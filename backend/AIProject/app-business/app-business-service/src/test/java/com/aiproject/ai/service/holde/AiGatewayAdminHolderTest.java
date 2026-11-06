package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.gateway.AiGatewayConfigResolver;
import com.aiproject.ai.service.gateway.AiGatewayProperties;
import com.aiproject.ai.service.gateway.ModelGatewayResolver;
import com.aiproject.ai.service.gateway.dto.AiGatewayHealth;
import com.aiproject.ai.service.gateway.dto.AiGatewayModelStat;
import com.aiproject.ai.service.gateway.dto.AiGatewaySummary;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 治理看板的按模型聚合与健康面板。
 *
 * <p>重点不是「统计跑通」，而是口径：
 * 失败包含 failed / aborted / blocked 三种状态，成功率为
 * {@code (calls - failed) / calls}；P95 用最近秩（ceil(n*0.95)-1），
 * 与全局耗时口径一致。</p>
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayAdminHolderTest {

    private static final String ADMIN = "admin";
    private static final Integer SUCCESS_CODE =
            com.heneng.hnsp.common.model.CodeEnum.SUCCESS.getCode();

    @Mock
    AiCallLogBeanMapper<AiCallLogBean> callLogBeanMapper;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @Mock
    AiGatewayProperties properties;

    @Mock
    AiGatewayConfigResolver configResolver;

    @Mock
    ModelGatewayResolver modelGatewayResolver;

    @Mock
    ModelsConfigBeanHolder modelsConfigBeanHolder;

    @InjectMocks
    AiGatewayAdminHolder holder;

    private List<AiCallLogBean> rows;

    @BeforeEach
    void setUp() {
        lenient().when(myUserBaseDbHolder.isAdmin(ADMIN)).thenReturn(true);
        lenient().when(callLogBeanMapper.getTableName()).thenReturn("aicalllogbean");
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(properties.getRateLimitPerMinute()).thenReturn(120);
        lenient().when(properties.getDefaultDailyTokenQuota()).thenReturn(200000L);
        lenient().when(properties.getQuotaReserveTokens()).thenReturn(1500L);
        lenient().when(configResolver.moduleName(anyString())).thenReturn("模块");
        lenient().when(configResolver.resolve(anyString())).thenReturn(null);
        lenient().when(modelGatewayResolver.hasDirectModels()).thenReturn(true);

        rows = new ArrayList<AiCallLogBean>();
        rows.add(log("gpt-a", "success", 100L, 10L, 10));
        rows.add(log("gpt-a", "success", 200L, 10L, 0));
        rows.add(log("gpt-a", "failed", 300L, 10L, 0));
        rows.add(log("gpt-a", "blocked", 400L, 10L, 0));
        rows.add(log("gpt-b", "success", 50L, 10L, 0));
    }

    /** 按模型给出成功率、平均耗时、P95 与成本；失败含 blocked */
    @Test
    @SuppressWarnings("unchecked")
    void summary_shouldAggregatePerModelSuccessRateAndP95() {
        stubRows(rows);

        Result<AiGatewaySummary> result = holder.summary(ADMIN, null);

        assertEquals(SUCCESS_CODE, result.getResp_code());
        AiGatewaySummary summary = result.getDatas();
        assertEquals(5, summary.getTotalCalls());
        assertEquals(3, summary.getSuccessCalls());
        assertEquals(1, summary.getFailedCalls());
        assertEquals(1, summary.getBlockedCalls());
        assertEquals(60.0, summary.getSuccessRate(), 0.001);
        assertTrue(summary.isHasUnpricedModels());

        AiGatewayModelStat modelA = summary.getModels().get(0);
        assertEquals("gpt-a", modelA.getModel());
        assertEquals(4, modelA.getCalls());
        assertEquals(2, modelA.getFailed());
        assertEquals(50.0, modelA.getSuccessRate(), 0.001);
        assertEquals(250L, modelA.getAvgLatencyMs());
        // 最近秩：ceil(4 * 0.95) - 1 = 3 → 排序后的第 4 个，即 400ms
        assertEquals(400L, modelA.getP95LatencyMs());
        assertEquals(10L, modelA.getCostCents());
        assertTrue(modelA.isPriced());

        AiGatewayModelStat modelB = summary.getModels().get(1);
        assertEquals("gpt-b", modelB.getModel());
        assertEquals(1, modelB.getCalls());
        assertEquals(100.0, modelB.getSuccessRate(), 0.001);
        assertEquals(50L, modelB.getP95LatencyMs());
        assertFalse(modelB.isPriced());
    }

    /** 没有耗时样本时 P95 为 0，不应把 0 混进平均值 */
    @Test
    @SuppressWarnings("unchecked")
    void summary_shouldKeepZeroLatencyWhenSamplesMissing() {
        AiCallLogBean noLatency = log("gpt-c", "success", null, 5L, 0);
        stubRows(Arrays.asList(noLatency));

        AiGatewaySummary summary = holder.summary(ADMIN, null).getDatas();

        AiGatewayModelStat model = summary.getModels().get(0);
        assertEquals(0L, model.getAvgLatencyMs());
        assertEquals(0L, model.getP95LatencyMs());
        assertEquals(100.0, model.getSuccessRate(), 0.001);
    }

    /** 健康面板只暴露配置完整性，不包含 baseUrl / apiKey */
    @Test
    @SuppressWarnings("unchecked")
    void health_shouldExposeConfigCompletenessWithoutSecrets() {
        ModelsConfigBean complete = config(1L, "完整模型", true, "sk-secret", "https://example.invalid", "m1", 100L, 200L);
        ModelsConfigBean incomplete = config(2L, "缺密钥", false, "", "https://example.invalid", "m2", null, null);
        when(modelsConfigBeanHolder.queryList(any(ModelsConfigBean.class), isNull()))
                .thenReturn(Arrays.asList(complete, incomplete));
        stubRows(rows);

        Result<AiGatewayHealth> result = holder.health(ADMIN);

        assertEquals(SUCCESS_CODE, result.getResp_code());
        AiGatewayHealth health = result.getDatas();
        assertTrue(health.isGatewayEnabled());
        assertEquals(1500L, health.getQuotaReserveTokens());
        assertEquals(2, health.getModels().size());

        AiGatewayHealth.ModelHealth first = health.getModels().get(0);
        assertEquals(Long.valueOf(1L), first.getIid());
        assertTrue(first.isEnabled());
        assertTrue(first.isReady());
        assertTrue(first.isPriced());

        AiGatewayHealth.ModelHealth second = health.getModels().get(1);
        assertFalse(second.isEnabled());
        assertFalse(second.isReady());
        assertFalse(second.isPriced());

        assertNotNull(health.getToday());
        assertEquals(5, health.getToday().getTotalCalls());
        assertTrue(health.getCheckedAt() > 0);
    }

    /** 非管理员既拿不到汇总，也拿不到健康面板与模型配置 */
    @Test
    @SuppressWarnings("unchecked")
    void nonAdmin_shouldBeRejectedBeforeAnyQuery() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        assertFalse(SUCCESS_CODE.equals(holder.summary("u1", null).getResp_code()));
        assertNull(holder.health("u1").getDatas());

        verifyNoInteractions(callLogBeanMapper);
        verifyNoInteractions(modelsConfigBeanHolder);
    }

    @SuppressWarnings("unchecked")
    private void stubRows(List<AiCallLogBean> content) {
        PageResult<AiCallLogBean> page = new PageResult<AiCallLogBean>();
        page.setContent(content);
        page.setTotalSize(content.size());
        when(callLogBeanMapper.selectPage(any(PageRequest.class), any(AiCallLogBean.class), anyString()))
                .thenReturn(Result.succeed(page, "查询成功"));
    }

    private AiCallLogBean log(String model, String status, Long latencyMs,
                              Long totalTokens, long costCents) {
        AiCallLogBean row = new AiCallLogBean();
        row.setModelName(model);
        row.setStatus(status);
        row.setLatencyMs(latencyMs);
        row.setTotalTokens(totalTokens);
        row.setCostCents(costCents);
        row.setModule("models");
        row.setUserId("u1");
        row.setCreatetime(new java.sql.Timestamp(System.currentTimeMillis()));
        return row;
    }

    private ModelsConfigBean config(Long iid, String name, boolean enabled, String apiKey,
                                    String baseUrl, String upstreamModel,
                                    Long inputPrice, Long outputPrice) {
        ModelsConfigBean bean = new ModelsConfigBean();
        bean.setIid(iid);
        bean.setName(name);
        bean.setEnable(enabled);
        bean.setProtocol("openai");
        bean.setApiKey(apiKey);
        bean.setBaseUrl(baseUrl);
        bean.setUpstreamModel(upstreamModel);
        bean.setPriceInputPerMillion(inputPrice);
        bean.setPriceOutputPerMillion(outputPrice);
        return bean;
    }
}
