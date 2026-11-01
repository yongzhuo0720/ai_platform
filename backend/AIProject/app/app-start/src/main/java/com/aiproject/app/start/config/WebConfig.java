package com.aiproject.app.start.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * web静态资源配置
 */
@EnableWebMvc
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 受保护的路径前缀：框架登录/账号接口 + 项目自身的业务与管理接口。 */
    private static final String[] PROTECTED_PATTERNS = {
            "/uaccess/**",
            "/user/**",
            "/userAccount/**",
            "/Role/**",
            "/menuview/**",
            "/controllerurl/**",
            "/userControllerlog/**",
            "/myuser/**",
            "/myRole/**",
            "/api/**"
    };

    @Autowired
    private LoginAuthInterceptor loginAuthInterceptor;

    @Autowired
    private ApiTokenAuthInterceptor apiTokenAuthInterceptor;

    //   // @Bean
//    public HttpMessageConverter<String> responseBodyConverter() {
//        // 解决中文乱码问题
//        return new StringHttpMessageConverter(Charset.forName("UTF-8"));
//    }
//
//    @Override
//    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
//        converters.add(responseBodyConverter());
//    }
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 解决静态资源无法访问
        registry.addResourceHandler("/doc.html").addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**").addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    /**
     * Token参数解析
     *
     * @param argumentResolvers 解析类
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        // 添加MultiRequestBody参数解析器
        //argumentResolvers.add(new MultiRequestBodyArgumentResolver());
        //注入用户信息
        //argumentResolvers.add(new TokenArgumentResolver());
        // MethodArgumentResolver.registry(new TokenArgumentResolver());
        //注入应用信息
        // argumentResolvers.add(new AppidArgumentResolver());
    }

    /**
     * 添加拦截器
     *
     * @param registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 框架自带的两个拦截器（UserPermissionInterceptor / HnspHandlerInterceptorAdapter）
        // 在这里一直是注释掉的，而框架的 @LoginUserId 解析器未登录时只会返回 null、不会拒绝请求，
        // 等于全站没有登录门禁。改由项目自己的 LoginAuthInterceptor 默认拒绝。
        // 免登录的具体路径由 LoginAuthInterceptor 内部按精确路径放行，
        // 不用 excludePathPatterns 重复列一遍，避免两处清单将来对不上。
        registry.addInterceptor(loginAuthInterceptor)
                .addPathPatterns(Arrays.asList(PROTECTED_PATTERNS));

        // OpenAI 兼容端点走的是平台 API 密钥，不是浏览器登录态，
        // 所以不能挂进上面那套「认 Sa-Token」的门禁；/v1/** 也不在受保护前缀里。
        // 单独注册一个只做 Bearer 校验的拦截器，缺密钥一律 401。
        registry.addInterceptor(apiTokenAuthInterceptor)
                .addPathPatterns("/v1/**");
    }

}
