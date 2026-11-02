package com.skillforge.platform.config;

import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.settings.OperationalSettingsMapper;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsRegistry;
import com.skillforge.core.foundation.settings.OperationalSettingsContributor;
import java.util.List;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 平台运营配置的显式装配边界。 */
@Configuration
@MapperScan(basePackageClasses = OperationalSettingsMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class SystemSettingsConfiguration {

    @Bean
    public OperationalSettingsRegistry operationalSettingsRegistry(List<OperationalSettingsContributor> contributors) {
        return new OperationalSettingsRegistry(contributors);
    }

    @Bean
    public OperationalSettingsService operationalSettingsService(
            OperationalSettingsMapper mapper, CryptoService cryptoService, OperationalSettingsRegistry registry) {
        return new OperationalSettingsService(mapper, cryptoService, registry);
    }
}
