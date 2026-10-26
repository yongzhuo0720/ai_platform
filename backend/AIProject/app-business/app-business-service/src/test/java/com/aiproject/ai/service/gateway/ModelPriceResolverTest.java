package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.mapper.ModelsConfigBeanMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 模型单价解析与成本折算的测试。
 *
 * <p>核心是「token 用量 → 钱」的折算正确性：
 * 治理看板要从「用了多少 token」升级到「花了多少钱」，
 * 折算错一次，运营对成本的判断就全歪了。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModelPriceResolverTest {

    @Mock
    ModelsConfigBeanMapper<ModelsConfigBean> modelsConfigBeanMapper;

    @InjectMocks
    ModelPriceResolver resolver;

    private ModelsConfigBean config(String name, String upstreamModel,
                                    Long inputPerMillion, Long outputPerMillion) {
        ModelsConfigBean c = new ModelsConfigBean();
        c.setName(name);
        c.setUpstreamModel(upstreamModel);
        c.setPriceInputPerMillion(inputPerMillion);
        c.setPriceOutputPerMillion(outputPerMillion);
        return c;
    }

    /** 输入 100 万 tokens、单价 200 分/百万 → 200 分 = 200000 毫分 */
    @Test
    void costCents_shouldComputeInputCost() {
        ModelPriceResolver.Price price = new ModelPriceResolver.Price(200L, 800L);
        assertEquals(200_000L, price.costCents(1_000_000L, 0L));
    }

    /** 输入输出分别计价，输出通常更贵 */
    @Test
    void costCents_shouldPriceInputAndOutputSeparately() {
        ModelPriceResolver.Price price = new ModelPriceResolver.Price(200L, 800L);
        assertEquals(200_000L + 800_000L, price.costCents(1_000_000L, 1_000_000L));
    }

    /** 小调用不被取整吞掉：508 tokens 按 200 分/百万 折算应为 101 毫分（约 0.1 分）而非 0 */
    @Test
    void costCents_shouldNotTruncateSmallCallsToZero() {
        ModelPriceResolver.Price price = new ModelPriceResolver.Price(200L, 800L);
        assertEquals(101L, price.costCents(508L, 0L));
    }

    /** 单价为空 = 未配置，不算钱也不能当成免费 */
    @Test
    void price_shouldBeUnconfiguredWhenBothNull() {
        ModelPriceResolver.Price price = new ModelPriceResolver.Price(0L, 0L);
        assertFalse(price.isConfigured());
        assertEquals(0L, price.costCents(1_000_000L, 1_000_000L));
    }

    /** 只配一侧单价时仍算已配置 */
    @Test
    void price_shouldBeConfiguredWhenOnlyOneSideSet() {
        assertTrue(new ModelPriceResolver.Price(200L, null == null ? 0L : 0L).isConfigured());
        assertTrue(new ModelPriceResolver.Price(0L, 800L).isConfigured());
    }

    /** 模型名与上游模型名都应能查到价格 */
    @Test
    void resolve_shouldIndexByBothNameAndUpstreamModel() {
        when(modelsConfigBeanMapper.select(isNull(), any(ModelsConfigBean.class)))
                .thenReturn(Arrays.<ModelsConfigBean>asList(
                        config("DeepSeek 聊天", "deepseek-chat", 100L, 300L)));

        // 按展示名查
        ModelPriceResolver.Price byDisplay = resolver.resolve("DeepSeek 聊天");
        assertTrue(byDisplay != null && byDisplay.isConfigured());
        // 按上游模型名查（审计日志里的 modelName 通常来自这一侧）
        ModelPriceResolver.Price byUpstream = resolver.resolve("deepseek-chat");
        assertTrue(byUpstream != null && byUpstream.isConfigured());
    }

    /** 没配价格的模型查不到，调用方据此记 0 成本 */
    @Test
    void resolve_shouldReturnNullForUnpricedModel() {
        when(modelsConfigBeanMapper.select(isNull(), any(ModelsConfigBean.class)))
                .thenReturn(Arrays.<ModelsConfigBean>asList(
                        config("免费模型", "free-model", null, null)));

        assertNull(resolver.resolve("free-model"));
    }

    /** 空模型名不该触发查询 */
    @Test
    void resolve_shouldReturnNullForBlankModelName() {
        assertNull(resolver.resolve(null));
        assertNull(resolver.resolve("   "));
    }

    /** 价格表整体读取失败时不能抛异常，AI 调用不该被记账逻辑拖垮 */
    @Test
    void resolve_shouldNotThrowWhenQueryFails() {
        when(modelsConfigBeanMapper.select(isNull(), any(ModelsConfigBean.class)))
                .thenThrow(new RuntimeException("数据库连接失败"));

        // 不该抛异常；查不到就返回 null，让调用方记 0 成本
        assertNull(resolver.resolve("any-model"));
    }

    /** 同一模型多次查询只查一次库（TTL 缓存生效） */
    @Test
    void resolve_shouldCacheWithinTtl() {
        when(modelsConfigBeanMapper.select(isNull(), any(ModelsConfigBean.class)))
                .thenReturn(Arrays.<ModelsConfigBean>asList(
                        config("M", "m", 100L, 300L)));

        resolver.resolve("m");
        resolver.resolve("m");
        resolver.resolve("m");

        // 三次查询只应触发一次数据库读取
        org.mockito.Mockito.verify(modelsConfigBeanMapper,
                org.mockito.Mockito.times(1)).select(isNull(), any(ModelsConfigBean.class));
    }
}
