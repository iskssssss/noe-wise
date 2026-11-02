package com.skillforge.core.foundation.runtimeconfig;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackageClasses=RunSettingsMapper.class,annotationClass=org.apache.ibatis.annotations.Mapper.class)
public class RunSettingsConfiguration { }
