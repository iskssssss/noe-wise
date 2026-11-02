package com.skillforge.platform.sessionmodelattempt;

import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 私有模型诊断仅在已有平台会话读取窗口中查询明确机构的精确原材料。 */
@Component
public final class ModelAttemptObservationAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final String PREFIX = "com.skillforge.platform.sessionmodelattempt.ModelAttemptDiagnosticMapper.";
    private static final Set<String> READS = Set.of("attempt", "descendantAttempt", "capabilityFacts", "materials");

    @Override public boolean allows(Access access) {
        return access.window() == Window.PLATFORM_OBSERVATION && access.query() && !access.settings()
                && "foundation.sessions.read".equals(access.action())
                && access.targetTenants().equals(Set.of(access.tenant()))
                && access.statementId().startsWith(PREFIX)
                && READS.contains(access.statementId().substring(PREFIX.length()))
                && access.parameterEquals("tenant", access.tenant());
    }
}
