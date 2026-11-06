package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.ChatShareBean;
import com.aiproject.ai.service.mapper.ChatShareBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

class ChatShareHolderTest {

    private ChatShareHolder holder;

    @BeforeEach
    void setUp() {
        holder = new ChatShareHolder();
        holder.mapper = mock(ChatShareBeanMapper.class);
    }

    @Test
    void insertFailureDoesNotReturnShareLink() {
        when(holder.mapper.install(any(ChatShareBean.class))).thenReturn(0);

        Result<String> result = holder.create("u1", "张三", share("hello"));

        assertNull(result.getDatas(), "写入未生效时不能返回可分享的 token");
        assertTrue(result.getResp_msg().contains("失败"));
    }

    @Test
    void insertExceptionDoesNotReturnShareLink() {
        when(holder.mapper.install(any(ChatShareBean.class)))
                .thenThrow(new RuntimeException("db down"));

        Result<String> result = holder.create("u1", "张三", share("hello"));

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("失败"));
    }

    @Test
    void successfulInsertReturnsTokenAndOwner() {
        when(holder.mapper.install(any(ChatShareBean.class))).thenAnswer(invocation -> {
            ChatShareBean row = invocation.getArgument(0);
            assertEquals("u1", row.getUserId());
            assertEquals("张三", row.getAuthorName());
            assertEquals("hello", row.getPayload());
            assertNotNull(row.getShareToken());
            assertEquals(32, row.getShareToken().length(), "token 应为去掉连字符的 UUID");
            return 1;
        });

        Result<String> result = holder.create("u1", "张三", share("hello"));

        assertEquals(0, result.getResp_code());
        assertNotNull(result.getDatas());
        assertEquals(32, result.getDatas().length());
    }

    @Test
    void blankPayloadIsRejectedBeforeWrite() {
        ChatShareBean data = share("   ");

        Result<String> result = holder.create("u1", "张三", data);

        assertNull(result.getDatas());
        verify(holder.mapper, never()).install(any(ChatShareBean.class));
    }

    @Test
    void oversizedPayloadIsRejectedBeforeWrite() {
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < 512 * 1024 + 1; i++) {
            payload.append('x');
        }

        Result<String> result = holder.create("u1", "张三", share(payload.toString()));

        assertNull(result.getDatas());
        verify(holder.mapper, never()).install(any(ChatShareBean.class));
    }

    @Test
    void getByToken_shouldStripOwnerIdentifiersFromPublicResponse() {
        ChatShareBean row = share("hello");
        row.setShareToken("token-1");
        row.setUserId("u1");
        row.setLastuser("u1");
        row.setAuthorName("张三");
        when(holder.mapper.select(any(), any(ChatShareBean.class)))
                .thenReturn(Collections.singletonList(row));

        Result<ChatShareBean> result = holder.getByToken("token-1");

        assertNotNull(result.getDatas());
        assertNull(result.getDatas().getUserId(), "作者 uid 不应出现在免登录响应中");
        assertNull(result.getDatas().getLastuser(), "继承列里的作者 uid 也要抹掉");
        assertEquals("张三", result.getDatas().getAuthorName());
        assertEquals("一次对话", result.getDatas().getTitle());
        assertEquals("hello", result.getDatas().getPayload());
    }

    private ChatShareBean share(String payload) {
        ChatShareBean data = new ChatShareBean();
        data.setTitle("一次对话");
        data.setPayload(payload);
        return data;
    }
}
