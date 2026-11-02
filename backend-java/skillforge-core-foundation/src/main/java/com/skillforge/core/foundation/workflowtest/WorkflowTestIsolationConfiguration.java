package com.skillforge.core.foundation.workflowtest;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.mybatis.spring.annotation.MapperScan;

@Configuration(proxyBeanMethods=false)
@Import({WorkflowTestIsolation.class,WorkflowTestRegistry.class})
@MapperScan(basePackageClasses=WorkflowTestIsolationMapper.class,annotationClass=org.apache.ibatis.annotations.Mapper.class)
public class WorkflowTestIsolationConfiguration {}
