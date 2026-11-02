package com.skillforge.core.billing.export;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 两个宿主装配同一应用计量导出 XML Mapper，避免仅新增接口而未进入真实运行路径。 */
@Configuration
@MapperScan(basePackageClasses=PlatformUsageMapper.class,annotationClass=org.apache.ibatis.annotations.Mapper.class)
public class PlatformUsagePersistenceConfiguration { }
