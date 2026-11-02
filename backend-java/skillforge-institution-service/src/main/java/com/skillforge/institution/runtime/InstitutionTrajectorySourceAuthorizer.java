package com.skillforge.institution.runtime;

import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.trajectory.TrajectoryQuery;
import com.skillforge.core.chat.trajectory.TrajectorySourceAuthorizer;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 轨迹读取沿原成员绑定复验当前历史读权，不能用父会话的权限替代成员授权。 */
@Component
public final class InstitutionTrajectorySourceAuthorizer implements TrajectorySourceAuthorizer {
    private final SessionObservationAuthorizer authorizer;
    private final SessionService sessions;
    private final AgentRunBindings bindings;
    private final ExecutionStore executions;

    public InstitutionTrajectorySourceAuthorizer(SessionObservationAuthorizer authorizer,
            SessionService sessions, AgentRunBindings bindings, ExecutionStore executions) {
        this.authorizer = authorizer;
        this.sessions = sessions;
        this.bindings = bindings;
        this.executions = executions;
    }

    @Override
    public boolean canRead(SessionObservationScope scope, TrajectoryQuery.Resource parent, Source source) {
        if (scope == null || parent == null || source == null
                || scope.kind() != SessionObservationScope.Kind.TENANT
                || !Objects.equals(scope.tenantId(), parent.tenantId())
                || source.tenantId() != parent.tenantId()) return false;
        try {
            authorizer.requireSession(scope, parent.tenantId(), parent.sessionId());
            var product = sessions.require(Long.toString(parent.tenantId()), parent.sessionId());
            if (product.getTenantId() == null || product.getTenantId() != parent.tenantId()
                    || !parent.sessionId().equals(product.getSessionId())) return false;
            SessionOwner origin = owner(product);
            if (origin == null || blank(source.runId()) || blank(source.agentId())) return false;
            var reference = new RunRef(origin.sessionRef().tenantId(), source.runId());
            var persisted = executions.load(reference);
            if (persisted.isEmpty()) return false;
            RunIdentity actual = persisted.orElseThrow().identity();
            if (!actual.runRef().equals(reference) || !actual.owner().sessionRef().sessionId().equals(source.sessionId())
                    || !actual.trace().rootInputId().value().equals(source.rootInputId())
                    || !actual.agentId().value().equals(source.agentId())
                    || !actual.owner().subject().equals(origin.subject())) return false;
            // 根产品会话的读权仍由原授权器拥有；同owner的内部节点不新增成员授权语义。
            if (source.sessionId().equals(parent.sessionId())) return actual.owner().equals(origin);
            if (blank(source.parentRunId())) return false;
            if ("PROGRAM".equals(source.originKind()) || "WORKFLOW_NODE".equals(source.originKind()))
                return inheritsCheckedParent(origin.sessionRef().tenantId(), source.parentRunId(), actual);
            if (!"DELEGATION".equals(source.originKind()) && !"PLAN".equals(source.originKind())) return false;
            var candidate = bindings.find(reference);
            if (candidate.isEmpty()) return false;
            var binding = candidate.orElseThrow();
            SessionTarget target = origin.target().kind() == SessionTarget.Kind.AGENT
                    ? SessionTarget.agent(actual.agentId()) : origin.target();
            if (binding.role() != AgentRunBindings.Role.DELEGATE
                    || !binding.owner().equals(actual.owner()) || !binding.actor().equals(actual.agentId())
                    || !binding.scope().origin().equals(origin)
                    || !binding.owner().target().equals(target)
                    || !binding.parentRunRef().equals(Optional.of(new RunRef(reference.tenantId(), source.parentRunId()))))
                return false;
            bindings.authorizeRead(binding, binding.actor().value());
            return true;
        } catch (SecurityException denied) {
            return false;
        } catch (ApiException denied) {
            if (denied.getStatus().is4xxClientError()) return false;
            throw denied;
        }
    }

    /** 只有投影已验证的真实父边可走此路径；程序/Workflow节点保留原owner与actor。 */
    private boolean inheritsCheckedParent(TenantId tenant, String parentRunId, RunIdentity actual) {
        return executions.load(new RunRef(tenant, parentRunId)).map(ExecutionStore.Run::identity)
                .filter(parent -> parent.owner().equals(actual.owner())
                        && parent.agentId().equals(actual.agentId())
                        && parent.trace().rootInputId().equals(actual.trace().rootInputId()))
                .isPresent();
    }

    private static SessionOwner owner(SessionView session) {
        var reference = new SessionRef(new TenantId(session.getTenantId().toString()), session.getSessionId());
        if (session.getGroupBindingId() != null) {
            var group = new GroupBindingRef(reference.tenantId(), session.getGroupBindingId().toString());
            return new SessionOwner(reference, SessionSubject.group(group), SessionTarget.group(group));
        }
        if (session.getEndUserId() == null || session.getTeamId() == null && session.getAgentId() == null) return null;
        var target = session.getTeamId() == null ? SessionTarget.agent(new AgentId(session.getAgentId().toString()))
                : SessionTarget.team(session.getTeamId());
        return SessionOwner.user(reference, target, new EndUserId(session.getEndUserId()));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
