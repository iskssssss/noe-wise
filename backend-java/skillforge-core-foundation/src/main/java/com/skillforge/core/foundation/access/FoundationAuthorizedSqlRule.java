package com.skillforge.core.foundation.access;

import java.util.Set;
import org.springframework.stereotype.Component;

/** 基础身份、审计和配额对总部部署窗口贡献的有限存储规则。 */
@Component
public final class FoundationAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final String CONTEXT = "com.skillforge.core.foundation.catalog.entitlements.DirectDeploymentScope$ContextMapper";
    private static final Set<String> WRITERS = Set.of("com.skillforge.core.foundation.persistence.AuditLogMapper", CONTEXT);
    private static final Set<String> READERS = Set.of("com.skillforge.core.foundation.settings.OperationalSettingsMapper",
            "com.skillforge.core.foundation.catalog.entitlements.EntitlementMapper");
    private static final Set<String> INTEGRATION_READS = Set.of(
            "com.skillforge.core.foundation.integration.InstitutionIntegrationMapper.tenantStatus",
            "com.skillforge.core.foundation.integration.InstitutionIntegrationMapper.integrationExists");
    @Override public boolean allows(Access access) {
        if (access.window() != Window.INSTITUTION_DEPLOYMENT) return false;
        if (access.settings()) {
            return access.statementId().equals(CONTEXT + ".bindTenant") && access.parameterEquals("tenant", access.tenant())
                    || access.statementId().equals(CONTEXT + ".restore") && access.restoresTargetTenant();
        }
        return WRITERS.contains(access.namespace()) || access.query()
                && (READERS.contains(access.namespace()) || INTEGRATION_READS.contains(access.statementId()));
    }
}
