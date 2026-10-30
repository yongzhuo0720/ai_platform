package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGatewayUsageParserTest {

    /** 首 token 延迟只认「带正文的事件」：心跳、role 帧、usage 帧都不能提前计时 */
    @Test
    void detectsFirstAnswerContentForTtft() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        assertFalse(parser.acceptSseLine(": ping"));
        assertFalse(parser.acceptSseLine("event: message"));
        assertFalse(parser.acceptSseLine("data: [DONE]"));
        assertFalse(parser.acceptSseLine(
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"));
        assertFalse(parser.acceptSseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}"));
        assertFalse(parser.acceptSseLine(
                "data: {\"choices\":[],\"usage\":{\"total_tokens\":9}}"));

        assertTrue(parser.acceptSseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}"));
        assertTrue(parser.acceptSseLine(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想\"}}]}"));
    }

    @Test
    void detectsDifyAnswerAsFirstContent() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        assertFalse(parser.acceptSseLine("data: {\"event\":\"message\",\"answer\":\"\"}"));
        assertTrue(parser.acceptSseLine("data: {\"event\":\"message\",\"answer\":\"你好\"}"));
        assertTrue(parser.acceptSseLine("data: {\"event\":\"agent_message\",\"answer\":\"Hi\"}"));
    }

    @Test
    void extractsUsageFromMessageEndEvent() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        parser.acceptSseLine("data: {\"event\":\"message_end\",\"conversation_id\":\"conv-1\","
                + "\"message_id\":\"msg-1\",\"metadata\":{\"model_name\":\"qwen-max\","
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34,\"total_tokens\":46}}}");

        assertEquals(Long.valueOf(12), parser.getPromptTokens());
        assertEquals(Long.valueOf(34), parser.getCompletionTokens());
        assertEquals(Long.valueOf(46), parser.getTotalTokens());
        assertEquals("qwen-max", parser.getModelName());
        assertEquals("conv-1", parser.getConversationId());
        assertEquals("msg-1", parser.getMessageId());
        assertNull(parser.getErrorMessage());
    }

    @Test
    void extractsWorkflowTotalsFromDataNode() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        parser.acceptSseLine("event: workflow_finished");
        parser.acceptSseLine("data: {\"event\":\"workflow_finished\",\"conversation_id\":\"conv-2\","
                + "\"data\":{\"total_tokens\":100,\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":60}}}");
        parser.acceptSseLine("data: [DONE]");

        assertEquals(Long.valueOf(100), parser.getTotalTokens());
        assertEquals(Long.valueOf(40), parser.getPromptTokens());
        assertEquals(Long.valueOf(60), parser.getCompletionTokens());
        assertEquals("conv-2", parser.getConversationId());
        assertNull(parser.getErrorMessage());
    }

    @Test
    void supportsInputOutputTokenAliases() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        parser.acceptJson("{\"usage\":{\"input_tokens\":7,\"output_tokens\":9}}");

        assertEquals(Long.valueOf(7), parser.getPromptTokens());
        assertEquals(Long.valueOf(9), parser.getCompletionTokens());
        assertEquals(Long.valueOf(16), parser.getTotalTokens());
    }

    @Test
    void capturesSseErrorEvent() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        parser.acceptSseLine("data: {\"event\":\"error\",\"message\":\"quota exceeded\"}");

        assertEquals("quota exceeded", parser.getErrorMessage());
    }

    @Test
    void capturesBlockingErrorPayload() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        parser.acceptJson("{\"code\":\"invalid_param\",\"message\":\"bad request\",\"status\":400}");

        assertEquals("bad request (invalid_param)", parser.getErrorMessage());
    }

    @Test
    void capturesAgentFailedResponseEvent() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        parser.acceptSseLine("data: {\"object\":\"response\",\"status\":\"failed\","
                + "\"error\":{\"message\":\"agent offline\"}}");

        assertEquals("agent offline", parser.getErrorMessage());
    }

    @Test
    void extractsAgentSessionAndMessageIds() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        parser.acceptSseLine("data: {\"object\":\"message\",\"id\":\"msg-a\","
                + "\"session_id\":\"session-a\",\"type\":\"message\",\"content\":[]}");

        assertEquals("session-a", parser.getConversationId());
        assertEquals("msg-a", parser.getMessageId());
    }

    @Test
    void ignoresNonDataLinesAndMalformedJson() {
        AiGatewayUsageParser parser = new AiGatewayUsageParser();
        parser.acceptSseLine("event: message");
        parser.acceptSseLine("data: not-json");
        parser.acceptSseLine(null);
        parser.acceptJson("");

        assertNull(parser.getTotalTokens());
        assertNull(parser.getErrorMessage());
    }
}
