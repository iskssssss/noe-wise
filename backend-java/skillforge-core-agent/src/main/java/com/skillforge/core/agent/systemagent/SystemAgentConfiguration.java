package com.skillforge.core.agent.systemagent;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 平台智能体持久化装配；不扫描业务执行端口。 */
@Configuration
@MapperScan(basePackageClasses=SystemAgentMapper.class, annotationClass=Mapper.class)
public class SystemAgentConfiguration {}
