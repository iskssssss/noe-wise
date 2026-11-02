package com.skillforge.core.capability;

import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 领域在已授权事务窗口内允许访问的固定 SQL，未命中不产生权限。 */
@Component
public final class CapabilityAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final String BINDER = "com.skillforge.core.capability.CapabilityCatalogContextMapper.bind";
    private static final Set<String> WRITERS = Set.of(
            "com.skillforge.core.capability.CapabilityCatalogContextMapper",
            "com.skillforge.core.capability.persistence.CapabilityDefinitionMapper",
            "com.skillforge.core.capability.persistence.CapabilityVersionMapper",
            "com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper");
    @Override public boolean allows(Access access) {
        if (access.window() != Window.INSTITUTION_DEPLOYMENT && access.window() != Window.DEFINITION_IMPACT) return false;
        if (access.settings()) return access.statementId().equals(BINDER) && access.bindsCurrentTenant();
        return access.window() == Window.INSTITUTION_DEPLOYMENT && WRITERS.contains(access.namespace());
    }
}
