package com.aiproject.app.start.config;

import com.heneng.hnsp.common.swagger.BaseSwaggerConfig;
import com.heneng.hnsp.common.swagger.SwaggerProperties;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 是否暴露接口文档，默认关闭。
     *
     * <p>修复前这里硬编码 {@code enable(true)}，任何环境（含生产）都能打开
     * {@code /swagger-ui.html} 浏览全量接口。现在改为读配置，仅在
     * 显式设置 {@code swagger.enable=true} 时开启。</p>
     */
    @Value("${swagger.enable:false}")
    private boolean swaggerEnable;

    @Override
    public SwaggerProperties swaggerProperties() {
        return SwaggerProperties.builder()
                .enable(swaggerEnable)
                .apiBasePackage("com")
                .title("智汇平台 NexusAI 接口文档")
                .description("智汇平台后端 API：平台接口、AI 调用治理网关、知识库与模型配置")
                .contactName("xxxxxx")
                .version("1.0")
                .enableSecurity(true)
                .build();
    }

}
