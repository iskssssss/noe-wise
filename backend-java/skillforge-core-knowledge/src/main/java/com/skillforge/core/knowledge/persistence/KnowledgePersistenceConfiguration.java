package com.skillforge.core.knowledge.persistence;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 知识变更提案的持久化映射，由知识领域装配。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = KnowledgeWriteProposalMapper.class,
        annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class KnowledgePersistenceConfiguration { }
