package com.skillforge.core.foundation.settings;

import com.skillforge.core.foundation.application.CryptoService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 扫描 core 包的宿主用于装配运营配置 Mapper。 */
@Configuration
@MapperScan(basePackageClasses = OperationalSettingsMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class OperationalSettingsConfiguration {

    @Bean
    public OperationalSettingsRegistry operationalSettingsRegistry(java.util.List<OperationalSettingsContributor> contributors) {
        return new OperationalSettingsRegistry(contributors);
    }

    @Bean
    public OperationalSettingsService operationalSettingsService(
            OperationalSettingsMapper mapper, CryptoService cryptoService, OperationalSettingsRegistry registry) {
        return new OperationalSettingsService(mapper, cryptoService, registry);
    }
}
