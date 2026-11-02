package com.skillforge.core.chat.trajectory;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 观测投影与只读Mapper由本域装配，平台和机构复用相同实现。 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = TrajectoryConfiguration.class)
@MapperScan(basePackageClasses = TrajectoryListMapper.class, annotationClass = Mapper.class)
@Import({com.skillforge.core.chat.observation.ChatObservationAuthorizedSqlRule.class,
        com.skillforge.core.knowledge.KnowledgeObservationConfiguration.class})
public class TrajectoryConfiguration { }
