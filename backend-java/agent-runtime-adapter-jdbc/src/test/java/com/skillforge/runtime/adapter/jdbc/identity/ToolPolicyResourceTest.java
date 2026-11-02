package com.skillforge.runtime.adapter.jdbc.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.IdempotencyKey;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolPolicyResourceTest {
    @Test
    void toolActionRequiresCompleteCanonicalRunSnapshotAndTargetResource() {
        var owner = SessionOwner.user(new SessionRef(new TenantId("tenant"), "session"), new AgentId("agent"), new EndUserId(1));
        var run = new RunIdentity(owner, owner.target().requireAgent(), new InputId("input"),
                new RunRef(owner.sessionRef().tenantId(), "run"),
                new TraceEnvelope("trace", "span", new InputId("root"), Optional.empty()), new ResultRef("cause"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(),
                new RunOriginRef(owner.sessionRef().tenantId(), "execution-input:run"));
        var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.TEST_MOCK, "provider", "tool");
        var target = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:mount"),
                new CapabilityRef(CapabilityType.TOOL, "capability", "version"), "deployment", 3,
                new TypedPayload("input.v1", "{}"), new TypedPayload("output.v1", "{}"),
                ToolEffect.READ_ONLY, false, Duration.ofSeconds(2), true,
                ToolPortal.ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        var payload = ToolPolicyResource.invocation(run, binding, target, new IdempotencyKey("key"));
        String body = payload.canonicalJson();
        assertEquals(Optional.of(owner), ToolPolicyResource.owner("tool.invoke", payload));
        assertTrue(ToolPolicyResource.owner("tool.invoke", new TypedPayload(ToolPolicyResource.SCHEMA,
                body.replace("\"AGENT\",\"agent\",", ""))).isEmpty());
        assertTrue(ToolPolicyResource.owner("model.generate", payload).isEmpty());
        assertTrue(ToolPolicyResource.owner("tool.invoke", new TypedPayload("unknown", body)).isEmpty());
        for (String invalid : List.of(body + " ", body.replace("\"3\"", "\"03\""), body.replace("\"3\"", "\"0\""),
                body.replace("tool:mount", ""), body.replace("READ_ONLY", "UNKNOWN"), body.replace("false", "FALSE"),
                body.replace("PT2S", "PT2.000S"), body.replace("\"cause\"", "\"\""), body.replace("\"provider\"", "\"\""),
                body.substring(0, body.length() - 1) + ",\"extra\"]")) {
            assertTrue(ToolPolicyResource.owner("tool.invoke", new TypedPayload(ToolPolicyResource.SCHEMA, invalid)).isEmpty());
        }
    }
}
