package com.skillforge.platform.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/** 防止共享根环境中的机构数据源账号覆盖平台专用账号。 */
public final class PlatformDatasourceEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /** 平台专属账号的属性来源名，用于稳定覆盖顺序和配置诊断。 */
    private static final String PROPERTY_SOURCE_NAME = "platformDatasourceCredentials";

    /** 将平台账号映射置于共享环境变量之前，保留显式测试和命令行覆盖。 */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MapPropertySource credentials = new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
                "spring.datasource.username", "${PLATFORM_DATASOURCE_USERNAME:skillforge_platform}",
                "spring.datasource.password", "${PLATFORM_DATASOURCE_PASSWORD}"));
        MutablePropertySources sources = environment.getPropertySources();
        // 延迟解析平台变量，保留随后加入的测试数据源覆盖；缺失密码时不能回退机构账号。
        // 命令行、系统属性和 Spring 测试属性仍保持比 systemEnvironment 更高的优先级。
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addBefore(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, credentials);
        } else {
            sources.addLast(credentials);
        }
    }

    /** 在配置文件载入后登记延迟解析的账号映射。 */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
