package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.institution.group.GroupBindingService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import java.util.Objects;
import java.util.Set;

/**
 * 按已验证 owner 授权 SESSION/USER_AGENT；提取身份只能提出 USER 候选，取代或激活需治理权限。
 */
final class AgentRuntimeMemoryAccessPolicy implements MemoryAccessPolicy {
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests;
    private final GroupBindingService groups;
    private final InstitutionIntegrationService integrations;

    AgentRuntimeMemoryAccessPolicy(GroupBindingService groups, InstitutionIntegrationService integrations,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests) {
        this.tests=Objects.requireNonNull(tests);this.groups=Objects.requireNonNull(groups,"groups");
        this.integrations=Objects.requireNonNull(integrations,"integrations");
    }

    private static final Set<MemoryScope> AGENT_OWNED_SCOPES =
            Set.of(MemoryScope.SESSION, MemoryScope.USER_AGENT);
    private static final Set<String> ALLOWED_TYPES = Set.of(
            AgentRuntimeHostContracts.MEMORY_PREFERENCE_TYPE,
            AgentRuntimeHostContracts.MEMORY_EPISODE_TYPE);

    @Override
    public Decision authorize(Request request) {
        Objects.requireNonNull(request, "request");
        SessionOwner owner = request.context().identity().owner();
        if(tests.isolated(Long.parseLong(owner.sessionRef().tenantId().value()),owner.sessionRef().sessionId()))return denied("workflow_test_memory_denied");
        if (!owner.equals(request.context().runIdentity().owner())) {
            return denied("memory_access_owner_mismatch");
        }
        if (owner.subject() instanceof SessionSubject.Group group) return group(request,group);
        boolean manage = request.context().identity().scopes().contains("memory:manage");
        boolean extract = request.context().identity().scopes().contains("memory:extract");
        for (MemoryScope scope : request.scopes()) {
            if (scope == MemoryScope.USER) {
                // CREATE 的待确认状态由 SourcePolicy 再校验；提取身份不能绕过审核做状态迁移。
                if (request.operation() != Operation.QUERY && !manage
                        && !(extract && request.operation() == Operation.CREATE)) {
                    return denied("memory_access_user_write_denied");
                }
                continue;
            }
            if (!AGENT_OWNED_SCOPES.contains(scope)) {
                return denied("memory_access_scope_denied");
            }
        }
        for (MemoryTypeRef type : request.types()) {
            if (!ALLOWED_TYPES.contains(type.value())) {
                return denied("memory_access_type_unregistered");
            }
        }
        return new Decision.Allowed(AgentRuntimeHostContracts.MEMORY_ACCESS_POLICY);
    }

    private Decision group(Request request,SessionSubject.Group group) {
        if (extractionIdentity(request.context())) return extraction(request, group);
        if(!(request.context().identity().principal() instanceof PrincipalRef.ServicePrincipal principal)
                ||!(request.context().runIdentity().requester() instanceof RunRequester.EndUser requester))
            return denied("memory_group_current_identity_required");
        boolean write=request.operation()!=Operation.QUERY;
        String permission=write?"groups.memory.write":"groups.memory.read";
        try {
            integrations.requirePrincipal(new ServicePrincipalContext.Identity(principal.principalId(),principal.tenantId().value(),
                    principal.integrationId(),principal.credentialVersion(),Set.of(permission)),permission);
            String tenant=principal.tenantId().value(),binding=group.groupBindingRef().groupBindingId();
            var current=groups.requireActive(tenant,principal.integrationId(),binding);
            if(!current.sessionId().equals(request.context().runIdentity().owner().sessionRef().sessionId()))return denied("memory_group_session_mismatch");
            groups.requireMember(tenant,principal.integrationId(),binding,requester.endUserId().value(),false);
            groups.requireServiceAgent(tenant,principal.integrationId(),binding,Long.parseLong(request.context().runIdentity().agentId().value()));
            for(MemoryScope scope:request.scopes())if(!Set.of(MemoryScope.GROUP,MemoryScope.GROUP_MEMBER,MemoryScope.SESSION).contains(scope))return denied("memory_group_scope_denied");
            if(write) {
                if(!request.context().identity().scopes().contains("memory:group:manage"))return denied("memory_group_write_denied");
                if(request.owner() instanceof MemoryOwner.Group)
                    groups.requireMember(tenant,principal.integrationId(),binding,requester.endUserId().value(),true);
                if(request.owner() instanceof MemoryOwner.GroupMember member && request.operation()!=Operation.CREATE
                        &&!member.subjectEndUserId().equals(requester.endUserId()))return denied("memory_group_subject_governance_denied");
            }
            for(MemoryTypeRef type:request.types())if(!ALLOWED_TYPES.contains(type.value()))return denied("memory_access_type_unregistered");
            return new Decision.Allowed(AgentRuntimeHostContracts.MEMORY_ACCESS_POLICY);
        } catch(com.skillforge.core.shared.exception.ApiException denied) {
            return denied("memory_group_current_authorization_denied");
        }
    }

    /** 后台提取用系统身份消费已公开来源，不借用原作者的当前成员权限。 */
    private Decision extraction(Request request, SessionSubject.Group group) {
        if (request.operation() != Operation.QUERY && request.operation() != Operation.CREATE)
            return denied("memory_group_extraction_governance_denied");
        var binding = switch (request.owner()) {
            case MemoryOwner.Group value -> value.groupBindingRef();
            case MemoryOwner.GroupMember value -> value.groupBindingRef();
            default -> null;
        };
        if (!group.groupBindingRef().equals(binding)) return denied("memory_group_owner_mismatch");
        if (request.scopes().stream().anyMatch(scope -> scope != MemoryScope.GROUP && scope != MemoryScope.GROUP_MEMBER))
            return denied("memory_group_scope_denied");
        if (request.types().stream().anyMatch(type -> !ALLOWED_TYPES.contains(type.value())))
            return denied("memory_access_type_unregistered");
        try {
            String tenant = group.groupBindingRef().tenantId().value();
            // integration 属于正式群，系统身份无权由参数切换群或租户。
            var current = groups.requireActiveForExtraction(tenant, group.groupBindingRef().groupBindingId(),
                    request.context().runIdentity().owner().sessionRef().sessionId(),
                    Long.parseLong(request.context().runIdentity().agentId().value()));
            if (current == null) return denied("memory_group_current_authorization_denied");
            return new Decision.Allowed(AgentRuntimeHostContracts.MEMORY_ACCESS_POLICY);
        } catch (com.skillforge.core.shared.exception.ApiException denied) {
            return denied("memory_group_current_authorization_denied");
        }
    }

    static boolean extractionIdentity(com.skillforge.runtime.api.port.PortCallContext context) {
        return context.identity().principal() instanceof PrincipalRef.SystemPrincipal principal
                && "memory-extractor".equals(principal.principalId())
                && context.runIdentity().requester() instanceof RunRequester.System requester
                && requester.principal().equals(principal)
                && principal.tenantId().equals(context.runIdentity().runRef().tenantId())
                && context.identity().scopes().contains("memory:extract");
    }

    private static Decision denied(String reason) {
        return new Decision.Denied(reason, AgentRuntimeHostContracts.MEMORY_ACCESS_POLICY);
    }
}
