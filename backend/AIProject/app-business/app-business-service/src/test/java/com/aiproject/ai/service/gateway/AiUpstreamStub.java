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
    volatile int responseStatus = 200;

    /**
     * 模拟真实 SSE 上游：按固定间隔慢速吐块，直到连接被关闭。
     * 网关若在浏览器断开后不关上游，服务端计费会继续产生，所以这里用
     * 「写失败 + latch」把上游视角的断开事实暴露给测试。
     */
    volatile boolean slowSse;
    final AtomicInteger streamedChunks = new AtomicInteger();
    final CountDownLatch clientGone = new CountDownLatch(1);

    /**
     * 模拟「接受请求、发出响应头，然后长时间不吐任何数据」的上游。
     * 用来验证网关能否在上游静默时靠心跳写探测浏览器断开，而不是等满 readTimeout。
     */
    volatile boolean silentSse;

    /**
     * 模拟「上游已受理并吐出一帧，然后连接异常中断」：
     * 声明一个远大于实际写入的 Content-Length 后关闭连接，
     * 客户端读到不足长度时会抛 Premature EOF，而不是把 EOF 当成正常结束。
     */
    volatile boolean failSseMidStream;

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
                if (silentSse) {
                    writeSilentSse(exchange);
                    return;
                }
                if (slowSse) {
                    writeSlowSse(exchange);
                    return;
                }
                if (failSseMidStream) {
                    writePartialSseThenFail(exchange);
                    return;
                }
                byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", responseContentType);
                exchange.sendResponseHeaders(responseStatus, response.length);
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

    /** 只发响应头，之后长时间不写正文；醒来的那次写入用来暴露「客户端已断开」 */
    private void writeSilentSse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        // JDK HttpServer 的响应头默认随首块正文一起发出；这里必须立刻 flush，
        // 否则「上游静默」在客户端看来是「连响应头都还没到」，测不到心跳阶段
        exchange.getResponseBody().flush();
        try {
            Thread.sleep(1500);
            exchange.getResponseBody().write("data: {}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        } catch (IOException clientGoneError) {
            clientGone.countDown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 吐出一帧正文后提前关闭；声明的长度大于实际字节，客户端会看到读取异常而非正常 EOF */
    private void writePartialSseThenFail(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 128 * 1024);
        exchange.getResponseBody().write(
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
