package com.skillforge.core.model.invocation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import com.skillforge.core.model.ModelAuthorizedSqlRule;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 平台目标窗口只授予精确模型材料SELECT，不改变动作、目标或写入范围。 */
class ModelCallRecordSqlRuleTest {
    private final ModelAuthorizedSqlRule rule = new ModelAuthorizedSqlRule();

    @Test void onlyExplicitReadMethodsInsideTheAuthorizedTargetWindowAreAllowed() {
        assertTrue(rule.allows(access("find", true, false, "7", Set.of("7"), "foundation.sessions.read", 7L)));
        assertTrue(rule.allows(access("status", true, false, "7", Set.of("7"), "foundation.sessions.read", 7L)));
        assertFalse(rule.allows(access("recordRequest", false, false, "7", Set.of("7"), "foundation.sessions.read", 7L)));
        assertFalse(rule.allows(access("find", false, false, "7", Set.of("7"), "foundation.sessions.read", 7L)));
        assertFalse(rule.allows(access("find", true, true, "7", Set.of("7"), "foundation.sessions.read", 7L)));
        assertFalse(rule.allows(access("find", true, false, "7", Set.of("7", "8"), "foundation.sessions.read", 7L)));
        assertFalse(rule.allows(access("find", true, false, "7", Set.of("7"), "other.action", 7L)));
        assertFalse(rule.allows(access("find", true, false, "7", Set.of("7"), "foundation.sessions.read", 8L)));
    }

    private static AuthorizedSqlRule.Access access(String method, boolean query, boolean settings, String tenant,
            Set<String> targets, String action, long argumentTenant) {
        return new AuthorizedSqlRule.Access(AuthorizedSqlRule.Window.PLATFORM_OBSERVATION,
                "com.skillforge.core.model.invocation.ModelCallRecordMapper." + method, query, settings,
                Map.of("tenant", argumentTenant), tenant, 0, targets, "", "false", action);
    }
}
