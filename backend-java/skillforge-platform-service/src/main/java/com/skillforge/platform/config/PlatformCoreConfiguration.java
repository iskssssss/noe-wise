package com.skillforge.platform.config;

import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.capability.CapabilityCatalogConfiguration;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.SkillPackageCatalogService;
import com.skillforge.core.capability.SkillDraftService;
import com.skillforge.core.capability.SkillDraftRepository;
import com.skillforge.core.shared.config.AuditMetaObjectHandler;
import com.skillforge.core.foundation.config.BeanConfig;
import com.skillforge.core.shared.config.MybatisPlusConfig;
import com.skillforge.core.foundation.application.AuditLogService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.billing.application.BillingStateService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.chat.session.SessionTraceService;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.integration.publicread.RuntimeCompletedAnswerReader;
import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationRepository;
import com.skillforge.core.chat.observation.SessionObservationService;
import com.skillforge.core.workflow.WorkflowAssetService;
import com.skillforge.core.workflow.WorkflowSecurityScanner;
import com.skillforge.core.workflow.WorkflowTemplateService;
import com.skillforge.core.workflow.WorkflowReviewService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 平台运营服务的显式核心组件装配边界。 */
@Configuration
@Import({
        com.skillforge.core.foundation.integration.ManagementAuthenticationConfiguration.class,
        com.skillforge.core.foundation.institution.InstitutionRelationConfiguration.class,
        com.skillforge.core.agent.deployment.AgentManagementPermissionContributor.class,
        com.skillforge.core.shared.web.ExternalAuthBodyCachingFilter.class,
        com.skillforge.core.foundation.access.FoundationAccessConfiguration.class,
        com.skillforge.core.foundation.workflowtest.WorkflowTestIsolationConfiguration.class,
        com.skillforge.core.foundation.catalog.CatalogConfiguration.class,
        com.skillforge.core.foundation.security.SecurityConfiguration.class,
        com.skillforge.core.foundation.messaging.config.MessagingConfiguration.class,
        com.skillforge.core.foundation.assets.FoundationAssetsConfiguration.class,
        com.skillforge.core.foundation.jobs.FoundationJobsConfiguration.class,
        com.skillforge.core.foundation.operations.FoundationOperationsConfiguration.class,
        com.skillforge.core.foundation.management.ManagementPermissionContributor.class,
        com.skillforge.core.shared.config.BusinessTimeWebConfiguration.class,
        AuditMetaObjectHandler.class,
        BeanConfig.class,
        MybatisPlusConfig.class,
        com.skillforge.core.foundation.persistence.FoundationPersistenceConfiguration.class,
        com.skillforge.core.billing.persistence.BillingPersistenceConfiguration.class,
        com.skillforge.core.capability.persistence.CapabilityPersistenceConfiguration.class,
        com.skillforge.core.chat.persistence.ChatPersistenceConfiguration.class,
        com.skillforge.core.channel.ChannelPublicationConfiguration.class,
        com.skillforge.core.capability.application.OpenPlatformResourceLifecycleService.class,
        com.skillforge.core.shared.config.MapperExecutionLoggingInterceptor.class,
        com.skillforge.core.foundation.config.TenantRlsInterceptor.class,
        CapabilityCatalogConfiguration.class,
        CapabilityCatalogDatabaseContext.class,
        CapabilityCatalogService.class,
        SkillPackageCatalogService.class,
        SkillDraftService.class,
        com.skillforge.core.capability.SkillCreationService.class,
        SkillDraftRepository.class,
        AgentTemplateService.class,
        com.skillforge.core.agent.AgentDefinitionLifecycleService.class,
        AuditLogService.class,
        AuditService.class,
        TenantBillingProfileService.class,
        BillingStateService.class,
        com.skillforge.core.billing.application.BillingTraceQueryService.class,
        SessionTraceService.class,
        SessionObservationAuthorizer.class,
        SessionObservationRepository.class,
        SessionObservationService.class,
        com.skillforge.core.chat.trajectory.TrajectoryConfiguration.class,
        com.skillforge.core.knowledge.KnowledgeObservationConfiguration.class,
        PublicOutputGuard.class,
        RuntimeCompletedAnswerReader.class,
        com.skillforge.core.billing.application.ModelBillingService.class,
        CryptoService.class,
        WorkflowAssetService.class,
        WorkflowSecurityScanner.class,
        WorkflowTemplateService.class,
        WorkflowReviewService.class
})
public class PlatformCoreConfiguration {
}
