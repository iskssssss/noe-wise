package com.skillforge.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 平台模型目录的受控装配，不引入机构服务的业务扫描。 */
@Configuration
@Import({
        com.skillforge.core.model.PlatformModelsConfiguration.class,
        com.skillforge.core.model.TimePricingPresetService.class,
        com.skillforge.core.knowledge.PlatformKnowledgeModelProbeConfiguration.class
})
public class PlatformModelsConfiguration { }
