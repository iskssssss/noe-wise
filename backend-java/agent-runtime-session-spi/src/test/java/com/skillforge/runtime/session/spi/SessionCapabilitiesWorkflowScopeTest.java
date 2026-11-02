package com.skillforge.runtime.session.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Workflow 准入目标必须同时属于原挂载及原输入，不能借句柄前缀复用其他 Run 的绑定。 */
class SessionCapabilitiesWorkflowScopeTest {
    private static final TenantId TENANT = new TenantId("tenant");
    private static final SessionOwner OWNER = SessionOwner.user(
            new SessionRef(TENANT, "session"), new AgentId("agent"), new EndUserId(7));
    private static final InputId INPUT = new InputId("client-input");
    private static final CapabilityRef CAPABILITY = new CapabilityRef(CapabilityType.WORKFLOW, "workflow", "v1");

    @Test
    void snapshotAcceptsTheExactAdmissionScopedWorkflowTarget() {
        var snapshot = snapshot(mount("workflow:101:" + INPUT.value()));
        assertEquals(List.of("workflow:101:" + INPUT.value()), snapshot.handles());
        assertEquals(INPUT, snapshot.reference().inputId());
    }

    @Test
    void snapshotRejectsAnotherAdmissionInputEvenWithTheSameMountAndCapability() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(mount("workflow:101:other-input")));
    }

    @Test
    void mountRejectsAnotherMountAndUnscopedTargets() {
        assertThrows(IllegalArgumentException.class, () -> mount("workflow:102:" + INPUT.value()));
        assertThrows(IllegalArgumentException.class, () -> mount("workflow:101"));
        assertThrows(IllegalArgumentException.class, () -> mount("workflow:101:"));
    }

    private static SessionCapabilities.Mount mount(String handle) {
        var target = new FrozenWorkflowTarget(new WorkflowHandle(handle), "instance", 1,
                CAPABILITY, Duration.ofSeconds(30), 1);
        return new SessionCapabilities.Mount("101", 1, 0, CAPABILITY,
                new TypedPayload("descriptor.v1", "{}"), Optional.empty(), Optional.of(target),
                Optional.empty(), Optional.empty());
    }

    private static SessionCapabilities.Snapshot snapshot(SessionCapabilities.Mount mount) {
        var identity = new VerifiedIdentity(new PrincipalRef.ServicePrincipal(TENANT, "service", "chat", 1),
                OWNER, Set.of("runtime:accept"), 1, new ResultRef("verification"));
        return new SessionCapabilities.Snapshot(OWNER,
                new CapabilitySnapshotRef(OWNER.sessionRef(), INPUT, INPUT.value() + ":capability-mount-snapshot", 1),
                identity, new ResultRef("authorization"), List.of(mount), Instant.parse("2026-10-09T02:00:00Z"));
    }
}
