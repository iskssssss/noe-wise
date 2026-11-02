package com.skillforge.core.capability.connector;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.CancellationSignal;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformToolExecutionContextTest {

    @Test
    void rejectsVerifiedIdentityForAnotherSessionOwner() {
        TenantId tenant = new TenantId("1");
        SessionOwner runOwner = owner(tenant, "session");
        SessionOwner verifiedOwner = owner(tenant, "other-session");

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> new PortCallContext(
                run(runOwner), identity(tenant, verifiedOwner),
                control(Instant.parse("2026-09-24T00:00:00Z"), () -> false)));

        assertEquals("runIdentity owner must match verified identity owner", rejected.getMessage());
    }

    @Test
    void exposesDeadlineAndCancellationSignalFromTrustedCallContext() {
        TenantId tenant = new TenantId("1");
        SessionOwner owner = owner(tenant, "session");
        Instant deadline = Instant.parse("2026-09-24T00:00:30Z");
        CancellationSignal cancellationSignal = () -> false;
        PlatformToolExecutionContext context = new PlatformToolExecutionContext(
                new PortCallContext(run(owner), identity(tenant, owner), control(deadline, cancellationSignal)),
                "invocation", "attempt");

        assertEquals(deadline, context.deadlineAt());
        assertSame(cancellationSignal, context.cancellationSignal());
        assertEquals(false, context.programChild());assertEquals(false, context.workflowNode());
        var program = new PlatformToolExecutionContext(context.callContext(), "invocation", "attempt", true);
        assertEquals(true, program.programChild());assertEquals(false, program.workflowNode());
        var workflow = new PlatformToolExecutionContext(context.callContext(), "invocation", "attempt", false, true);
        assertEquals(false, workflow.programChild());assertEquals(true, workflow.workflowNode());
    }

    private static SessionOwner owner(TenantId tenant, String sessionId) {
        return SessionOwner.user(new SessionRef(tenant, sessionId), new AgentId("agent"), new EndUserId(1));
    }

    private static RunIdentity run(SessionOwner owner) {
        InputId input = new InputId("input");
        return new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(owner.sessionRef().tenantId(), "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
    }

    private static VerifiedIdentity identity(TenantId tenant, SessionOwner owner) {
        return new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verification"));
    }

    private static InvocationControl control(Instant deadline, CancellationSignal cancellationSignal) {
        return new InvocationControl(deadline, cancellationSignal);
    }
}
