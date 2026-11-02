package com.skillforge.runtime.adapter.inmemory.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.IdentityPolicyPort.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryIdentityPolicyPortTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final TenantId TENANT = new TenantId("tenant");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("agent"), new EndUserId(1));
    private static final PrincipalRef PRINCIPAL = new PrincipalRef.EndUserPrincipal(TENANT, OWNER.requireUser());
    private static final String SCHEMA = "skillforge.runtime.session.admission-resource.v1";

    @Test void capabilityFreezeRequiresDedicatedResourceAndCurrentWriteScope() {
        var policy=new InMemoryIdentityPolicyPort(Clock.fixed(NOW,ZoneOffset.UTC));var auth=register(policy);var identity=identity(policy,auth);
        var resource=new TypedPayload("skillforge.runtime.session.capability-freeze-resource.v1","[\"tenant\",\"agent\",\"session\",\"1\",\"input\"]");
        assertEquals(10,assertInstanceOf(AuthorizationResult.Allowed.class,policy.authorize(authorization(identity,"session.capabilities.freeze",resource))).policyRevision());
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"session.claim",resource)));
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"session.capabilities.freeze",new TypedPayload(resource.schemaId(),resource.canonicalJson().replace("\"1\"","\"2\"")))));
        policy.updateScopes(auth,Set.of("sessions.read"));
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"session.capabilities.freeze",resource)));
    }

    @Test void sessionResourcesRequireExactShapesAndCurrentScope() {
        var policy=new InMemoryIdentityPolicyPort(Clock.fixed(NOW,ZoneOffset.UTC));var auth=register(policy);var identity=identity(policy,auth);
        var claim=new TypedPayload("skillforge.runtime.session.claim-resource.v1","[\"tenant\",\"agent\",\"session\",\"1\"]");
        var run=new TypedPayload("skillforge.runtime.session.run-resource.v1","[\"tenant\",\"agent\",\"session\",\"1\",\"run\",\"input\",\"cause\",\"trace\",\"span\",\"root\",\"\",\"cancel\"]");
        assertEquals(10,assertInstanceOf(AuthorizationResult.Allowed.class,policy.authorize(authorization(identity,"session.claim",claim))).policyRevision());
        for(var action:List.of("session.execute","session.commit","session.cancel"))assertInstanceOf(AuthorizationResult.Allowed.class,policy.authorize(authorization(identity,action,run)));
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"session.commit",claim)));
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"session.cancel",new TypedPayload(run.schemaId(),run.canonicalJson().replace("\"cancel\"","\"\"")))));
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"session.commit",new TypedPayload(run.schemaId(),run.canonicalJson()+" "))));
        policy.updateScopes(auth,Set.of("sessions.read"));
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"session.execute",run)));
    }

    @Test
    void knowledgeExecutionUsesOnlyStrictResourceAndCurrentWriteScope() {
        var policy=new InMemoryIdentityPolicyPort(Clock.fixed(NOW,ZoneOffset.UTC));var auth=register(policy);var identity=identity(policy,auth);
        var fields=List.of("tenant","agent","session","1","run","input","cause","trace","span","root","","snapshot","1",
                "knowledge:docs","capability","version","descriptor","1","PT2S","0".repeat(64),"KEYWORD","request","GROUNDING","answer","question",
                "1".repeat(64),"2".repeat(64),"KEYWORD","1","3","40","500","2000",NOW.toString(),"","true","source-event","key","action","invocation","invocation-ref","attempt","fingerprint");
        String json=fields.stream().map(value->"\""+value+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));
        var resource=new TypedPayload("skillforge.runtime.knowledge.execute-resource.v1",json);
        assertEquals(10,assertInstanceOf(AuthorizationResult.Allowed.class,policy.authorize(authorization(identity,"knowledge.execute",resource))).policyRevision());
        for(String invalid:List.of(json+" ",json.substring(0,json.length()-1)+",\"extra\"]",json.replace("\"agent\"","\"other\""),json.replace("\"GROUNDING\"","\"NONE\""),json.replace("\"2000\"","\"0\"")))
            assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"knowledge.execute",new TypedPayload(resource.schemaId(),invalid))));
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"knowledge.query",resource)));
        policy.updateScopes(auth,Set.of("sessions.read"));
        assertInstanceOf(AuthorizationResult.Denied.class,policy.authorize(authorization(identity,"knowledge.execute",resource)));
    }

    @Test
    void workflowWritesAndWorkerExecutionDoNotImplyReadQueryScope() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC)); var auth = register(policy); var identity = identity(policy, auth);
        String common = "[\"tenant\",\"agent\",\"session\",\"1\",\"run\",\"input\",\"cause\",\"trace\",\"span\",\"root\",\"\",\"snapshot\",\"1\",\"workflow:mount\",\"capability\",\"version\",\"instance\",\"1\",\"PT2S\",\"execution\",\"action\",\"invocation\",\"invocation-ref\"";
        var bodies = Map.of("workflow.dispatch", common + ",\"input.v1\",\"{}\",\"" + "0".repeat(64) + "\",\"key\"]",
                "workflow.resume", common + ",\"suspend\",\"action-key\",\"input.v1\",\"{}\",\"1\",\"key\"]",
                "workflow.recover", common + ",\"blocked\"]", "workflow.cancel", common + ",\"key\",\"1\",\"cancelled\",\"idempotency\"]",
                "workflow.query", "[\"tenant\",\"agent\",\"session\",\"1\",\"execution\"]",
                "workflow.execute", "[\"tenant\",\"agent\",\"session\",\"1\",\"execution\"]");
        for (var entry : bodies.entrySet()) {
            var resource = new TypedPayload("skillforge.runtime." + entry.getKey() + "-resource.v1", entry.getValue());
            var result = policy.authorize(authorization(identity, entry.getKey(), resource));
            if (entry.getKey().equals("workflow.query")) assertInstanceOf(AuthorizationResult.Denied.class, result);
            else assertEquals(10, assertInstanceOf(AuthorizationResult.Allowed.class, result).policyRevision());
            assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, entry.getKey(),
                    new TypedPayload(resource.schemaId(), entry.getValue().replace("\"agent\"", "\"other\"")))));
        }
        policy.updateScopes(auth, Set.of("sessions.read"));
        for (var entry : bodies.entrySet()) {
            var result = policy.authorize(authorization(identity, entry.getKey(), new TypedPayload("skillforge.runtime." + entry.getKey() + "-resource.v1", entry.getValue())));
            if (entry.getKey().equals("workflow.query")) assertInstanceOf(AuthorizationResult.Allowed.class, result);
            else assertInstanceOf(AuthorizationResult.Denied.class, result);
        }
    }

    @Test
    void toolInvokeRejectsTargetShapeDriftAndRevokedCurrentAuthority() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC));
        var auth = register(policy);
        var identity = identity(policy, auth);
        String body = "[\"tenant\",\"agent\",\"session\",\"1\",\"run\",\"input\",\"cause\",\"trace\",\"span\",\"root\",\"\","
                + "\"MCP\",\"server\",\"lookup\",\"tool:mount\",\"capability\",\"version\",\"deployment\",\"3\","
                + "\"input.v1\",\"{}\",\"output.v1\",\"{}\",\"READ_ONLY\",\"false\",\"PT2S\",\"true\",\"NEVER\",\"\",\"\",\"true\",\"key\"]";
        String schema = "skillforge.runtime.tool.invoke-resource.v3";
        var resource = new TypedPayload(schema, body);
        assertInstanceOf(AuthorizationResult.Allowed.class, policy.authorize(authorization(identity, "tool.invoke", resource)));
        for (String invalid : List.of(body + " ", body.replace("\"agent\"", "\"other\""), body.replace("\"3\"", "\"03\""),
                body.replace("tool:mount", "workflow:mount"), body.replace("false", "FALSE"), body.replace("PT2S", "PT2.000S"))) {
            assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "tool.invoke", new TypedPayload(schema, invalid))));
        }
        policy.updateScopes(auth, Set.of());
        assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "tool.invoke", resource)));
    }

    @Test
    void modelActionsRequireExactOwnerCanonicalBindingRevisionAndCurrentScopes() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC));
        var auth = register(policy);
        var identity = identity(policy, auth);
        String body = "[\"tenant\",\"agent\",\"session\",\"1\",\"run\",\"input\",\"cause\",\"trace\",\"span\",\"root\",\"\",\"binding\",\"2\",\"strategy\",\"1\"]";
        for (String action : List.of("model.resolve", "model.generate", "model.stream")) {
            String schema = "model.resolve".equals(action) ? "skillforge.runtime.model.resolve-resource.v1" : "skillforge.runtime.model.dispatch-resource.v1";
            assertInstanceOf(AuthorizationResult.Allowed.class, policy.authorize(authorization(identity, action, new TypedPayload(schema, body))));
            for (String invalid : List.of(body + " ", body.replace("\"agent\"", "\"other\""), body.replace("\"2\"", "\"02\""), body.replace("\"cause\"", "\"\""))) {
                assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, action, new TypedPayload(schema, invalid))));
            }
        }
        policy.updateScopes(auth, Set.of());
        assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "model.resolve",
                new TypedPayload("skillforge.runtime.model.resolve-resource.v1", body))));
    }

    @Test
    void callbackActionsRequireCanonicalFrozenShapeAndCurrentScopes() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC));
        var auth = register(policy);
        var identity = identity(policy, auth);
        String body = "[\"tenant\",\"agent\",\"session\",\"1\",\"callback\",\"source\","
                + "\"skillforge.runtime.callback.session-event.v1\",\"hmac-sha256-callback.v1\","
                + "\"service-principal-v1.c2VydmljZQ.7\",\"session\",\"\",\"\",\"\",\"\",\"cause\"]";
        var resource = new TypedPayload("skillforge.runtime.callback.register-resource.v1", body);
        assertInstanceOf(AuthorizationResult.Allowed.class, policy.authorize(authorization(identity, "callback.register", resource)));
        var status = new TypedPayload("skillforge.runtime.callback.status-resource.v1",
                body.substring(0, body.length() - 1) + ",\"1\",\"SUSPENDED\"]");
        assertInstanceOf(AuthorizationResult.Allowed.class, policy.authorize(authorization(identity, "callback.status", status)));
        String actionBody = "[\"tenant\",\"agent\",\"session\",\"1\",\"callback\",\"source\","
                + "\"skillforge.runtime.callback.action-result.v1\",\"hmac-sha256-callback.v1\","
                + "\"service-principal-v1.c2VydmljZQ.7\",\"action\",\"run\",\"action-id\","
                + "\"invocation-id\",\"3\",\"cause\"]";
        var actionResource = new TypedPayload(resource.schemaId(), actionBody);
        assertInstanceOf(AuthorizationResult.Allowed.class,
                policy.authorize(authorization(identity, "callback.register", actionResource)));
        for (String malformed : List.of(actionBody.replace("\"run\"", "\"\""),
                actionBody.replace("\"action-id\"", "\"\""),
                actionBody.replace("\"invocation-id\"", "\"\""),
                actionBody.replace("\"3\"", "\"03\""),
                actionBody.replace("action-result", "execution-result"))) {
            assertInstanceOf(AuthorizationResult.Denied.class,
                    policy.authorize(authorization(identity, "callback.register",
                            new TypedPayload(resource.schemaId(), malformed))));
        }
        for (String malformed : List.of(body + " ", body.replace("\"session\",\"\",\"\"", "\"session\",\"extra\",\"\""),
                body.replace("c2VydmljZQ.7", "c2VydmljZQ.07"), body.replace("hmac-sha256-callback.v1", "unknown"))) {
            assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "callback.register",
                    new TypedPayload(resource.schemaId(), malformed))));
        }
        policy.updateScopes(auth, Set.of());
        assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "callback.register", resource)));
    }

    @Test
    void randomTrustedReferenceVerifiesOnlyExactOwnerChannelAndSourceAndAuditsRejection() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC));
        var authentication = register(policy);
        assertNotEquals(authentication, register(policy));
        assertEquals("development", policy.supportedProfile());
        var verified = identity(policy, authentication);
        assertEquals(OWNER, verified.owner()); assertEquals(PRINCIPAL, verified.principal());
        assertTrue(policy.audit(verified.verificationRef()).isPresent());
        var otherOwners = List.of(
                SessionOwner.user(new SessionRef(new TenantId("other"), "session"), OWNER.target().requireAgent(), OWNER.requireUser()),
                SessionOwner.user(new SessionRef(TENANT, "other-session"), OWNER.target().requireAgent(), OWNER.requireUser()),
                SessionOwner.user(OWNER.sessionRef(), new AgentId("other-agent"), OWNER.requireUser()),
                SessionOwner.user(OWNER.sessionRef(), OWNER.target().requireAgent(), new EndUserId(2)));
        for (var owner : otherOwners) {
            var rejected = assertInstanceOf(IdentityResult.Rejected.class, policy.verify(request(authentication, owner, "web", "user")));
            assertTrue(policy.audit(rejected.failure().resultRef()).isPresent());
        }
        assertInstanceOf(IdentityResult.Rejected.class, policy.verify(request(authentication, OWNER, "api", "user")));
        assertInstanceOf(IdentityResult.Rejected.class, policy.verify(request(authentication, OWNER, "web", "callback")));
        var unknown = assertInstanceOf(IdentityResult.Rejected.class, policy.verify(request(new AuthenticationRef("unknown"), OWNER, "web", "user")));
        assertFalse(policy.audit(unknown.failure().resultRef()).orElseThrow().toString().contains(authentication.value()));
    }

    @Test
    void facadeAccessReturnsAuthenticatedOwnerRejectsClaimDriftAndUsesReadWriteScopes() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC));
        var authentication = register(policy);
        var request = access(authentication, TENANT, OWNER.target().requireAgent(), OWNER.requireUser(), "web", "user");
        var identity = assertInstanceOf(IdentityResult.Verified.class,
                policy.verifyAccess(request)).identity();
        assertEquals(OWNER, identity.owner());
        for (var drift : List.of(
                access(authentication, new TenantId("other"), OWNER.target().requireAgent(), OWNER.requireUser(), "web", "user"),
                access(authentication, TENANT, new AgentId("other"), OWNER.requireUser(), "web", "user"),
                access(authentication, TENANT, OWNER.target().requireAgent(), new EndUserId(2), "web", "user"),
                access(authentication, TENANT, OWNER.target().requireAgent(), OWNER.requireUser(), "api", "user"),
                access(authentication, TENANT, OWNER.target().requireAgent(), OWNER.requireUser(), "web", "callback"))) {
            assertInstanceOf(IdentityResult.Rejected.class, policy.verifyAccess(drift));
        }

        var query = facadeResource("runtime.run.query", "run", "run", "0", "request");
        var cancel = facadeResource("runtime.cancel", "run", "run", "2", "cancel");
        policy.updateScopes(authentication, Set.of("sessions.read"));
        assertInstanceOf(AuthorizationResult.Allowed.class,
                policy.authorize(authorization(identity, "runtime.run.query", query)));
        assertInstanceOf(AuthorizationResult.Denied.class,
                policy.authorize(authorization(identity, "runtime.cancel", cancel)));
        assertInstanceOf(AuthorizationResult.Denied.class,
                policy.authorize(authorization(identity, "runtime.run.query",
                        new TypedPayload(query.schemaId(), query.canonicalJson() + " "))));

        policy.updateScopes(authentication, Set.of("sessions.write"));
        assertInstanceOf(AuthorizationResult.Allowed.class,
                policy.authorize(authorization(identity, "runtime.cancel", cancel)));
        assertInstanceOf(AuthorizationResult.Denied.class,
                policy.authorize(authorization(identity, "runtime.run.query", query)));
    }

    @Test
    void authorizationRequiresOriginalWholeVerifiedIdentityAndCurrentScopes() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC));
        var authentication = register(policy); var identity = identity(policy, authentication);
        var allowed = assertInstanceOf(AuthorizationResult.Allowed.class, policy.authorize(authorization(identity, "runtime.accept", resource())));
        assertTrue(policy.audit(allowed.auditRef()).isPresent());
        for (var forged : List.of(
                new VerifiedIdentity(identity.principal(), identity.owner(), Set.of("sessions.write", "admin"), identity.policyRevision(), identity.verificationRef()),
                new VerifiedIdentity(identity.principal(), identity.owner(), identity.scopes(), identity.policyRevision() + 1, identity.verificationRef()),
                new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT, "forged"), identity.owner(), identity.scopes(), identity.policyRevision(), identity.verificationRef()),
                new VerifiedIdentity(identity.principal(), identity.owner(), identity.scopes(), identity.policyRevision(), new ResultRef("invented")))) {
            assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(forged, "runtime.accept", resource())));
        }
        policy.updateScopes(authentication, Set.of());
        var denied = assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "runtime.accept", resource())));
        assertEquals(allowed.policyRevision(), denied.policyRevision());
        assertEquals(Set.of("sessions.write"), identity.scopes());
        policy.updateScopes(authentication, Set.of("sessions.write"));
        assertInstanceOf(AuthorizationResult.Allowed.class, policy.authorize(authorization(identity, "runtime.accept", resource())));
        policy.revoke(authentication);
        assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "runtime.accept", resource())));
        assertInstanceOf(IdentityResult.Rejected.class, policy.verify(request(authentication, OWNER, "web", "user")));
    }

    @Test
    void runtimeAcceptRejectsUnknownActionSchemaMalformedResourceAndEveryOwnerFieldDrift() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC));
        var identity = identity(policy, register(policy));
        assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "runtime.delete", resource())));
        assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "runtime.accept", new TypedPayload("unknown", resource().canonicalJson()))));
        var original = resource().canonicalJson();
        for (var bad : List.of(original.replace("\"tenant\"", "\"other\""), original.replace("\"agent\"", "\"other\""),
                original.replace("\"session\"", "\"other\""), original.replace("\"1\"", "\"2\""),
                original.replace("\"web\"", "\"api\""), original.replace("\"user\"", "\"callback\""),
                original.replace("[\"new\"]", "[\"existing\",\"other\",\"run\"]"),
                original.replace("MESSAGE", "UNKNOWN"), original.replace("2026-09-10T00:00:00Z", "invalid"),
                original + "[]", original.substring(0, original.length() - 1) + ",\"extra\"]", "[]", "{}")) {
            var denied = assertInstanceOf(AuthorizationResult.Denied.class,
                    policy.authorize(authorization(identity, "runtime.accept", new TypedPayload(SCHEMA, bad))));
            assertEquals("denied", policy.audit(denied.auditRef()).orElseThrow().outcome());
        }
    }

    @Test
    void rejectsNoncanonicalEscapesAndEquivalentTimestampSpellings() {
        var policy = new InMemoryIdentityPolicyPort(Clock.fixed(NOW, ZoneOffset.UTC));
        var identity = identity(policy, register(policy));
        var original = resource().canonicalJson();
        for (String bad : List.of(original.replace("tenant", "\\u0074enant"),
                original.replace("{}", "\\/"), original.replace("{}", "\\u000a"),
                original.replace("{}", "\\u001F"),
                original.replace("2026-09-10T00:00:00Z", "2026-09-10T00:00:00+00:00"))) {
            assertInstanceOf(AuthorizationResult.Denied.class,
                    policy.authorize(authorization(identity, "runtime.accept", new TypedPayload(SCHEMA, bad))));
        }
        // 普通斜杠与 writer 的控制字符表示仍被接纳；原业务 payload 字符串不在身份边界重新解释。
        for (String valid : List.of(original.replace("{}", "x/y"), original.replace("{}", "x\\u0001"),
                original.replace("{}", "x\\n"))) {
            assertInstanceOf(AuthorizationResult.Allowed.class,
                    policy.authorize(authorization(identity, "runtime.accept", new TypedPayload(SCHEMA, valid))));
        }
    }

    @Test
    void expirationRejectsBothFreshVerificationAndPreviouslyVerifiedAuthorization() {
        var clock = new MutableClock(); var policy = new InMemoryIdentityPolicyPort(clock);
        var authentication = register(policy); var identity = identity(policy, authentication);
        clock.now = NOW.plusSeconds(60);
        assertInstanceOf(IdentityResult.Rejected.class, policy.verify(request(authentication, OWNER, "web", "user")));
        assertInstanceOf(AuthorizationResult.Denied.class, policy.authorize(authorization(identity, "runtime.accept", resource())));
        assertThrows(IllegalArgumentException.class, () -> register(policy));
    }

    private static AuthenticationRef register(InMemoryIdentityPolicyPort policy) {
        return policy.registerTrusted(new InMemoryIdentityPolicyPort.TrustedRegistration(PRINCIPAL, OWNER, "web", "user", NOW.plusSeconds(60), Set.of("sessions.write")));
    }
    private static IdentityRequest request(AuthenticationRef ref, SessionOwner owner, String channel, String source) {
        return new IdentityRequest(ref, owner, channel, source, new RequestId("request"), Optional.of(new InputId("input")), "trace", "span");
    }
    private static AccessIdentityRequest access(AuthenticationRef ref, TenantId tenantId,
            AgentId agentId, EndUserId endUserId, String channel, String source) {
        return new AccessIdentityRequest(ref, tenantId, com.skillforge.runtime.api.SessionTarget.agent(agentId), java.util.Optional.of(agentId), com.skillforge.runtime.api.SessionSubject.user(endUserId), channel, source, new RequestId("request"), "trace", "span");
    }
    private static VerifiedIdentity identity(InMemoryIdentityPolicyPort policy, AuthenticationRef ref) {
        return assertInstanceOf(IdentityResult.Verified.class, policy.verify(request(ref, OWNER, "web", "user"))).identity();
    }
    private static AuthorizationRequest authorization(VerifiedIdentity identity, String action, TypedPayload resource) {
        return new AuthorizationRequest(identity, action, resource, "trace", "span");
    }
    private static TypedPayload resource() {
        return new TypedPayload(SCHEMA, "[\"tenant\",\"agent\",\"session\",\"1\",\"input\",\"event\",\"MESSAGE\",[\"new\"],\"web\",\"user\",\"trace\",\"span\",\"input\",[],\"payload.v1\",\"{}\",\"2026-09-10T00:00:00Z\"]");
    }
    private static TypedPayload facadeResource(String action, String targetKind,
            String targetId, String cursor, String operation) {
        return new TypedPayload("skillforge.runtime.facade-access.v1",
                "[\"tenant\",\"agent\",\"session\",\"1\",\"" + action
                        + "\",\"" + targetKind + "\",\"" + targetId + "\",\""
                        + cursor + "\",\"" + operation + "\"]");
    }
    private static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException(); return this; }
        @Override public Instant instant() { return now; }
    }
}
