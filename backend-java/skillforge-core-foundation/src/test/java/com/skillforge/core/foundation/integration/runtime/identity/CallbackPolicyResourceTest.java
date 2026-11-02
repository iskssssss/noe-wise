package com.skillforge.core.foundation.integration.runtime.identity;


import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackSigningKeys;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CallbackPolicyResourceTest {
    private static final TenantId TENANT = new TenantId("tenant");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("agent"), new EndUserId(1));
    private static final PrincipalRef.ServicePrincipal SERVICE = new PrincipalRef.ServicePrincipal(TENANT, "service", "integration", 7);

    @Test
    void validRegistrationAndStatusHaveStrictActionBoundCanonicalResources() {
        var requester = new VerifiedIdentity(SERVICE, OWNER, Set.of("sessions.write"), 3, new ResultRef("cause"));
        var trace = new TraceEnvelope("trace", "span", new InputId("root"), Optional.empty());
        var draft = new CallbackRegistrationDraft(new CallbackRef(TENANT, "callback"), OWNER,
                new CallbackTarget.ExecutionTarget(new ExecutionRef(TENANT,"execution"),new RunRef(TENANT,"run"),"action",1), "source", CallbackPayloads.EXECUTION_SCHEMA,
                CallbackPayloads.SIGNATURE_POLICY, JdbcCallbackSigningKeys.reference(SERVICE));
        var run=new RunIdentity(OWNER, OWNER.target().requireAgent(), new InputId("input"), new RunRef(TENANT,"run"), trace, requester.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(),"execution-input:"+(new RunRef(TENANT,"run")).runId()));
        var command = new RegisterCallback(draft, requester, Optional.of(run), new InputId("input"), trace,
                requester.verificationRef(), new IdempotencyKey("key"), "fingerprint");
        var resource = CallbackPolicyResource.registration(command);
        assertEquals(Optional.of(OWNER), CallbackPolicyResource.owner("callback.register", resource));
        assertTrue(CallbackPolicyResource.owner("callback.status", resource).isEmpty());
        var registration = new CallbackRegistration(draft, requester.verificationRef(), CallbackRegistrationStatus.ACTIVE, 1, Instant.EPOCH, Instant.EPOCH);
        var status = new ChangeCallbackStatus(draft.callbackRef(), requester, 1, CallbackRegistrationStatus.REVOKED,
                new InputId("input"), trace, new RequestId("revoke"), "fingerprint");
        assertEquals(Optional.of(OWNER), CallbackPolicyResource.owner("callback.status", CallbackPolicyResource.status(registration, status)));
        for (String value : List.of(resource.canonicalJson() + " ", resource.canonicalJson().replace("c2VydmljZQ.7", "c2VydmljZQ.07"),
                resource.canonicalJson().replace("\"source\"", "\"\\u0073ource\""),
                resource.canonicalJson().replace(CallbackPayloads.SIGNATURE_POLICY, "unknown"),
                resource.canonicalJson().replace(CallbackPayloads.EXECUTION_SCHEMA, "skillforge.runtime.callback.session-event.v1"))) {
            assertTrue(CallbackPolicyResource.owner("callback.register", new TypedPayload(resource.schemaId(), value)).isEmpty());
        }
    }

    @Test
    void executionResourceRequiresFullTargetAndExactPositiveRevision() {
        String body = "[\"tenant\",\"AGENT\",\"agent\",\"agent\",\"session\",\"1\",\"callback\",\"source\","
                + "\"skillforge.runtime.callback.execution-result.v1\",\"hmac-sha256-callback.v1\","
                + "\"service-principal-v1.c2VydmljZQ.7\",\"execution\",\"execution-id\",\"run-id\",\"action\",\"4\",\"cause\"]";
        assertEquals(Optional.of(OWNER), CallbackPolicyResource.owner("callback.register",
                new TypedPayload(CallbackPolicyResource.REGISTER_SCHEMA, body)));
        assertTrue(CallbackPolicyResource.owner("callback.register", new TypedPayload(CallbackPolicyResource.REGISTER_SCHEMA,
                body.replace("\"AGENT\",\"agent\",", ""))).isEmpty());
        for (String invalid : List.of(body.replace("\"4\"", "\"04\""), body.replace("\"run-id\"", "\"\""),
                body.replace("\"action\"", "\"\""), body.replace("\"execution-id\"", "\"\""))) {
            assertTrue(CallbackPolicyResource.owner("callback.register", new TypedPayload(CallbackPolicyResource.REGISTER_SCHEMA, invalid)).isEmpty());
        }
    }

    @Test
    void legacyActionResourceCannotBeUsedForCallbackAuthorization() {
        String body = "[\"tenant\",\"AGENT\",\"agent\",\"agent\",\"session\",\"1\",\"callback\",\"source\","
                + "\"skillforge.runtime.callback.action-result.v1\",\"hmac-sha256-callback.v1\","
                + "\"service-principal-v1.c2VydmljZQ.7\",\"action\",\"run-id\",\"action-id\","
                + "\"invocation-id\",\"4\",\"cause\"]";
        assertTrue(CallbackPolicyResource.owner("callback.register",new TypedPayload(CallbackPolicyResource.REGISTER_SCHEMA,body)).isEmpty());
        for (String invalid : List.of(body.replace("\"4\"", "\"04\""),
                body.replace("\"run-id\"", "\"\""),
                body.replace("\"action-id\"", "\"\""),
                body.replace("\"invocation-id\"", "\"\""),
                body.replace("skillforge.runtime.callback.action-result.v1", CallbackPayloads.EXECUTION_SCHEMA))) {
            assertTrue(CallbackPolicyResource.owner("callback.register",
                    new TypedPayload(CallbackPolicyResource.REGISTER_SCHEMA, invalid)).isEmpty());
        }
    }
}
