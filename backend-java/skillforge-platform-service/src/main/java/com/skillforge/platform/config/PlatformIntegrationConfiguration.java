package com.skillforge.platform.config;

import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.integration.IntegrationPersistenceConfiguration;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 平台宿主显式装配独立管理授权、机构接入和公共定义授权。 */
@Configuration
@Import({InstitutionIntegrationService.class, PlatformManagementService.class, IntegrationPersistenceConfiguration.class,
        ServicePrincipalService.class, AgentTemplateGrantService.class})
public class PlatformIntegrationConfiguration { }
