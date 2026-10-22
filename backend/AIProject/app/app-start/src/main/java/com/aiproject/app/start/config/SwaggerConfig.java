package com.aiproject.app.start.config;

import com.heneng.hnsp.common.swagger.BaseSwaggerConfig;
import com.heneng.hnsp.common.swagger.SwaggerProperties;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

/**
 * Swagger配置
 *
 * @author wq
 * @since 2017-05-16
 */
@EnableSwagger2
@Configuration
public class SwaggerConfig extends BaseSwaggerConfig {

    @Override
    public SwaggerProperties swaggerProperties() {
        return SwaggerProperties.builder()
                .enable(true)
                .apiBasePackage("com")
                .title("ai系统")
                .description("ai 接口文档")
                .contactName("xxxxxx")
                .version("1.0")
                .enableSecurity(true)
                .build();
    }

}
