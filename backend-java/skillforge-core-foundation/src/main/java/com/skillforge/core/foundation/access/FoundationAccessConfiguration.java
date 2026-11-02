package com.skillforge.core.foundation.access;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;

/** 平台与机构宿主共同装配 access 的 XML Mapper。 */
@Configuration
@ComponentScan(basePackageClasses = FoundationAccess.class)
@MapperScan(basePackageClasses = FoundationAccessMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class FoundationAccessConfiguration { }
