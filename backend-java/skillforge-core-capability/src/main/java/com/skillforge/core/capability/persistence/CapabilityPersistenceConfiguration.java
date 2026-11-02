package com.skillforge.core.capability.persistence;

import com.skillforge.core.capability.SkillDraftMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.context.annotation.Configuration;

/** 能力持久化接口归本域装配；混合业务包仅接纳明确标注的 Mapper。 */
@Configuration(proxyBeanMethods = false)
@MapperScans({
        @MapperScan(basePackageClasses = CapabilityDefinitionMapper.class),
        @MapperScan(basePackageClasses = SkillDraftMapper.class, annotationClass = Mapper.class)
})
public class CapabilityPersistenceConfiguration { }
