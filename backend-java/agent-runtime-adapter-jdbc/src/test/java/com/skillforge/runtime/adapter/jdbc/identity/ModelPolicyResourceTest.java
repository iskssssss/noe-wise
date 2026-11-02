package com.skillforge.runtime.adapter.jdbc.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.ModelResolutionPort.ModelResolutionRequest;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelPolicyResourceTest {
    @Test
    void resolutionResourceBindsFullRunAndStrictActionSchema() {
        var tenant = new TenantId("tenant");
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("agent"), new EndUserId(1));
        var run = new RunIdentity(owner, owner.target().requireAgent(), new InputId("input"), new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", new InputId("root"), Optional.of(new EventId("event"))), new ResultRef("cause"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(),"execution-input:"+(new RunRef(tenant, "run")).runId()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner, Set.of("sessions.write"), 4, new ResultRef("verified"));
        var resource = ModelPolicyResource.resolution(new ModelResolutionRequest(run, identity, new ModelBindingRef(tenant, "binding", 2),
                new RequestedModelStrategy("strategy", "1")));
        assertEquals(Optional.of(owner), ModelPolicyResource.owner("model.resolve", resource));
        assertTrue(ModelPolicyResource.owner("model.generate", resource).isEmpty());
        var dispatch = new TypedPayload(ModelPolicyResource.DISPATCH_SCHEMA, resource.canonicalJson());
        assertEquals(Optional.of(owner), ModelPolicyResource.owner("model.stream", dispatch));
        assertEquals(Optional.of(owner), ModelPolicyResource.owner("model.generate", dispatch));
        assertTrue(ModelPolicyResource.owner("model.resolve", dispatch).isEmpty());
        for (String malformed : List.of(resource.canonicalJson() + " ", resource.canonicalJson().replace("\"2\"", "\"02\""),
                resource.canonicalJson().replace("\"cause\"", "\"\""), resource.canonicalJson().replace("\"run\"", "\"\""),
                resource.canonicalJson().replace("\"strategy\"", "\"\\u0073trategy\""))) {
            assertTrue(ModelPolicyResource.owner("model.resolve", new TypedPayload(resource.schemaId(), malformed)).isEmpty());
        }
    }
}
