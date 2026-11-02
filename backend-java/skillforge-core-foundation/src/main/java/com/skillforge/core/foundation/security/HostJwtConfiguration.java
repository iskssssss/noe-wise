package com.skillforge.core.foundation.security;

import cn.dev33.satoken.stp.StpLogic;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.security.ScopedJwtLogic;
import com.skillforge.common.time.BusinessTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token JWT 模式配置类。
 *
 * <p>通过注册 {@link ScopedJwtLogic} 替换默认的 StpLogic，使 {@code StpUtil}
 * 生成的令牌为 JWT，并支持在令牌中携带 {@code tenant_id}、{@code role} 等扩展字段。
 */
@Configuration
public class HostJwtConfiguration {

    /**
     * 注册 JWT 模式的 StpLogic Bean。
     *
     * @return 基于 JWT 的简单模式 StpLogic 实例
     */
    @Bean
    public StpLogic stpLogic(SkillForgeProperties properties) {
        return new ScopedJwtLogic(properties.getJwtIssuer(), properties.getJwtAudience(), BusinessTime.clock(),
                properties.getManagementJwtIssuer(), properties.getManagementJwtAudience(), properties.getManagementJwtSecretKey());
    }
}
