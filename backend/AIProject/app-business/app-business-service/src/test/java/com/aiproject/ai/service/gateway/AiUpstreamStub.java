package com.aiproject.ai.service.gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

class AiUpstreamStub implements AutoCloseable {
    private final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile String query;
    volatile byte[] requestBody;
    volatile String responseBody = "{}";

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
                byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
