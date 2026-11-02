package com.skillforge.core.billing.export;

import com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions;

import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import com.skillforge.core.billing.application.BillingTraceFacts;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 领域在已授权事务窗口内允许访问的固定 SQL，未命中不产生权限。 */
@Component
public final class BillingAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final String MAPPER = "com.skillforge.core.billing.export.PlatformUsageMapper.";
    private static final String OBSERVATION_SESSION_USAGE =
            "com.skillforge.core.billing.persistence.CostEventMapper.listEffectiveBySessionId";
    private static final String SESSION_READ_ACTION = "foundation.sessions.read";
    private static final String OBSERVATION_SCOPED_COSTS =
            "com.skillforge.core.billing.persistence.CostEventMapper.listEffectiveCostsByScopes";
    @Override public boolean allows(Access access) {
        if (access.window() == Window.BILLING_SCAN) {
            if (access.settings()) return false;
            if (access.query()) return "com.skillforge.core.billing.persistence.PlanMapper.selectById".equals(access.statementId())
                    || "com.skillforge.core.billing.persistence.CostEventMapper.countRequestsForBilling".equals(access.statementId())
                        && access.parameterEquals("tenantId", access.tenant());
            return "com.skillforge.core.foundation.persistence.TenantMapper.transitionBillingState".equals(access.statementId())
                    && access.parameterEquals("tenantId", access.tenant());
        }
        if (access.window() == Window.PLATFORM_OBSERVATION) {
            // 只允许轨迹已核会话的有效计量查询，不让平台窗口获得任意计费Mapper或QueryWrapper读取权。
            return access.query() && !access.settings()
                    && (OBSERVATION_SESSION_USAGE.equals(access.statementId())
                        || OBSERVATION_SCOPED_COSTS.equals(access.statementId()) && boundedCostScopes(access.parameter("scopes")))
                    && SESSION_READ_ACTION.equals(access.action())
                    && access.tenant() != null && !access.tenant().isBlank()
                    && access.targetTenants().equals(Set.of(access.tenant()))
                    && "".equals(access.originalTenant())
                    && access.parameterEquals("tenantId", access.tenant());
        }
        if (access.window() != Window.HEADQUARTERS_USAGE || !access.query() || access.settings()) return false;
        return HeadquartersUsageActions.SUMMARY.equals(access.action())
                ? access.statementId().equals(MAPPER + "summarize")
                : access.statementId().equals(MAPPER + "list") || access.statementId().equals(MAPPER + "hasPending")
                    || access.statementId().equals(MAPPER + "grantHops");
    }

    private static boolean boundedCostScopes(Object value) {
        return value instanceof List<?> scopes && !scopes.isEmpty()
                && scopes.size() <= BillingTraceFacts.COST_SCOPE_BATCH_SIZE
                && scopes.stream().allMatch(BillingTraceFacts.ReadScope.class::isInstance);
    }
}
