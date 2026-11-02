package com.skillforge.core.model;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 两个宿主共享模型配置能力，平台服务采用显式装配。 */
@Configuration
@MapperScan(basePackageClasses = PlatformModelMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
@Import({PlatformModelCatalogService.class, TimePricingRules.class, com.skillforge.core.model.invocation.ModelCallRecords.class,
        com.skillforge.core.model.media.ModelMediaConfiguration.class})
public class PlatformModelsConfiguration { }
