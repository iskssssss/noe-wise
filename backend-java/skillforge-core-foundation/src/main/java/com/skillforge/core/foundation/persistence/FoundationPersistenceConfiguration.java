package com.skillforge.core.foundation.persistence;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 基础身份、机构和审计的持久化接口专属包，不扫描领域服务接口。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = ServicePrincipalMapper.class)
public class FoundationPersistenceConfiguration { }
