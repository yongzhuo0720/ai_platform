package com.aiproject.app.start.config;

import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局异常处理的脱敏回归测试。
 *
 * <p>核心断言：业务抛出的 IllegalArgumentException / IllegalStateException 消息
 * 可能拼了上游响应片段或内部配置，绝不能出现在返回给前端的 resp_msg 里；
 * 框架标准参数异常的消息由 Spring 生成，可以保留以帮助调用方排错。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void illegalStateWithUpstreamSnippet_isNotEchoed() {
        // 模拟 EmbeddingClient 那种把上游响应片段拼进消息的情况
        String secret = "SECRET_UPSTREAM_SNIPPET_9f3a";
        Result<Void> result = handler.handleBadRequest(
                new IllegalStateException("向量化接口返回 HTTP 500：" + secret), request);

        assertNotNull(result.getResp_msg());
        assertFalse(result.getResp_msg().contains(secret),
                "业务异常消息不得回显到前端，避免泄露上游片段");
        assertTrue(result.getResp_msg().contains("请求参数有误"));
    }

    @Test
    void illegalArgumentWithInternalConfig_isNotEchoed() {
        String secret = "sso.token-encrypt-key 当前为 7 字节 SECRET";
        Result<Void> result = handler.handleBadRequest(new IllegalArgumentException(secret), request);

        assertFalse(result.getResp_msg().contains(secret),
                "内部配置状态不得回显");
    }

    @Test
    void frameworkParameterException_keepsReadableMessage() {
        // 框架标准异常消息安全，保留可读提示帮助调用方
        MissingServletRequestParameterException e =
                new MissingServletRequestParameterException("datasetKey", "String");
        Result<Void> result = handler.handleBadRequest(e, request);

        assertTrue(result.getResp_msg().contains("datasetKey"),
                "框架参数异常应保留参数名，方便调用方修正");
    }

    @Test
    void nullMessage_doesNotThrow() {
        assertDoesNotThrow(() -> handler.handleBadRequest(new IllegalStateException(), request));
    }
}
