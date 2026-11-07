package com.aiproject.app.start.config;

import com.aiproject.ai.service.gateway.AiGatewayProperties;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * AI 网关上游 HTTP 客户端。
 *
 * <p>连接池上限与中继线程数对齐：一个中继线程同一时刻只占一条上游连接，
 * 多出来的连接只可能是已用完待回收的。闲置连接 30 秒回收，
 * 避免 Dify 侧主动断开后池里留下死连接。</p>
 *
 * <p>必须关闭自动重试：网关转发的是可能已计费的生成请求，
 * 连接中途失败后静默重发会让上游重复生成、用户被重复扣费。</p>
 */
@Configuration
public class AiGatewayHttpClientConfig {

    private CloseableHttpClient httpClient;

    @Bean
    public CloseableHttpClient aiGatewayHttpClient(AiGatewayProperties properties) {
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
        manager.setMaxTotal(Math.max(16, properties.getRelayMaxThreads()));
        manager.setDefaultMaxPerRoute(Math.max(16, properties.getRelayMaxThreads()));
        manager.setValidateAfterInactivity(5000);

        httpClient = HttpClients.custom()
                .setConnectionManager(manager)
                .disableAutomaticRetries()
                .evictExpiredConnections()
                .evictIdleConnections(30, TimeUnit.SECONDS)
                .build();
        return httpClient;
    }

    @PreDestroy
    public void shutdown() {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception ignored) {
                // 停机阶段关闭失败无需处理
            }
        }
    }
}