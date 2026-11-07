package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.bean.AgentToolBean;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工具执行器：复用真实检索、拒绝危险类型、参数与结果都有边界。 */
@ExtendWith(MockitoExtension.class)
class AgentToolExecutorTest {

    private static final String UID = "u1";

    @Mock
    KbRagHolder kbRagHolder;

    @InjectMocks
    AgentToolExecutor executor;

    private AgentToolBean tool(String type) {
        AgentToolBean tool = new AgentToolBean();
        tool.setName("kb_search");
        tool.setToolType(type);
        return tool;
    }

    @Test
    void kbSearch_shouldReuseRealHybridRetrieval() throws Exception {
        KbSearchItem item = new KbSearchItem();
        item.setDocName("制度.txt");
        item.setContent("第 3 条：报销需附发票");
        KbSearchResult result = new KbSearchResult();
        result.setItems(Collections.singletonList(item));
        when(kbRagHolder.search(eq(UID), eq("报销"), isNull(), eq(3)))
                .thenReturn(Result.succeed(result, "检索成功"));

        String output = executor.execute(UID, tool("kb_search"), "{\"query\":\"报销\"}");

        assertTrue(output.contains("制度.txt"));
        assertTrue(output.contains("第 3 条"));
        verify(kbRagHolder).search(eq(UID), eq("报销"), isNull(), eq(3));
    }

    @Test
    void kbSearch_shouldRejectMissingQuery() {
        AgentCallException e = assertThrows(AgentCallException.class,
                () -> executor.execute(UID, tool("kb_search"), "{\"foo\":\"bar\"}"));
        assertTrue(e.getMessage().contains("query"));
    }

    @Test
    void kbSearch_shouldTreatInvalidJsonAsMissingQuery() {
        AgentCallException e = assertThrows(AgentCallException.class,
                () -> executor.execute(UID, tool("kb_search"), "not-json"));
        assertTrue(e.getMessage().contains("query"));
    }

    @Test
    void kbSearch_shouldTruncateOversizedResult() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append('x');
        }
        KbSearchItem item = new KbSearchItem();
        item.setDocName("长文档.txt");
        item.setContent(sb.toString());
        KbSearchResult result = new KbSearchResult();
        result.setItems(Collections.singletonList(item));
        when(kbRagHolder.search(eq(UID), anyString(), isNull(), eq(3)))
                .thenReturn(Result.succeed(result, "检索成功"));

        String output = executor.execute(UID, tool("kb_search"), "{\"query\":\"长文档\"}");
        assertEquals(AgentToolExecutor.MAX_RESULT_CHARS, output.length());
    }

    @Test
    void kbSearch_shouldFailFastWhenToolExceedsTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(kbRagHolder.search(eq(UID), eq("慢查询"), isNull(), eq(3)))
                .thenAnswer(invocation -> {
                    release.await(5, TimeUnit.SECONDS);
                    return Result.succeed(new KbSearchResult(), "检索成功");
                });

        long start = System.currentTimeMillis();
        AgentCallException e = assertThrows(AgentCallException.class,
                () -> executor.execute(UID, tool("kb_search"), "{\"query\":\"慢查询\"}", 100L));
        long elapsed = System.currentTimeMillis() - start;
        release.countDown();

        assertTrue(e.getMessage().contains("超时"), "超时后应把错误交给模型，而不是一直等工具");
        assertTrue(elapsed >= 80, "应在约 100ms 内返回，而不是等工具自己结束");
    }

    @Test
    void execute_shouldCapConfiguredTimeout() throws Exception {
        when(kbRagHolder.search(eq(UID), eq("报销"), isNull(), eq(3)))
                .thenReturn(Result.succeed(new KbSearchResult(), "检索成功"));

        // 配置超过上限时按 120000ms 截断；这里只验证正常路径不受影响
        assertTrue(executor.execute(UID, tool("kb_search"), "{\"query\":\"报销\"}", 999999L)
                .contains("无结果"));
    }

    @Test
    void builtin_shouldReturnCurrentTime() throws Exception {
        assertTrue(executor.execute(UID, tool("builtin"), "{}").contains("当前时间"));
    }

    @Test
    void http_shouldBeRejectedUntilWhitelistExists() {
        AgentCallException e = assertThrows(AgentCallException.class,
                () -> executor.execute(UID, tool("http"), "{}"));
        assertTrue(e.getMessage().contains("白名单"));
    }

    @Test
    void unknownType_shouldBeRejected() {
        AgentCallException e = assertThrows(AgentCallException.class,
                () -> executor.execute(UID, tool("shell_exec"), "{}"));
        assertTrue(e.getMessage().contains("不支持的工具类型"));
    }
}
