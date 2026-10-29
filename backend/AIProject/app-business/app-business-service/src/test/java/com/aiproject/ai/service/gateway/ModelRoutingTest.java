package com.aiproject.ai.service.gateway;

import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.holde.ModelsConfigBeanHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRoutingTest {
    @Test
    void disabledModelCannotBeResolvedOrAdvertisedAsAvailable() {
        ModelGatewayResolver resolver = new ModelGatewayResolver();
        resolver.modelsConfigBeanHolder = holder(model("openai", false));

        assertNull(resolver.resolve(1));
        assertFalse(resolver.hasDirectModels());
    }

    @Test
    void legacyModelWithoutEnableFlagRemainsAvailable() {
        ModelGatewayResolver resolver = new ModelGatewayResolver();
        resolver.modelsConfigBeanHolder = holder(model("openai", null));

        assertNotNull(resolver.resolve(1));
    }

    @Test
    void disabledRerankConfigurationIsIgnored() {
        RerankResolver resolver = new RerankResolver();
        resolver.modelsConfigBeanHolder = holder(model("rerank", false));

        assertNull(resolver.resolve());
    }

    @Test
    void validRerankOrderIsPreserved() throws Exception {
        try (AiUpstreamStub upstream = new AiUpstreamStub()) {
            upstream.responseBody = "{\"results\":[{\"index\":1},{\"index\":0}]}";
            RerankResolver resolver = new RerankResolver();

            assertEquals(Arrays.asList(1, 0), resolver.rerank(
                    new RerankResolver.ResolvedRerank(upstream.baseUrl(), "test-key", "rerank"),
                    "question", Arrays.asList("first", "second")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"results\":[{\"index\":0},{\"index\":0}]}",
            "{\"results\":[{\"index\":-1},{\"index\":0}]}",
            "{\"results\":[{\"index\":0},{\"index\":2}]}"
    })
    void invalidRerankOrderFallsBackToRecallOrder(String response) throws Exception {
        try (AiUpstreamStub upstream = new AiUpstreamStub()) {
            upstream.responseBody = response;

            assertNull(new RerankResolver().rerank(
                    new RerankResolver.ResolvedRerank(upstream.baseUrl(), "test-key", "rerank"),
                    "question", Arrays.asList("first", "second")));
        }
    }

    private ModelsConfigBeanHolder holder(ModelsConfigBean config) {
        ModelsConfigBeanHolder holder = mock(ModelsConfigBeanHolder.class);
        when(holder.queryList(any(ModelsConfigBean.class), isNull())).thenReturn(Collections.singletonList(config));
        return holder;
    }

    private ModelsConfigBean model(String protocol, Boolean enabled) {
        ModelsConfigBean config = new ModelsConfigBean();
        config.setProtocol(protocol);
        config.setEnable(enabled);
        config.setBaseUrl("http://localhost/v1");
        config.setApiKey("test-key");
        config.setUpstreamModel("model");
        return config;
    }
}
