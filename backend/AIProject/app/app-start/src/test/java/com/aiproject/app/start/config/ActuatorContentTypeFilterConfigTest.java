package com.aiproject.app.start.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * actuator 请求缺失 Content-Type 时的兜底。
 *
 * <p>HNSP 的 MultiRequestBodyArgumentResolver 对 Content-Type 直接解引用，
 * 无 Content-Type 的 GET 会在解析参数时 NPE，健康检查因此永远不可用。
 * 这里验两个边界：actuator 路径补默认值，已有 Content-Type 保持原样。</p>
 */
class ActuatorContentTypeFilterConfigTest {

    @Test
    void registrationOnlyCoversActuatorAndRunsFirst() {
        FilterRegistrationBean<OncePerRequestFilter> registration =
                new ActuatorContentTypeFilterConfig().actuatorContentTypeFilter();

        assertEquals("/actuator/*", registration.getUrlPatterns().iterator().next());
        assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
    }

    @Test
    void missingContentTypeBecomesJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        assertNull(request.getContentType());
        AtomicReference<String> seen = new AtomicReference<>();
        OncePerRequestFilter filter = new ActuatorContentTypeFilterConfig()
                .actuatorContentTypeFilter().getFilter();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(req.getContentType()));

        assertEquals("application/json", seen.get());
    }

    @Test
    void existingContentTypeIsPreserved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/health");
        request.setContentType("application/x-www-form-urlencoded");
        AtomicReference<String> seen = new AtomicReference<>();
        OncePerRequestFilter filter = new ActuatorContentTypeFilterConfig()
                .actuatorContentTypeFilter().getFilter();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(req.getContentType()));

        assertEquals("application/x-www-form-urlencoded", seen.get());
    }
}