package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** USER 读写分权：extract 可提交候选，治理变更必须由 manage 授权。 */
class AgentRuntimeMemoryAccessPolicyTest {

    private static final TenantId TENANT = new TenantId("701");
    private static final SessionOwner OWNER = SessionOwner.user(
            new SessionRef(TENANT, "session-1"), new AgentId("801"), new EndUserId(7));
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests=org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class);
    private final AgentRuntimeMemoryAccessPolicy policy = new AgentRuntimeMemoryAccessPolicy(
            org.mockito.Mockito.mock(com.skillforge.institution.group.GroupBindingService.class),
            org.mockito.Mockito.mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class), tests);

    @Test
    void chatIdentityMayQueryUserButNotWrite() {
        PortCallContext chat = context(Set.of("sessions.write"));
        assertInstanceOf(MemoryAccessPolicy.Decision.Allowed.class, policy.authorize(request(
                chat, MemoryAccessPolicy.Operation.QUERY, MemoryScope.USER)));
        MemoryAccessPolicy.Decision.Denied denied = assertInstanceOf(
                MemoryAccessPolicy.Decision.Denied.class, policy.authorize(request(
                        chat, MemoryAccessPolicy.Operation.CREATE, MemoryScope.USER)));
        assertEquals("memory_access_user_write_denied", denied.reasonCode());
    }

    @Test
    void extractorMayCreateUserFacts() {
        assertInstanceOf(MemoryAccessPolicy.Decision.Allowed.class, policy.authorize(request(
                context(Set.of("memory:extract")), MemoryAccessPolicy.Operation.CREATE,
                MemoryScope.USER)));
    }

    /** 提取权限不能借 SUPERSEDE 或 TRANSITION 激活、替换共享事实。 */
    @Test
    void extractorCannotMutateExistingUserWhileManagerCan() {
        for (MemoryAccessPolicy.Operation operation : Set.of(
                MemoryAccessPolicy.Operation.SUPERSEDE, MemoryAccessPolicy.Operation.TRANSITION)) {
            MemoryAccessPolicy.Decision.Denied denied = assertInstanceOf(
                    MemoryAccessPolicy.Decision.Denied.class, policy.authorize(request(
                            context(Set.of("memory:extract")), operation, MemoryScope.USER)));
            assertEquals("memory_access_user_write_denied", denied.reasonCode());
            assertInstanceOf(MemoryAccessPolicy.Decision.Allowed.class, policy.authorize(request(
                    context(Set.of("memory:manage")), operation, MemoryScope.USER)));
        }
    }

    @Test void isolatedTestSessionCannotReadOrWriteMemoryEvenWithManagementScope() {
        org.mockito.Mockito.when(tests.isolated(701L,"session-1")).thenReturn(true);
        for(var operation:java.util.List.of(MemoryAccessPolicy.Operation.QUERY,MemoryAccessPolicy.Operation.CREATE)) {
            var denied=assertInstanceOf(MemoryAccessPolicy.Decision.Denied.class,policy.authorize(request(context(Set.of("memory:manage","memory:extract")),operation,MemoryScope.USER)));
            assertEquals("workflow_test_memory_denied",denied.reasonCode());
        }
    }

    private static MemoryAccessPolicy.Request request(
            PortCallContext context, MemoryAccessPolicy.Operation operation, MemoryScope scope) {
        return new MemoryAccessPolicy.Request(
                context, new MemoryOwner.User(TENANT, OWNER.requireUser()), operation, Set.of(scope),
                Set.of(new MemoryTypeRef(AgentRuntimeHostContracts.MEMORY_PREFERENCE_TYPE)),
                operation == MemoryAccessPolicy.Operation.SUPERSEDE
                        || operation == MemoryAccessPolicy.Operation.TRANSITION
                        ? Optional.of(new MemoryFactRef(TENANT, "fact-1")) : Optional.empty());
    }

    private static PortCallContext context(Set<String> scopes) {
        InputId input = new InputId("memory-access-1");
        RunIdentity run = new RunIdentity(OWNER, new AgentId((OWNER).target().id()), input, new RunRef(TENANT, "run-1"), new TraceEnvelope("trace-1", "span-1", input, Optional.empty()), new ResultRef("causation-1"), new RunRequester.EndUser((OWNER).requireUser()), java.util.Optional.empty(), new RunOriginRef((OWNER).sessionRef().tenantId(), "input:" + (input).value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, OWNER.requireUser()), OWNER,
                scopes, 1, new ResultRef("verified-1"));
        return new PortCallContext(run, identity,
                new InvocationControl(Instant.parse("2026-09-18T00:00:00Z").plusSeconds(30),
                        () -> false));
    }
}
