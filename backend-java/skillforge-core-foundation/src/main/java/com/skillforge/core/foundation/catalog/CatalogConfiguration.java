package com.skillforge.core.foundation.catalog;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** 双宿主显式导入的 catalog 装配；包扫描仅覆盖本域组件和 Mapper。 */
@Configuration(proxyBeanMethods=false)
@ComponentScan(basePackageClasses=CatalogScope.class)
@MapperScan(basePackageClasses=CatalogScope.class,annotationClass=Mapper.class)
public class CatalogConfiguration { }
