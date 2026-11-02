package com.skillforge.core.billing.persistence;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 账单与调用账本的持久化接口专属包。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = ModelBillingMapper.class)
public class BillingPersistenceConfiguration { }
