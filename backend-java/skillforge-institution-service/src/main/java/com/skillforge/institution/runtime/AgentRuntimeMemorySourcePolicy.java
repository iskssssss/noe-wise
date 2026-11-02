package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.institution.group.GroupMemorySources;
import com.skillforge.runtime.memory.MemorySourcePolicy;
import java.util.Objects;

/** 核对已提交来源归属；提取身份的用户级共享提议只能保存为待确认候选。 */
final class AgentRuntimeMemorySourcePolicy implements MemorySourcePolicy {
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests;
    private final GroupMemorySources groupSources;
    AgentRuntimeMemorySourcePolicy(GroupMemorySources groupSources,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests) {
        this.tests=Objects.requireNonNull(tests);this.groupSources=Objects.requireNonNull(groupSources,"groupSources");
    }

    @Override
    public Decision authorize(Request request) {
        Objects.requireNonNull(request, "request");
        SessionOwner owner = request.context().identity().owner();
        if(tests.isolated(Long.parseLong(owner.sessionRef().tenantId().value()),owner.sessionRef().sessionId()))return denied("workflow_test_memory_denied");
        if (!owner.equals(request.context().runIdentity().owner())) {
            return denied("memory_source_owner_mismatch");
        }
        for (CommittedSessionEvent source : request.sources()) {
            if (!owner.equals(source.owner())
                    || !owner.sessionRef().equals(source.owner().sessionRef())) {
                return denied("memory_source_session_mismatch");
            }
        }
        if(owner.subject() instanceof SessionSubject.Group group) {
            boolean extraction = AgentRuntimeMemoryAccessPolicy.extractionIdentity(request.context());
            if (!extraction && (!(request.context().identity().principal() instanceof PrincipalRef.ServicePrincipal)
                    || !(request.context().runIdentity().requester() instanceof RunRequester.EndUser)))
                return denied("memory_group_source_identity_required");
            try {
                for (var source : request.sources()) {
                    var row = extraction
                            ? groupSources.requireForExtraction(source, group.groupBindingRef().groupBindingId())
                            : groupSources.require(source,
                                    ((PrincipalRef.ServicePrincipal) request.context().identity().principal()).integrationId(),
                                    group.groupBindingRef().groupBindingId());
                    if (request.draft().owner() instanceof MemoryOwner.GroupMember member
                            && request.draft().initialStatus() == FactStatus.ACTIVE
                            && (row.authorEndUserId() != member.subjectEndUserId().value()
                                || !extraction && member.subjectEndUserId().value()
                                    != ((RunRequester.EndUser) request.context().runIdentity().requester()).endUserId().value()))
                        return denied("memory_group_subject_source_required");
                }
            } catch (com.skillforge.core.shared.exception.ApiException invalid) {
                return denied("memory_group_source_denied");
            }
        }
        if (!request.draft().type().value().equals(AgentRuntimeHostContracts.MEMORY_PREFERENCE_TYPE)
                && !request.draft().type().value().equals(AgentRuntimeHostContracts.MEMORY_EPISODE_TYPE)) {
            return denied("memory_source_type_unregistered");
        }
        // CREATE 与提取复用都经过此边界；模型 explicit_save/scope 不能替代共享范围确认。
        if (request.draft().scope() == MemoryScope.USER
                && !request.context().identity().scopes().contains("memory:manage")
                && (request.draft().initialStatus() != FactStatus.PENDING_CONFIRM
                    || !request.draft().reviewRequired())) {
            return denied("memory_user_scope_review_required");
        }
        return new Decision.Allowed(AgentRuntimeHostContracts.MEMORY_SOURCE_POLICY);
    }

    private static Decision denied(String reason) {
        return new Decision.Denied(reason, AgentRuntimeHostContracts.MEMORY_SOURCE_POLICY);
    }
}
