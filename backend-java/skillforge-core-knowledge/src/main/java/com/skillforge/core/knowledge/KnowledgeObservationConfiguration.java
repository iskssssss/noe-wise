package com.skillforge.core.knowledge;

import com.skillforge.core.knowledge.mapper.KnowledgeObservationAccessMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 平台宿主按需导出知识观测读取能力，不扩大知识领域的组件或Mapper扫描。 */
@Configuration(proxyBeanMethods = false)
@Import({KnowledgeObservationAccess.class, KnowledgeAuthorizedSqlRule.class})
@MapperScan(basePackageClasses = KnowledgeObservationAccessMapper.class, annotationClass = KnowledgeObservationMapper.class)
public class KnowledgeObservationConfiguration { }
