package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 跨实例模型配置同步的测试。
 *
 * <p>核心契约：只有「版本号真的变了」才广播失效事件；首次读到的版本只建立基线；
 * Redis 故障不得抛异常、也不得把基线弄丢（否则恢复后的第一次写入会被当成基线吞掉）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModelConfigVersionWatcherTest {

    @Mock
    StringRedisTemplate stringRedisTemplate;

    @Mock
    ValueOperations<String, String> valueOperations;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    ModelConfigVersionWatcher watcher;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void firstPollOnlyEstablishesBaseline() {
        when(valueOperations.get(ModelConfigVersionWatcher.VERSION_KEY)).thenReturn("5");

        watcher.pollVersion();
        watcher.pollVersion();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void versionChangePublishesInvalidation() {
        when(valueOperations.get(ModelConfigVersionWatcher.VERSION_KEY))
                .thenReturn("5", "5", "6");

        watcher.pollVersion();
        watcher.pollVersion();
        verifyNoInteractions(eventPublisher);

        watcher.pollVersion();
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }

    /** 版本键缺失按 0 处理：清空 Redis 后第一次写入（0 -> 1）也要让其他实例失效 */
    @Test
    void missingVersionKeyTreatedAsZeroAndFirstWriteIsNoticed() {
        when(valueOperations.get(ModelConfigVersionWatcher.VERSION_KEY))
                .thenReturn(null, "1");

        watcher.pollVersion();
        verifyNoInteractions(eventPublisher);

        watcher.pollVersion();
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }

    /** Redis 故障只跳过本轮，基线必须保留：恢复后 5 -> 6 仍要触发失效 */
    @Test
    void redisFailureKeepsBaselineAndRecovers() {
        when(valueOperations.get(ModelConfigVersionWatcher.VERSION_KEY))
                .thenReturn("5")
                .thenThrow(new RuntimeException("redis down"))
                .thenReturn("6");

        watcher.pollVersion();
        assertDoesNotThrow(watcher::pollVersion);
        verifyNoInteractions(eventPublisher);

        watcher.pollVersion();
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }

    @Test
    void bumpVersionIncrementsSharedKey() {
        when(valueOperations.increment(ModelConfigVersionWatcher.VERSION_KEY)).thenReturn(7L);

        watcher.bumpVersion();

        verify(valueOperations).increment(ModelConfigVersionWatcher.VERSION_KEY);
    }

    @Test
    void bumpFailureDoesNotThrow() {
        when(valueOperations.increment(ModelConfigVersionWatcher.VERSION_KEY))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(watcher::bumpVersion);
    }

    /** 监听方抛异常不能打断调度线程 */
    @Test
    void listenerFailureDoesNotBreakPolling() {
        when(valueOperations.get(ModelConfigVersionWatcher.VERSION_KEY)).thenReturn("5", "6");
        doThrow(new RuntimeException("listener boom"))
                .when(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));

        watcher.pollVersion();
        assertDoesNotThrow(watcher::pollVersion);
    }

    /** 没有 Redis 时退化为原有单机行为：不轮询、不递增、不发事件 */
    @Test
    void withoutRedisDoesNothing() {
        ModelConfigVersionWatcher bare = new ModelConfigVersionWatcher();
        bare.eventPublisher = eventPublisher;

        assertDoesNotThrow(bare::pollVersion);
        assertDoesNotThrow(bare::bumpVersion);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void parseVersionToleratesMissingAndMalformedValues() {
        assertEquals(0L, ModelConfigVersionWatcher.parseVersion(null));
        assertEquals(0L, ModelConfigVersionWatcher.parseVersion("  "));
        assertEquals(0L, ModelConfigVersionWatcher.parseVersion("not-a-number"));
        assertEquals(42L, ModelConfigVersionWatcher.parseVersion(" 42 "));
    }
}