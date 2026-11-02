package com.skillforge.core.capability.connector;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
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
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** Connector 单测使用的最小可信 Tool 上下文。 */
final class PlatformToolTestContext {
    private PlatformToolTestContext() {
    }

    static PlatformToolExecutionContext create() {
        TenantId tenant = new TenantId("1");
        InputId input = new InputId("input");
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session"),
                new AgentId("agent"), new EndUserId(1));
        RunIdentity run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner, Set.of(), 1,
                new ResultRef("verification"));
        return new PlatformToolExecutionContext(
                new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(30), () -> false)),
                "invocation", "attempt");
    }
}
