package com.aiproject.app.start.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 给 /actuator/* 补一个默认 Content-Type，绕开 HNSP 参数解析器的空指针。
 *
 * <p>HNSP 的 MultiRequestBodyArgumentResolver 的 supportsParameter 会接管所有
 * {@code @RequestBody} 参数（包括 Spring Boot actuator 内部的
 * {@code @RequestBody(required = false) Map}），但 resolveArgument 一开始就是
 * {@code request.getContentType().startsWith(...)}。GET /actuator/health 没有请求体、
 * 也没有 Content-Type，于是直接 NullPointerException，健康检查被全局异常处理器
 * 包成 200 + resp_code=-1（2026-11-07 实测）。</p>
 *
 * <p>这里只在 actuator 路径上把缺失的 Content-Type 补成空的 application/json，
 * 让解析器走「没有 body → 返回 null」的分支。范围限定在 /actuator/*，
 * 不改变业务接口对缺失 Content-Type 的既有处理。</p>
 */
@Configuration
public class ActuatorContentTypeFilterConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> actuatorContentTypeFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                if (request.getContentType() == null) {
                    filterChain.doFilter(new ContentTypeDefaultsToJson(request), response);
                    return;
                }
                filterChain.doFilter(request, response);
            }
        });
        registration.addUrlPatterns("/actuator/*");
        registration.setName("actuatorContentTypeFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private static final class ContentTypeDefaultsToJson extends HttpServletRequestWrapper {

        private ContentTypeDefaultsToJson(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getContentType() {
            return "application/json";
        }
    }
}