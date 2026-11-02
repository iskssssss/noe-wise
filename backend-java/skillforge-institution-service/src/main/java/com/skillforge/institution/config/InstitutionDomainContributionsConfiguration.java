package com.skillforge.institution.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 机构宿主使用的领域设置、授权 SQL 规则与渠道发布事务参与者。 */
@Configuration(proxyBeanMethods = false)
@Import({
        com.skillforge.core.foundation.workflowtest.WorkflowTestIsolationConfiguration.class,
        com.skillforge.core.channel.ChannelPublicationConfiguration.class,
        com.skillforge.core.foundation.settings.FoundationOperationalSettings.class,
        com.skillforge.core.foundation.runtimeconfig.RuntimeBudgetOperationalSettings.class,
        com.skillforge.core.billing.settings.BillingOperationalSettings.class,
        com.skillforge.core.chat.contract.ChatOperationalSettings.class,
        com.skillforge.core.capability.connector.ExecutionOperationalSettings.class,
        com.skillforge.core.knowledge.KnowledgeSettingsContributor.class,
        com.skillforge.core.foundation.access.FoundationAuthorizedSqlRule.class,
        com.skillforge.core.billing.export.BillingAuthorizedSqlRule.class,
        com.skillforge.core.capability.CapabilityAuthorizedSqlRule.class,
        com.skillforge.core.agent.AgentAuthorizedSqlRule.class,
        com.skillforge.core.workflow.WorkflowAuthorizedSqlRule.class,
        com.skillforge.core.knowledge.KnowledgeAuthorizedSqlRule.class,
        com.skillforge.core.model.ModelAuthorizedSqlRule.class
})
public class InstitutionDomainContributionsConfiguration { }
