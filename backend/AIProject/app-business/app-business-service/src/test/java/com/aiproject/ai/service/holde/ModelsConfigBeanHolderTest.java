package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.ModelsConfigBean;
import com.aiproject.ai.service.gateway.ModelConfigChangedEvent;
import com.aiproject.ai.service.mapper.ModelsConfigBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模型配置写操作后广播变更事件的测试。
 *
 * <p>背景：RerankResolver 与 ModelPriceResolver 对 modelsconfigbean 有
 * 5 分钟 TTL 的进程内缓存。后台停用 rerank、换密钥、改单价后，如果缓存不失效，
 * 管理员看到「保存成功」而线上仍在用旧配置，属于难以察觉的一致性问题。
 * Holder 负责在写成功后发布 {@link ModelConfigChangedEvent}。</p>
 */
@ExtendWith(MockitoExtension.class)
class ModelsConfigBeanHolderTest {

    private static final String ADMIN = "admin";

    @Mock
    ModelsConfigBeanMapper<ModelsConfigBean> modelsConfigBeanMapper;

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    ModelsConfigBeanHolder holder;

    private ModelsConfigBean data() {
        ModelsConfigBean bean = new ModelsConfigBean();
        bean.setName("DeepSeek 聊天");
        bean.setProtocol("openai");
        return bean;
    }

    @Test
    void install_shouldPublishEventAfterSuccessfulWrite() {
        when(myUserBaseDbHolder.isAdmin(ADMIN)).thenReturn(true);
        when(modelsConfigBeanMapper.install(any(ModelsConfigBean.class))).thenReturn(1);

        Result<Boolean> result = holder.install(ADMIN, data());

        assertTrue(Boolean.TRUE.equals(result.getDatas()));
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }

    @Test
    void update_shouldPublishEventAfterSuccessfulWrite() {
        when(myUserBaseDbHolder.isAdmin(ADMIN)).thenReturn(true);
        when(modelsConfigBeanMapper.update(any(ModelsConfigBean.class), any(ModelsConfigBean.class)))
                .thenReturn(1);

        Result<Boolean> result = holder.update(ADMIN, data(), new ModelsConfigBean());

        assertTrue(Boolean.TRUE.equals(result.getDatas()));
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }

    @Test
    void delete_shouldPublishEventAfterSuccessfulWrite() {
        when(myUserBaseDbHolder.isAdmin(ADMIN)).thenReturn(true);
        when(modelsConfigBeanMapper.delete(any(ModelsConfigBean.class))).thenReturn(1);

        Result<Boolean> result = holder.delete(ADMIN, data());

        assertTrue(Boolean.TRUE.equals(result.getDatas()));
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }

    /** 非超管的写操作在鉴权处就被拒绝，既不该落库也不该广播 */
    @Test
    void nonAdminWrite_shouldNotPublishNorTouchDb() {
        when(myUserBaseDbHolder.isAdmin("u1")).thenReturn(false);

        Result<Boolean> result = holder.update("u1", data(), new ModelsConfigBean());

        assertFalse(Boolean.TRUE.equals(result.getDatas()));
        verifyNoInteractions(modelsConfigBeanMapper);
        verifyNoInteractions(eventPublisher);
    }

    /** 落库失败时配置并没有变化，广播会让缓存白刷一次 */
    @Test
    void failedWrite_shouldNotPublish() {
        when(myUserBaseDbHolder.isAdmin(ADMIN)).thenReturn(true);
        when(modelsConfigBeanMapper.install(any(ModelsConfigBean.class))).thenReturn(0);

        assertFalse(Boolean.TRUE.equals(holder.install(ADMIN, data()).getDatas()));
        verify(eventPublisher, never()).publishEvent(any(ModelConfigChangedEvent.class));
    }

    /** 广播失败不能把已经成功的写操作报成失败：监听方只是刷新缓存，属于附属动作 */
    @Test
    void publishFailure_shouldNotFailSuccessfulWrite() {
        when(myUserBaseDbHolder.isAdmin(ADMIN)).thenReturn(true);
        when(modelsConfigBeanMapper.install(any(ModelsConfigBean.class))).thenReturn(1);
        doThrow(new RuntimeException("监听器异常"))
                .when(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));

        assertTrue(Boolean.TRUE.equals(holder.install(ADMIN, data()).getDatas()));
        verify(eventPublisher).publishEvent(any(ModelConfigChangedEvent.class));
    }
}
