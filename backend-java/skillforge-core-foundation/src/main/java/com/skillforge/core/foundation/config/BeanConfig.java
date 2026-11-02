package com.skillforge.core.foundation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 通用 Bean 配置类。
 *
 * <p>集中注册项目所需的通用组件，避免散落在各个业务类中重复创建。
 */
@Configuration
public class BeanConfig {

    /**
     * 密码编码器 Bean。
     *
     * <p>使用 BCrypt 算法对租户成员密码进行哈希与校验。
     *
     * @return BCrypt 密码编码器实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
