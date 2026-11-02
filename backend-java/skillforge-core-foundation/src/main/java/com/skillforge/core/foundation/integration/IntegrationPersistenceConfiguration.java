package com.skillforge.core.foundation.integration;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 两个宿主均扫描本治理包，真实装配 XML Mapper。 */
@Configuration
@MapperScan(basePackageClasses = InstitutionIntegrationMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class IntegrationPersistenceConfiguration {}
