package com.aiproject.app.start.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * web静态资源配置
 */
@EnableWebMvc
@Configuration
public class WebConfig implements WebMvcConfigurer {

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
//        registry.addInterceptor(new UserPermissionInterceptor())
//                //拦截所有，包括静态资源
//                .addPathPatterns("/**")
//                //放行
//                .excludePathPatterns("/", "/uaccess", "/login", "/css/**", "/fonts/**", "/images/**", "/js/**");

        // 可添加多个
        //   registry.addInterceptor(new HnspHandlerInterceptorAdapter()).addPathPatterns("/**");
        // 可添加多个
        //  registry.addInterceptor(new HandlerInterceptorAdapter()).addPathPatterns("/**");
    }

}
