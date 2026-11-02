package com.skillforge.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 平台宿主显式组合各领域运营配置定义与授权事务窗口的 SQL 规则。 */
@Configuration(proxyBeanMethods = false)
@Import({
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
public class PlatformDomainContributionsConfiguration { }
