package com.aiproject.ai.service.gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

class AiUpstreamStub implements AutoCloseable {
    /** slowSse 模式下最多写多少块，写不完说明客户端断开把连接关了 */
    static final int SLOW_SSE_ITERATIONS = 200;

    private final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile String query;
    volatile byte[] requestBody;
    volatile String responseBody = "{}";
    volatile String responseContentType = "application/json";

    /**
     * 模拟真实 SSE 上游：按固定间隔慢速吐块，直到连接被关闭。
     * 网关若在浏览器断开后不关上游，服务端计费会继续产生，所以这里用
     * 「写失败 + latch」把上游视角的断开事实暴露给测试。
     */
    volatile boolean slowSse;
    final AtomicInteger streamedChunks = new AtomicInteger();
    final CountDownLatch clientGone = new CountDownLatch(1);

    AiUpstreamStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                query = exchange.getRequestURI().getRawQuery();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream input = exchange.getRequestBody()) {
                    byte[] chunk = new byte[4096];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        buffer.write(chunk, 0, count);
                    }
                }
                requestBody = buffer.toByteArray();
                calls.incrementAndGet();
                if (slowSse) {
                    writeSlowSse(exchange);
                    return;
                }
                byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", responseContentType);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void writeSlowSse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        // 块大一点，让「对端已关闭」尽快表现为写失败，而不是一直塞进内核缓冲
        byte[] padding = new byte[32 * 1024];
        java.util.Arrays.fill(padding, (byte) 'x');
        try {
            for (int i = 0; i < SLOW_SSE_ITERATIONS; i++) {
                exchange.getResponseBody().write(
                        ("data: {\"choices\":[{\"delta\":{\"content\":\"" + i + "\"}}]}\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().write(padding);
                exchange.getResponseBody().flush();
                streamedChunks.incrementAndGet();
                Thread.sleep(10);
            }
        } catch (IOException clientGoneError) {
            clientGone.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
