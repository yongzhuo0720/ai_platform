package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关只应放行前端真正使用的 Dify 接口。
 * 这里的正例覆盖 chat / knowledgeChat / file 三条链路在代码中出现的全部调用，
 * 反例锁住「路径穿越、编码绕过、未登记接口」三类风险。
 */
class DifyEndpointPolicyTest {

    private static final String CHAT_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    @Test
    void chatEndpointsUsedByFrontendAreAllowed() {
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "POST", "chat-messages"));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "POST", "chat-messages/" + CHAT_ID + "/stop"));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "GET", "conversations"));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "GET", "messages"));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "POST", "conversations/" + CHAT_ID));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "DELETE", "conversations/" + CHAT_ID));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "GET", "messages/" + CHAT_ID + "/feedbacks"));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "POST", "messages/" + CHAT_ID + "/feedbacks"));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "GET", "messages/" + CHAT_ID + "/suggested"));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "GET", "parameters"));
        assertTrue(DifyEndpointPolicy.isAllowed("chat", "POST", "files/upload"));
        assertTrue(DifyEndpointPolicy.isAllowed("knowledgeChat", "POST", "chat-messages"));
        assertTrue(DifyEndpointPolicy.isAllowed("knowledgeChat", "GET", "conversations"));
    }

    @Test
    void fileEndpointsUsedByFrontendAreAllowed() {
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET", "datasets"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "POST", "datasets"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET", "datasets/" + CHAT_ID));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "PATCH", "datasets/" + CHAT_ID));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "DELETE", "datasets/" + CHAT_ID));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "POST", "datasets/" + CHAT_ID + "/retrieve"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET",
                "workspaces/current/models/model-types/text-embedding"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET",
                "datasets/" + CHAT_ID + "/documents"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET",
                "datasets/" + CHAT_ID + "/documents/doc-1"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "DELETE",
                "datasets/" + CHAT_ID + "/documents/doc-1"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "POST",
                "datasets/" + CHAT_ID + "/document/create-by-text"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "POST",
                "datasets/" + CHAT_ID + "/document/create-by-file"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "POST",
                "datasets/" + CHAT_ID + "/documents/doc-1/update-by-text"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "POST",
                "datasets/" + CHAT_ID + "/documents/doc-1/update-by-file"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET",
                "datasets/" + CHAT_ID + "/documents/doc-1/indexing-status"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "PATCH",
                "datasets/" + CHAT_ID + "/documents/status/enable"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET",
                "datasets/" + CHAT_ID + "/documents/doc-1/download"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET",
                "datasets/" + CHAT_ID + "/documents/doc-1/segments"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "POST",
                "datasets/" + CHAT_ID + "/documents/doc-1/segments"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "POST",
                "datasets/" + CHAT_ID + "/documents/doc-1/segments/seg-1"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "DELETE",
                "datasets/" + CHAT_ID + "/documents/doc-1/segments/seg-1"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "GET",
                "datasets/" + CHAT_ID + "/documents/doc-1/segments/seg-1/child_chunks"));
        assertTrue(DifyEndpointPolicy.isAllowed("file", "PATCH",
                "datasets/" + CHAT_ID + "/documents/doc-1/segments/seg-1/child_chunks/child-1"));
    }

    @Test
    void pathTraversalAndEncodedTricksAreRejected() {
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "../parameters"));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "parameters/.."));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "parameters%2f..%2fadmin"));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "parameters\\..\\admin"));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "parameters//admin"));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "parameters?x=1"));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "parameters#fragment"));
    }

    @Test
    void unregisteredOrWrongMethodEndpointsAreRejected() {
        // 工作流、模型供应商等能力本平台并不通过网关开放
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "POST", "workflows/run"));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "info"));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "GET", "meta"));
        assertFalse(DifyEndpointPolicy.isAllowed("chat", "DELETE", "chat-messages"));
        assertFalse(DifyEndpointPolicy.isAllowed("file", "POST", "datasets/" + CHAT_ID + "/documents"));
        assertFalse(DifyEndpointPolicy.isAllowed("file", "PUT", "datasets/" + CHAT_ID));
        assertFalse(DifyEndpointPolicy.isAllowed("unknown", "GET", "datasets"));
        assertFalse(DifyEndpointPolicy.isAllowed(null, "GET", "datasets"));
        assertFalse(DifyEndpointPolicy.isAllowed("file", null, "datasets"));
        assertFalse(DifyEndpointPolicy.isAllowed("file", "GET", ""));
    }

    /** 段内只允许安全字符，避免 Tomcat 与上游对路径的二次解读产生分歧 */
    @Test
    void weirdSegmentCharactersAreRejected() {
        assertFalse(DifyEndpointPolicy.isAllowed("file", "GET", "datasets/a b"));
        assertFalse(DifyEndpointPolicy.isAllowed("file", "GET", "datasets/a;b"));
        assertFalse(DifyEndpointPolicy.isAllowed("file", "GET", "datasets/a:b"));
        assertFalse(DifyEndpointPolicy.isAllowed("file", "GET", "datasets/" + repeat('a', 129)));
    }

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
