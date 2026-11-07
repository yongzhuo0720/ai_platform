package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.ChatConversationBean;
import com.aiproject.ai.service.mapper.ChatConversationBeanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatConversationHolderTest {
    private ChatConversationHolder holder;

    @BeforeEach
    void setUp() {
        holder = new ChatConversationHolder();
        holder.mapper = mock(ChatConversationBeanMapper.class);
    }

    @Test
    void staleSnapshotDoesNotOverwriteNewerConversation() {
        ChatConversationBean current = conversation(200L, "new answer");
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(Collections.singletonList(current));

        assertFalse(holder.save("u1", conversation(100L, "old answer")).getDatas());
        assertEquals("new answer", current.getPayload());
        verify(holder.mapper, never()).update(any(), any(ChatConversationBean.class));
    }

    @Test
    void updateUsesPreviousVersionAndAuthenticatedOwnerAsConditions() {
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(Collections.singletonList(conversation(100L, "old")));
        when(holder.mapper.update(any(), any(ChatConversationBean.class))).thenAnswer(invocation -> {
            ChatConversationBean condition = invocation.getArgument(1);
            assertEquals("u1", condition.getUserId());
            assertEquals("conversation-1", condition.getConvId());
            assertEquals(Long.valueOf(100), condition.getClientUpdatedAt());
            return 1;
        });

        assertTrue(holder.save("u1", conversation(200L, "new")).getDatas());
    }

    @Test
    void failedConditionalUpdateDoesNotClaimSuccess() {
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(Collections.singletonList(conversation(100L, "old")));
        when(holder.mapper.update(any(), any(ChatConversationBean.class))).thenReturn(0);

        assertFalse(holder.save("u1", conversation(200L, "new")).getDatas());
    }

    /** 插入失败且确实不存在可更新的行时，仍应如实返回失败 */
    @Test
    void failedInsertDoesNotClaimSuccess() {
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(Collections.emptyList());
        when(holder.mapper.install(any(ChatConversationBean.class))).thenReturn(0);

        assertFalse(holder.save("u1", conversation(100L, "answer")).getDatas());
        verify(holder.mapper, never()).update(any(), any(ChatConversationBean.class));
    }

    /**
     * 并发首次保存：唯一索引让后到的插入失败，此时必须重新读取赢家行，
     * 按它的版本号做条件更新，而不是把「会话已存在」当成保存失败。
     */
    @Test
    void concurrentInsertConflictFallsBackToConditionalUpdate() {
        ChatConversationBean winner = conversation(100L, "winner");
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(Collections.<ChatConversationBean>emptyList(),
                        Collections.singletonList(winner));
        when(holder.mapper.install(any(ChatConversationBean.class)))
                .thenThrow(new RuntimeException("duplicate key value violates unique constraint"));
        when(holder.mapper.update(any(), any(ChatConversationBean.class))).thenAnswer(invocation -> {
            ChatConversationBean condition = invocation.getArgument(1);
            assertEquals("u1", condition.getUserId());
            assertEquals("conversation-1", condition.getConvId());
            assertEquals(Long.valueOf(100), condition.getClientUpdatedAt(),
                    "重试更新必须以赢家写入的版本号为条件");
            return 1;
        });

        assertTrue(holder.save("u1", conversation(200L, "newer")).getDatas());
        assertEquals("newer", winner.getPayload());
    }

    /** 冲突后重试同样要服从乐观锁：比云端旧的快照不能覆盖赢家 */
    @Test
    void concurrentInsertConflictDoesNotOverwriteNewerWinner() {
        ChatConversationBean winner = conversation(300L, "winner");
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(Collections.<ChatConversationBean>emptyList(),
                        Collections.singletonList(winner));
        when(holder.mapper.install(any(ChatConversationBean.class)))
                .thenThrow(new RuntimeException("duplicate key"));

        assertFalse(holder.save("u1", conversation(100L, "older")).getDatas());
        assertEquals("winner", winner.getPayload());
        verify(holder.mapper, never()).update(any(), any(ChatConversationBean.class));
    }

    @Test
    void snapshotWithoutVersionIsRejected() {
        assertFalse(holder.save("u1", conversation(null, "answer")).getDatas());
        verify(holder.mapper, never()).select(isNull(), any(ChatConversationBean.class));
    }

    @Test
    void unauthenticatedSaveDoesNotWriteData() {
        assertFalse(holder.save(" ", conversation(100L, "answer")).getDatas());
        verify(holder.mapper, never()).install(any(ChatConversationBean.class));
    }

    /** 列表只给元数据：正文可能接近 512KB，不能每次登录全量下发 */
    @Test
    void listReturnsMetadataWithoutPayload() {
        ChatConversationBean row = conversation(100L, "large payload");
        row.setTitle("季度总结");
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(new ArrayList<ChatConversationBean>(Collections.singletonList(row)));

        List<ChatConversationBean> rows = holder.list("u1").getDatas();

        assertEquals(1, rows.size());
        assertNull(rows.get(0).getPayload(), "列表响应不应携带会话正文");
        assertEquals("季度总结", rows.get(0).getTitle());
        assertEquals(Long.valueOf(100), rows.get(0).getClientUpdatedAt());
    }

    /** 按需正文只返回本人的行；空值、重复值与别人的 ID 都被跳过 */
    @Test
    void detailOnlyReturnsOwnedRowsAndDeduplicatesIds() {
        ChatConversationBean owned = conversation(100L, "body");
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(Collections.<ChatConversationBean>emptyList(),
                        Collections.singletonList(owned));

        List<ChatConversationBean> rows = holder.detail(
                "u1", Arrays.asList("other-user-conv", " ", "conversation-1", "conversation-1"))
                .getDatas();

        assertEquals(1, rows.size());
        assertEquals("body", rows.get(0).getPayload());
        ArgumentCaptor<ChatConversationBean> captor =
                ArgumentCaptor.forClass(ChatConversationBean.class);
        verify(holder.mapper, times(2)).select(isNull(), captor.capture());
        assertEquals("u1", captor.getAllValues().get(0).getUserId());
        assertEquals("other-user-conv", captor.getAllValues().get(0).getConvId());
        assertEquals("u1", captor.getAllValues().get(1).getUserId());
        assertEquals("conversation-1", captor.getAllValues().get(1).getConvId());
    }

    @Test
    void detailRequiresLoginAndCapsBatchSize() {
        assertNull(holder.detail(" ", Collections.singletonList("c1")).getDatas());
        verify(holder.mapper, never()).select(any(), any(ChatConversationBean.class));

        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 51; i++) {
            ids.add("conv-" + i);
        }
        when(holder.mapper.select(isNull(), any(ChatConversationBean.class)))
                .thenReturn(Collections.<ChatConversationBean>emptyList());
        assertTrue(holder.detail("u1", ids).getDatas().isEmpty());
        verify(holder.mapper, times(50)).select(isNull(), any(ChatConversationBean.class));
    }

    private ChatConversationBean conversation(Long updatedAt, String payload) {
        ChatConversationBean conversation = new ChatConversationBean();
        conversation.setConvId("conversation-1");
        conversation.setClientUpdatedAt(updatedAt);
        conversation.setPayload(payload);
        return conversation;
    }
}
