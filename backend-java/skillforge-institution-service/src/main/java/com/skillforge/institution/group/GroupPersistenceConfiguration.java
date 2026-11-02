package com.skillforge.institution.group;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 仅登记群领域标注Mapper，避免扩大宿主包扫描。 */
@Configuration(proxyBeanMethods=false)
@MapperScan(basePackageClasses=GroupBindingMapper.class,annotationClass=org.apache.ibatis.annotations.Mapper.class)
public class GroupPersistenceConfiguration { }
