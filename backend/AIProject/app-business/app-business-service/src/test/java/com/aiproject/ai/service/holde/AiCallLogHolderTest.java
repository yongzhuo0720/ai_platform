package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiCallLogBean;
import com.aiproject.ai.service.mapper.AiCallLogBeanMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiCallLogHolderTest {

    private AiCallLogHolder holder;
    private AiCallLogBeanMapper<AiCallLogBean> mapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        holder = new AiCallLogHolder();
        mapper = mock(AiCallLogBeanMapper.class);
        ReflectionTestUtils.setField(holder, "callLogBeanMapper", mapper);
    }

    @AfterEach
    void tearDown() {
        holder.shutdown();
    }

    @Test
    void recordReturnsOnlyAfterEntryIsPersisted() {
        AtomicReference<AiCallLogBean> saved = new AtomicReference<AiCallLogBean>();
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).install(any(AiCallLogBean.class));

        AiCallLogBean entry = entry();
        holder.record(entry);

        assertSame(entry, saved.get());
        assertNotNull(saved.get().getCreatetime());
        assertEquals(Boolean.TRUE, saved.get().getEnable());
        assertEquals("u1", saved.get().getLastuser());
    }

    @Test
    void interruptedCallerDoesNotLoseAuditEntry() throws Exception {
        final CountDownLatch written = new CountDownLatch(1);
        doAnswer(invocation -> {
            written.countDown();
            return 1;
        }).when(mapper).install(any(AiCallLogBean.class));

        Thread.currentThread().interrupt();
        holder.record(entry());

        // 不能把中断标志留给调用方：网关紧接着还要结算预扣（访问 Redis）
        assertFalse(Thread.currentThread().isInterrupted());
        assertTrue(written.await(2, TimeUnit.SECONDS));
        verify(mapper).install(any(AiCallLogBean.class));
    }

    private static AiCallLogBean entry() {
        AiCallLogBean entry = new AiCallLogBean();
        entry.setUserId("u1");
        entry.setModule("chat");
        entry.setEndpoint("chat-messages");
        entry.setStatus("success");
        return entry;
    }
}