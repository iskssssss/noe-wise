package com.skillforge.core.agent;

import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 领域在已授权事务窗口内允许访问的固定 SQL，未命中不产生权限。 */
@Component
public final class AgentAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final String DEPLOYMENT = "com.skillforge.core.agent.mapper.AgentDeploymentMapper.";
    private static final Set<String> WRITERS = Set.of(
            "com.skillforge.core.agent.mapper.AgentDeploymentMapper",
            "com.skillforge.core.agent.mapper.AgentMapper",
            "com.skillforge.core.agent.mapper.AgentPublicationMapper",
            "com.skillforge.core.agent.capabilitymount.AgentCapabilityMountSetMapper",
            "com.skillforge.core.agent.capabilitymount.AgentCapabilityMountMapper");
    @Override public boolean allows(Access access) {
        String id = access.statementId();
        if (access.window() == Window.INSTITUTION_DEPLOYMENT) {
            if (access.settings()) return id.equals(DEPLOYMENT + "restoreDatabaseContext") && access.restoresTargetTenant();
            return WRITERS.contains(access.namespace())
                    || access.namespace().equals("com.skillforge.core.agent.mapper.AgentTemplateMapper")
                        && (access.query() || id.endsWith(".incrementInstallCount"));
        }
        if (access.window() != Window.DEFINITION_IMPACT) return false;
        if (access.settings()) return id.equals(DEPLOYMENT + "restoreDatabaseContext")
                && access.parameterEquals("tenant", access.originalTenant())
                && access.parameterEquals("platformAdmin", access.originalPlatform());
        if (id.equals(DEPLOYMENT + "databaseContext")) return true;
        if (!id.equals(DEPLOYMENT + "impact")) return false;
        if (!access.targetTenants().contains(access.tenant())
                || !access.parameterEquals("tenantId", access.tenant())
                || !access.parameterEquals("definitionId", Long.toString(access.definitionId())))
            throw new IllegalStateException("definition_impact_target_mismatch");
        return true;
    }
}
