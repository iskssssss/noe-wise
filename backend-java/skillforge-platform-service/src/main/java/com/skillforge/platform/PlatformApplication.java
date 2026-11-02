package com.skillforge.platform;

import com.skillforge.core.shared.config.SkillForgeProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 灵知 平台运营后台服务启动入口。
 *
 * <p>启动包只承载接口层（平台运营控制器、Web 配置、安全配置），
 * 核心业务逻辑位于 {@code com.skillforge.core} 包。
 */
@SpringBootApplication(scanBasePackages = "com.skillforge.platform")
@EnableScheduling
@EnableConfigurationProperties(SkillForgeProperties.class)
@MapperScans({
        @MapperScan({"com.skillforge.core.agent.mapper", "com.skillforge.core.workflow.mapper"}),
        @MapperScan(basePackageClasses = {com.skillforge.platform.billing.BillingScanMapper.class,
                com.skillforge.platform.controller.HealthMapper.class},
                annotationClass = org.apache.ibatis.annotations.Mapper.class)
})
public class PlatformApplication {

    /**
     * 启动平台运营后台服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
