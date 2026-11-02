package com.skillforge.core.billing.export;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.foundation.access.AuthorizedSqlRule.Access;
import com.skillforge.core.foundation.access.AuthorizedSqlRule.Window;
import com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions;
import com.skillforge.core.billing.application.BillingTraceFacts.ReadScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 平台观测只向精确目标机构开放单个有效会话计量SELECT，不扩大普通计费访问权。 */
class BillingAuthorizedSqlRuleTest {
    private static final String SESSION_USAGE =
            "com.skillforge.core.billing.persistence.CostEventMapper.listEffectiveBySessionId";
    private static final String READ_ACTION = "foundation.sessions.read";
    private static final String SCOPED_COSTS =
            "com.skillforge.core.billing.persistence.CostEventMapper.listEffectiveCostsByScopes";
    private final BillingAuthorizedSqlRule rule = new BillingAuthorizedSqlRule();

    @Test
    void scopedCostStatementRequiresExactTargetAndNonemptyBoundedTypedScopes() {
        var scopes = List.of(new ReadScope("session", "run", "root"));
        assertTrue(rule.allows(observation(SCOPED_COSTS, true, false,
                Map.of("tenantId", 7L, "scopes", scopes), "7", Set.of("7"), "", READ_ACTION)));
        for (Object invalid : List.of(List.of(), List.of("session"),
                java.util.Collections.nCopies(201, scopes.getFirst()), Map.of("sessionId", "session"))) {
            assertFalse(rule.allows(observation(SCOPED_COSTS, true, false,
                    Map.of("tenantId", 7L, "scopes", invalid), "7", Set.of("7"), "", READ_ACTION)));
        }
        assertFalse(rule.allows(observation(SCOPED_COSTS, true, false,
                Map.of("tenantId", 8L, "scopes", scopes), "7", Set.of("7"), "", READ_ACTION)));
        assertFalse(rule.allows(observation(SCOPED_COSTS + "Extra", true, false,
                Map.of("tenantId", 7L, "scopes", scopes), "7", Set.of("7"), "", READ_ACTION)));
        assertFalse(rule.allows(observation(SCOPED_COSTS, false, false,
                Map.of("tenantId", 7L, "scopes", scopes), "7", Set.of("7"), "", READ_ACTION)));
        assertFalse(rule.allows(observation(SCOPED_COSTS, true, true,
                Map.of("tenantId", 7L, "scopes", scopes), "7", Set.of("7"), "", READ_ACTION)));
    }

    @Test
    void permitsOnlyTheExactSessionUsageStatementBoundToTheAuthorizedTarget() {
        assertTrue(rule.allows(observation(SESSION_USAGE, true, false,
                Map.of("tenantId", 7L, "sessionId", "session:authorized"), "7", Set.of("7"), "", READ_ACTION)));
        assertTrue(rule.allows(observation(SESSION_USAGE, true, false,
                Map.of("tenantId", "9007199254740993", "sessionId", "session:authorized"),
                "9007199254740993", Set.of("9007199254740993"), "", READ_ACTION)));
    }

    @Test
    void anotherMethodOrGenericMapperQueryNeverInheritsTheSessionReadPermission() {
        for (String statement : List.of(
                "com.skillforge.core.billing.persistence.CostEventMapper.listByRootInputId",
                "com.skillforge.core.billing.persistence.CostEventMapper.sumByRequest",
                "com.skillforge.core.billing.persistence.CostEventMapper.selectList",
                "com.skillforge.core.billing.persistence.CostEventMapper.insert",
                SESSION_USAGE + "Extra",
                "com.skillforge.core.billing.export.PlatformUsageMapper.list")) {
            assertFalse(rule.allows(observation(statement, true, false, Map.of("tenantId", 7L),
                    "7", Set.of("7"), "", READ_ACTION)), statement);
        }
    }

    @Test
    void missingDifferentOrAliasedTenantParametersCannotSelectAnotherInstitutionsUsage() {
        for (Map<String, ?> parameters : List.<Map<String, ?>>of(Map.of(), Map.of("tenantId", 8L),
                Map.of("tenant_id", 7L), Map.of("tenantId", "07"), Map.of("tenantId", 7.0))) {
            assertFalse(rule.allows(observation(SESSION_USAGE, true, false, parameters,
                    "7", Set.of("7"), "", READ_ACTION)), parameters.toString());
        }
        assertFalse(rule.allows(observation(SESSION_USAGE, true, false, new Object(),
                "7", Set.of("7"), "", READ_ACTION)));
    }

    @Test
    void observationCannotWriteChangeSettingsExpandTheTargetSetOrPromoteATenantSubject() {
        assertFalse(rule.allows(observation(SESSION_USAGE, false, false, Map.of("tenantId", 7L),
                "7", Set.of("7"), "", READ_ACTION)));
        assertFalse(rule.allows(observation(SESSION_USAGE, true, true, Map.of("tenantId", 7L),
                "7", Set.of("7"), "", READ_ACTION)));
        assertFalse(rule.allows(observation(SESSION_USAGE, true, false, Map.of("tenantId", 7L),
                "7", Set.of("7", "8"), "", READ_ACTION)));
        assertFalse(rule.allows(observation(SESSION_USAGE, true, false, Map.of("tenantId", 7L),
                "7", Set.of("8"), "", READ_ACTION)));
        assertFalse(rule.allows(observation(SESSION_USAGE, true, false, Map.of("tenantId", 7L),
                "7", Set.of("7"), "7", READ_ACTION)));
        assertFalse(rule.allows(observation(SESSION_USAGE, true, false, Map.of("tenantId", 7L),
                "7", Set.of("7"), "", "foundation.billing.read")));
    }

    @Test
    void otherWindowsDoNotAcquireTheObservationStatementAndHeadquartersKeepsItsOwnStatements() {
        for (Window window : List.of(Window.INSTITUTION_DEPLOYMENT, Window.DEFINITION_IMPACT, Window.HEADQUARTERS_USAGE)) {
            assertFalse(rule.allows(new Access(window, SESSION_USAGE, true, false, Map.of("tenantId", 7L),
                    "7", 0, Set.of("7"), "", null, READ_ACTION)));
        }
        assertTrue(rule.allows(new Access(Window.HEADQUARTERS_USAGE,
                "com.skillforge.core.billing.export.PlatformUsageMapper.summarize", true, false, Map.of(),
                "7", 0, Set.of("8"), "7", null, HeadquartersUsageActions.SUMMARY)));
        assertFalse(rule.allows(observation("com.skillforge.core.billing.export.PlatformUsageMapper.summarize", true,
                false, Map.of("tenantId", 7L), "7", Set.of("7"), "", READ_ACTION)));
    }

    private static Access observation(String statement, boolean query, boolean settings, Object parameters,
            String tenant, Set<String> targets, String originalTenant, String action) {
        return new Access(Window.PLATFORM_OBSERVATION, statement, query, settings, parameters,
                tenant, 0, targets, originalTenant, null, action);
    }
}
