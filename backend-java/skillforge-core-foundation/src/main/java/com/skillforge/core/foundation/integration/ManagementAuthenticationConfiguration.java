package com.skillforge.core.foundation.integration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 两个宿主复用同一机器认证协议，随后领域服务校验当前动作与明确目标。 */
@Configuration
public class ManagementAuthenticationConfiguration implements WebMvcConfigurer {
    private final PlatformManagementService management;
    public ManagementAuthenticationConfiguration(PlatformManagementService management) { this.management = management; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ManagementAuthenticationInterceptor(management))
                .addPathPatterns("/api/v1/**").order(-30);
    }
}
