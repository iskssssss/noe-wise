package com.skillforge.core.foundation.institution;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** 两个宿主显式组合机构关系领域及其持久化入口。 */
@Configuration(proxyBeanMethods=false)
@ComponentScan(basePackageClasses=InstitutionRelationService.class)
@MapperScan(basePackageClasses=InstitutionRelationMapper.class,annotationClass=Mapper.class)
public class InstitutionRelationConfiguration { }
