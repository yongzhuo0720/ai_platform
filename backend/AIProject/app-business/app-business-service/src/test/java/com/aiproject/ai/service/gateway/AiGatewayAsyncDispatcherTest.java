package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.context.request.async.WebAsyncUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AiGatewayAsyncDispatcherTest {

    private AiGatewayAsyncDispatcher dispatcher;
    private AiGatewayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiGatewayProperties();
        dispatcher = new AiGatewayAsyncDispatcher();
        dispatcher.properties = properties;
    }

    @Test
    void runsInlineWhenServletAsyncIsUnsupported() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncSupported(false);
        ReflectionTestUtils.setField(dispatcher, "executor", mock(AsyncTaskExecutor.class));
        AtomicBoolean ran = new AtomicBoolean(false);

        WebAsyncTask<Void> task = dispatcher.dispatch("chat", request, gate -> ran.set(true));

        assertNull(task);
        assertTrue(ran.get());
    }

    @Test
    void runsInlineWhenExecutorBeanIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AtomicBoolean ran = new AtomicBoolean(false);

        assertNull(dispatcher.dispatch("chat", request, gate -> ran.set(true)));
        assertTrue(ran.get());
    }

    @Test
    void runsInlineWhenAsyncDispatchIsDisabled() {
        properties.setAsyncDispatchEnabled(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        ReflectionTestUtils.setField(dispatcher, "executor", mock(AsyncTaskExecutor.class));
        AtomicBoolean ran = new AtomicBoolean(false);

        assertNull(dispatcher.dispatch("chat", request, gate -> ran.set(true)));
        assertTrue(ran.get());
    }

    @Test
    void returnsAsyncTaskWithDerivedTimeoutAndSwallowsRelayErrors() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncSupported(true);
        ReflectionTestUtils.setField(dispatcher, "executor", mock(AsyncTaskExecutor.class));

        WebAsyncTask<Void> task = dispatcher.dispatch("chat", request, gate -> {
            throw new IllegalStateException("boom");
        });

        assertNotNull(task);
        // 600000 总时长 + 300000 读超时 + 10000 建连 + 60000 收尾余量
        assertEquals(970_000L, task.getTimeout().longValue());
        // 异常不能穿回 Spring：响应可能已提交，抛出去只会覆盖成 500
        assertNull(task.getCallable().call());
    }

    @Test
    void timeoutIsDisabledWhenStreamDurationIsUnlimited() {
        properties.setMaxStreamMs(0);

        assertEquals(0L, dispatcher.asyncTimeoutMs());
    }

    @Test
    void containerErrorClosesGateBeforeResponseIsRecycled() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AsyncResponseGate gate = dispatcher.createGate("chat", request, 1000L);
        CallableProcessingInterceptor hook = hookOf(request, gate);

        // 返回 RESULT_NONE 让 Spring 继续用原始错误走自己的错误处理
        assertSame(CallableProcessingInterceptor.RESULT_NONE,
                hook.handleError(null, null, new IllegalStateException("client gone")));
        assertTrue(gate.isCancelled());
        assertFalse(gate.runIfActive(new AsyncResponseGate.ResponseAction() {
            @Override
            public void run() {
                throw new AssertionError("门闸关闭后不允许再写响应");
            }
        }));
    }

    @Test
    void containerTimeoutClosesGateAndKeepsDefaultTimeoutResult() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AsyncResponseGate gate = dispatcher.createGate("chat", request, 1000L);
        CallableProcessingInterceptor hook = hookOf(request, gate);

        assertSame(CallableProcessingInterceptor.RESULT_NONE, hook.handleTimeout(null, null));
        assertTrue(gate.isCancelled());
    }

    @Test
    void cancelledGateBlocksInFlightWriteAfterLockReleased() throws Exception {
        final AsyncResponseGate gate = AsyncResponseGate.create();
        final java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    gate.runIfActive(new AsyncResponseGate.ResponseAction() {
                        @Override
                        public void run() throws java.io.IOException {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new java.io.IOException(e);
                            }
                        }
                    });
                } catch (java.io.IOException ignored) {
                    // 测试只关心顺序
                }
            }
        });
        writer.start();
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));

        Thread canceller = new Thread(new Runnable() {
            @Override
            public void run() {
                gate.cancel();
            }
        });
        canceller.start();
        // cancel 必须等正在进行的这次写入结束，否则容器会先回收 response
        canceller.join(200);
        assertTrue(canceller.isAlive());

        release.countDown();
        canceller.join(2000);
        assertFalse(canceller.isAlive());
        writer.join(2000);
        assertFalse(gate.runIfActive(new AsyncResponseGate.ResponseAction() {
            @Override
            public void run() {
                throw new AssertionError("门闸关闭后不允许再写响应");
            }
        }));
    }

    private static CallableProcessingInterceptor hookOf(MockHttpServletRequest request,
                                                        AsyncResponseGate gate) {
        WebAsyncManager manager = WebAsyncUtils.getAsyncManager(request);
        CallableProcessingInterceptor hook = manager.getCallableInterceptor(gate);
        assertNotNull(hook, "调度器必须为每个异步请求注册门闸钩子");
        return hook;
    }
}
