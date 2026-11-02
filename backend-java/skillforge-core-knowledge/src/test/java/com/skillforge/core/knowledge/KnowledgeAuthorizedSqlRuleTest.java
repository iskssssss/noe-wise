package com.skillforge.core.knowledge;

import com.skillforge.core.foundation.access.AuthorizedSqlRule.Access;
import com.skillforge.core.foundation.access.AuthorizedSqlRule.Window;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 平台知识观测窗口只允许显式目标的固定来源核验，不能扩成整个知识域查询或设置语句。 */
class KnowledgeAuthorizedSqlRuleTest {
    private static final String READABLE = "com.skillforge.core.knowledge.mapper.KnowledgeObservationAccessMapper.readable";
    private final KnowledgeAuthorizedSqlRule rule = new KnowledgeAuthorizedSqlRule();

    @Test void observationAllowsOnlyItsReadStatementWithMatchingTargetTenant() {
        assertTrue(rule.allows(access(READABLE, true, false, "1")));
        assertFalse(rule.allows(access(READABLE, true, false, "2")));
        assertFalse(rule.allows(access(READABLE, false, false, "1")));
        assertFalse(rule.allows(access(READABLE, true, true, "1")));
        assertFalse(rule.allows(access("com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper.selectById", true, false, "1")));
        assertFalse(rule.allows(access(READABLE + "Extra", true, false, "1")));
    }

    private static Access access(String statement, boolean query, boolean settings, String tenantParameter) {
        return new Access(Window.PLATFORM_OBSERVATION, statement, query, settings, Map.of("tenant", tenantParameter),
                "1", 0, Set.of("1"), "", null, "foundation.sessions.read");
    }
}
