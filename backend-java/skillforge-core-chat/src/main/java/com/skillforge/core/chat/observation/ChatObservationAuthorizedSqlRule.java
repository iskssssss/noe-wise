package com.skillforge.core.chat.observation;

import com.skillforge.core.chat.trajectory.TrajectoryListMapper;
import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 平台观测窗口只允许本域固定读语句及精确目标参数，不开放任意聊天或Runtime Mapper。 */
@Component
public final class ChatObservationAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final String TRAJECTORY = "com.skillforge.core.chat.trajectory.TrajectoryMapper.";
    private static final String SESSION = "com.skillforge.core.chat.observation.SessionObservationMapper.";
    private static final Set<String> TRAJECTORY_READS = Set.of("productSession", "runs", "edges", "events", "attempts", "auxiliaryAttempts", "material", "modelMaterials", "citationEvent", "citationAttempts");
    private static final Set<String> SESSION_READS = Set.of("list", "find", "rounds", "sessionSequenceUpperBound", "runs",
            "frozenKeys", "messageUpperBound", "rootExists", "traceUpperBound", "traceNodeSequence", "trace",
            "roundEvents", "contentBelongsToRoot", "usage");

    @Override public boolean allows(Access access) {
        if (access.window() != Window.PLATFORM_OBSERVATION || !access.query() || access.settings()
                || !"foundation.sessions.read".equals(access.action())
                || !access.targetTenants().equals(Set.of(access.tenant()))) return false;
        String id = access.statementId();
        if (id.startsWith(TRAJECTORY) && TRAJECTORY_READS.contains(id.substring(TRAJECTORY.length())))
            return access.parameterEquals("tenant", access.tenant());
        if (id.equals(SESSION + "filterOptions"))
            return access.parameterEquals("scopeTenantId", access.tenant());
        if (id.startsWith(SESSION) && SESSION_READS.contains(id.substring(SESSION.length())))
            return access.parameterEquals("tenantId", access.tenant());
        if (id.equals(SESSION + "publicFacts") || id.equals(SESSION + "messages")) {
            Object scopes = access.parameter("scopes");
            return scopes instanceof Collection<?> values && !values.isEmpty() && values.stream().allMatch(value ->
                    value instanceof SessionObservationPublicFacts.ReadScope scope
                            && Long.toString(scope.tenantId()).equals(access.tenant()));
        }
        if (id.equals("com.skillforge.core.chat.trajectory.TrajectoryListMapper.candidates")
                && access.parameters() instanceof TrajectoryListMapper.Candidates query)
            return query.tenantId() != null && query.tenantId().toString().equals(access.tenant())
                    && (query.scopeTenantId() == null || query.scopeTenantId().toString().equals(access.tenant()));
        return id.equals("com.skillforge.core.chat.persistence.SessionTraceMapper.findEvent")
                && access.parameterEquals("tenantId", access.tenant());
    }
}
