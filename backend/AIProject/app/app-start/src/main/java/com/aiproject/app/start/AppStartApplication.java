package com.aiproject.app.start;

import com.heneng.hnsp.common.croe.HnspApplication;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * @author Hnsp快速开发框架
 */
@EnableAspectJAutoProxy(exposeProxy = true)
@org.springframework.scheduling.annotation.EnableScheduling
@Slf4j
@EnableConfigurationProperties
@SpringBootApplication(scanBasePackages={"com.heneng","com.aiproject"})
@MapperScan({"com.heneng.hnsp.business.role.view.dao"})
public class AppStartApplication {

    public static void main(String[] args) {
        HnspApplication.run(AppStartApplication.class, args);
    }

}
