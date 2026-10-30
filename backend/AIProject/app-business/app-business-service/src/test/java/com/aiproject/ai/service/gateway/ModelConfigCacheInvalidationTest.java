package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import com.aiproject.ai.service.mapper.ModelsConfigBeanMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置变更后缓存立即可见的测试。
 *
 * <p>用真实 Spring 上下文验证 {@code @EventListener} 接线：两个 Resolver 只在
 * 收到 {@link ModelConfigChangedEvent} 时才丢缓存，如果注解或监听签名写错，
 * 功能会静默退化成「等 5 分钟 TTL」，单元测试直接调用方法则发现不了。</p>
 */
class ModelConfigCacheInvalidationTest {

    @Test
    void disablingRerankShouldTakeEffectImmediately() {
        ModelsConfigBeanHolder holder = mock(ModelsConfigBeanHolder.class);
        when(holder.queryList(any(ModelsConfigBean.class), isNull()))
                .thenReturn(Collections.singletonList(rerankConfig("key-old")));

        try (AnnotationConfigApplicationContext ctx = context(holder, mockMapper())) {
            RerankResolver resolver = ctx.getBean(RerankResolver.class);
            assertEquals("key-old", resolver.resolve().getApiKey());

            // 管理员已停用 rerank，但事件还没到：TTL 内继续用旧配置
            when(holder.queryList(any(ModelsConfigBean.class), isNull()))
                    .thenReturn(Collections.<ModelsConfigBean>emptyList());
            assertNotNull(resolver.resolve());

            ctx.publishEvent(new ModelConfigChangedEvent());
            assertNull(resolver.resolve(), "停用后应立刻停止精排，而不是等 5 分钟");
        }
    }

    @Test
    void priceChangeShouldTakeEffectImmediately() {
        ModelsConfigBeanMapper<ModelsConfigBean> mapper = mockMapper();
        when(mapper.select(isNull(), any(ModelsConfigBean.class)))
                .thenReturn(Collections.singletonList(priceConfig(100L)));

        try (AnnotationConfigApplicationContext ctx = context(mock(ModelsConfigBeanHolder.class), mapper)) {
            ModelPriceResolver resolver = ctx.getBean(ModelPriceResolver.class);
            assertEquals(100L, resolver.resolve("m").getInputPerMillion());

            when(mapper.select(isNull(), any(ModelsConfigBean.class)))
                    .thenReturn(Collections.singletonList(priceConfig(500L)));
            assertEquals(100L, resolver.resolve("m").getInputPerMillion(), "TTL 内仍是旧价");

            ctx.publishEvent(new ModelConfigChangedEvent());
            assertEquals(500L, resolver.resolve("m").getInputPerMillion(), "改价后应立刻反映到成本统计");
        }
    }

    /**
     * 向量模型配置同样缓存：每次检索都读库是纯浪费；
     * 后台改完后必须立即生效，否则「换了向量模型还按旧维度检索」会持续命中降级分支。
     */
    @Test
    void embeddingConfigShouldBeCachedAndInvalidatedOnChange() {
        ModelsConfigBeanHolder holder = mock(ModelsConfigBeanHolder.class);
        when(holder.queryList(any(ModelsConfigBean.class), isNull()))
                .thenReturn(Collections.singletonList(embeddingConfig("embed-v1")));

        try (AnnotationConfigApplicationContext ctx = context(holder, mockMapper())) {
            EmbeddingClient client = ctx.getBean(EmbeddingClient.class);
            assertEquals("embed-v1", client.resolve().getEmbedModel());
            assertEquals("embed-v1", client.resolve().getEmbedModel());
            verify(holder, times(1)).queryList(any(ModelsConfigBean.class), isNull());

            when(holder.queryList(any(ModelsConfigBean.class), isNull()))
                    .thenReturn(Collections.singletonList(embeddingConfig("embed-v2")));
            assertEquals("embed-v1", client.resolve().getEmbedModel(), "TTL 内仍是旧配置");

            ctx.publishEvent(new ModelConfigChangedEvent());
            assertEquals("embed-v2", client.resolve().getEmbedModel(), "改完向量模型应立即生效");
        }
    }

    /**
     * 未配置也是常见状态（没传文档的知识库）：null 同样要缓存，
     * 否则每次提问都落一次空查询；配好模型后靠事件立即恢复。
     */
    @Test
    void missingEmbeddingConfigShouldAlsoBeCached() {
        ModelsConfigBeanHolder holder = mock(ModelsConfigBeanHolder.class);
        when(holder.queryList(any(ModelsConfigBean.class), isNull()))
                .thenReturn(Collections.<ModelsConfigBean>emptyList());

        try (AnnotationConfigApplicationContext ctx = context(holder, mockMapper())) {
            EmbeddingClient client = ctx.getBean(EmbeddingClient.class);
            assertNull(client.resolve());
            assertNull(client.resolve());
            verify(holder, times(1)).queryList(any(ModelsConfigBean.class), isNull());

            when(holder.queryList(any(ModelsConfigBean.class), isNull()))
                    .thenReturn(Collections.singletonList(embeddingConfig("embed-v1")));
            ctx.publishEvent(new ModelConfigChangedEvent());
            assertEquals("embed-v1", client.resolve().getEmbedModel());
        }
    }

    private AnnotationConfigApplicationContext context(ModelsConfigBeanHolder holder,
                                                       ModelsConfigBeanMapper<ModelsConfigBean> mapper) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        // 用 registerSingleton 直接放入已完成 mock，避免 Spring 再对 mock 做字段注入
        ctx.getBeanFactory().registerSingleton("modelsConfigBeanHolder", holder);
        ctx.getBeanFactory().registerSingleton("modelsConfigBeanMapper", mapper);
        ctx.registerBean(RerankResolver.class);
        ctx.registerBean(ModelPriceResolver.class);
        ctx.registerBean(EmbeddingClient.class);
        ctx.refresh();
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private ModelsConfigBeanMapper<ModelsConfigBean> mockMapper() {
        return mock(ModelsConfigBeanMapper.class);
    }

    private ModelsConfigBean rerankConfig(String apiKey) {
        ModelsConfigBean c = new ModelsConfigBean();
        c.setProtocol("rerank");
        c.setEnable(true);
        c.setBaseUrl("http://127.0.0.1:9");
        c.setApiKey(apiKey);
        c.setUpstreamModel("rerank-model");
        return c;
    }

    private ModelsConfigBean priceConfig(Long inputPerMillion) {
        ModelsConfigBean c = new ModelsConfigBean();
        c.setName("m");
        c.setUpstreamModel("m");
        c.setPriceInputPerMillion(inputPerMillion);
        return c;
    }

    private ModelsConfigBean embeddingConfig(String embedModel) {
        ModelsConfigBean c = new ModelsConfigBean();
        c.setProtocol("openai");
        c.setEnable(true);
        c.setBaseUrl("http://127.0.0.1:9");
        c.setEmbedModel(embedModel);
        c.setName("embed");
        return c;
    }
}
