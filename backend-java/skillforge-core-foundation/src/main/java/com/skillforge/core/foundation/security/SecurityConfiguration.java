package com.skillforge.core.foundation.security;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 将安全域 XML Mapper 注册到宿主正式 MyBatis 数据源。 */
@Configuration
@org.springframework.context.annotation.ComponentScan(basePackageClasses = FoundationSecurityService.class)
@MapperScan(basePackageClasses = SecurityMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class SecurityConfiguration { }
