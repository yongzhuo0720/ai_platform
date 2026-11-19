package com.aiproject.ai.service.gateway;

import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * doProxy 异常分支审计状态码判定（从 397 行方法提取的纯逻辑）。
 *
 * 这条判定的正确性直接关系到「看板上的上游可用性统计准不准」——
 * 流中途断掉时若误记成 502，会让健康的上游被算成故障。
 */
class AiGatewayAuditStatusTest {

    @Test
    void timeout_takesPriorityOverUpstreamStatus() {
        // 读超时优先记 504，即使之前收到过 200 响应头
        assertEquals(HttpServletResponse.SC_GATEWAY_TIMEOUT,
                AiGatewayService.resolveAuditStatus(true, 200));
    }

    @Test
    void receivedUpstreamStatus_isRecordedAsIs() {
        // 200 之后读失败：记 200，不能记 502（否则看板把上游算成不可用）
        assertEquals(200, AiGatewayService.resolveAuditStatus(false, 200));
        // 上游真实返回的错误码同样原样记录
        assertEquals(429, AiGatewayService.resolveAuditStatus(false, 429));
        assertEquals(500, AiGatewayService.resolveAuditStatus(false, 500));
    }

    @Test
    void noUpstreamResponse_meansBadGateway() {
        assertEquals(HttpServletResponse.SC_BAD_GATEWAY,
                AiGatewayService.resolveAuditStatus(false, null));
    }
}
