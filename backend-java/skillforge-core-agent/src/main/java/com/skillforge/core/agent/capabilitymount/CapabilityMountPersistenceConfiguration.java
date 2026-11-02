package com.skillforge.core.agent.capabilitymount;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 为独立 CapabilityMount persistence package 注册其 MyBatis mapper。 */
@Configuration
@MapperScan(basePackageClasses = {
        AgentCapabilityMountMapper.class,
        AgentCapabilityMountSetMapper.class
})
public class CapabilityMountPersistenceConfiguration {
}
