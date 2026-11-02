package com.skillforge.core.foundation.integration.runtime.identity;

import com.skillforge.runtime.adapter.jdbc.identity.ModelPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.ToolPolicyResource;


import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationRequest;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationResult;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** steer采纳后的执行仍核原Run及当前群治理资格，不要求私人会话scope。 */
class GroupExecutionAuthorizationTest {
    private static final TenantId TENANT = new TenantId("41");
    private static final GroupBindingRef GROUP = new GroupBindingRef(TENANT, "702");
    private static final SessionOwner OWNER = new SessionOwner(new SessionRef(TENANT, "shared"),
            SessionSubject.group(GROUP), SessionTarget.group(GROUP));
    private static final PrincipalRef.ServicePrincipal SERVICE =
            new PrincipalRef.ServicePrincipal(TENANT, "service", "integration", 7);
    private static final String SCOPE = "groups.runs.control";
    private static final VerifiedIdentity ACTOR = new VerifiedIdentity(SERVICE, OWNER, Set.of(SCOPE),
            JdbcIdentityPolicyPort.POLICY_REVISION, new ResultRef("verified"));

    @Test void steerActorCanContinueModelAndToolWithItsExactCurrentGroupQualification() {
        var fixture = fixture();
        resources(run("run")).forEach((action, resource) -> assertInstanceOf(AuthorizationResult.Allowed.class,
                fixture.port.authorize(fixture.session, request(action, resource))));
        verify(fixture.mapper, times(2)).groupControllerAvailable(fixture.fact, "run");
        verify(fixture.mapper, times(2)).boundRunActorAvailable(fixture.fact, "run", "80", true);
        verifyNoInteractions(fixture.source);
    }

    @Test void anotherRunOperatorOrRevokedScopeCannotContinueThroughTheSameResourceKinds() {
        var fixture = fixture();
        resources(run("another-run")).forEach((action, resource) -> assertInstanceOf(AuthorizationResult.Denied.class,
                fixture.port.authorize(fixture.session, request(action, resource))));
        fixture.fact.source = "group-control:10";
        when(fixture.mapper.groupControllerAvailable(fixture.fact, "run")).thenReturn(false);
        resources(run("run")).forEach((action, resource) -> assertInstanceOf(AuthorizationResult.Denied.class,
                fixture.port.authorize(fixture.session, request(action, resource))));
        fixture.fact.source = "group-control:9";
        when(fixture.mapper.groupControllerAvailable(fixture.fact, "run")).thenReturn(true);
        fixture.fact.currentServiceScopes = "";
        resources(run("run")).forEach((action, resource) -> assertInstanceOf(AuthorizationResult.Denied.class,
                fixture.port.authorize(fixture.session, request(action, resource))));
        verifyNoInteractions(fixture.source);
    }

    @Test void acceptedGroupResponseReadDoesNotGrantSlotForNewExecution() {
        var fixture=fixture();var input=new InputId("accepted-response");
        var request=request("session.execute",SessionPolicyResource.run(run("run"),Optional.empty()));
        when(fixture.mapper.boundRunActorAvailable(fixture.fact,"run","80",true)).thenReturn(false);
        when(fixture.mapper.boundRunActorAvailable(fixture.fact,"run","80",false)).thenReturn(true);
        when(fixture.mapper.acceptedResponseExists("41","run",input.value())).thenReturn(true);
        assertInstanceOf(AuthorizationResult.Allowed.class,fixture.port.authorizeResponseReplay(fixture.session,request,input));
        assertInstanceOf(AuthorizationResult.Denied.class,fixture.port.authorize(fixture.session,request));
        resources(run("run")).forEach((action,resource)->assertInstanceOf(AuthorizationResult.Denied.class,
                fixture.port.authorize(fixture.session,request(action,resource))));
        assertInstanceOf(AuthorizationResult.Denied.class,fixture.port.authorizeResponseReplay(fixture.session,request,new InputId("new-response")));
        when(fixture.mapper.groupControllerAvailable(fixture.fact,"run")).thenReturn(false);
        assertInstanceOf(AuthorizationResult.Denied.class,fixture.port.authorizeResponseReplay(fixture.session,request,input));
        when(fixture.mapper.groupControllerAvailable(fixture.fact,"run")).thenReturn(true);
        fixture.fact.currentServiceScopes="";
        assertInstanceOf(AuthorizationResult.Denied.class,fixture.port.authorizeResponseReplay(fixture.session,request,input));
        verifyNoInteractions(fixture.source);
    }

    private static Fixture fixture() {
        var source = mock(DataSource.class);
        var session = mock(SqlSession.class);
        var mapper = mock(JdbcIdentityPolicyPort.Mapper.class);
        when(session.getMapper(JdbcIdentityPolicyPort.Mapper.class)).thenReturn(mapper);
        var fact = JdbcIdentityPolicyPort.Fact.from(SERVICE, OWNER, "open-platform", "group-control:9");
        fact.identitySnapshot = String.join("|", "identity-v2", "service:" + url("service") + ":" + url("integration") + ":7",
                url("41"), "GROUP", url("702"), url("shared"), "group:" + url("702"), url(SCOPE),
                Long.toString(ACTOR.policyRevision()), url("verified"));
        fact.currentTime = Instant.parse("2026-10-22T10:00:00Z");
        fact.expiresAt = fact.currentTime.plusSeconds(60);
        fact.currentServiceStatus = "active";
        fact.currentServiceIntegrationId = "integration";
        fact.currentServiceCredentialVersion = 7L;
        fact.currentServiceScopes = Base64.getEncoder().encodeToString(SCOPE.getBytes(StandardCharsets.UTF_8));
        fact.ownerAvailable = true;
        fact.sessionScopeAllowed = true;
        when(mapper.authorizationCurrent("41", "verified", "GROUP")).thenReturn(fact);
        when(mapper.boundRunActorAvailable(fact, "run", "80", true)).thenReturn(true);
        when(mapper.groupControllerAvailable(fact, "run")).thenReturn(true);
        when(mapper.audit(anyString(), nullable(String.class), eq("authorize"), anyString(),
                eq(JdbcIdentityPolicyPort.POLICY_REVISION), anyString(), anyString())).thenReturn(1);
        return new Fixture(new JdbcIdentityPolicyPort(source, Map.of()), session, mapper, fact, source);
    }

    private static RunIdentity run(String id) {
        var input = new InputId("group-trigger:trigger");
        return new RunIdentity(OWNER, new AgentId("80"), input, new RunRef(TENANT, id),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("original-trigger"),
                new RunRequester.EndUser(new EndUserId(9)), Optional.of(new SourceMessageRef(OWNER.sessionRef(), "source")),
                new RunOriginRef(TENANT, "group-trigger:trigger"));
    }

    private static Map<String, TypedPayload> resources(RunIdentity run) {
        var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.TEST_MOCK, "provider", "tool");
        var target = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:mount"),
                new CapabilityRef(CapabilityType.TOOL, "capability", "version"), "deployment", 3,
                new TypedPayload("input.v1", "{}"), new TypedPayload("output.v1", "{}"),
                ToolEffect.READ_ONLY, false, Duration.ofSeconds(2), true,
                ToolPortal.ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
        return Map.of("model.generate", ModelPolicyResource.dispatch(run, new ModelBindingRef(TENANT, "model", 1),
                        new RequestedModelStrategy("default", "1")),
                "tool.invoke", ToolPolicyResource.invocation(run, binding, target, new IdempotencyKey("key")));
    }

    private static AuthorizationRequest request(String action, TypedPayload resource) {
        return new AuthorizationRequest(ACTOR, action, resource, "trace", "span");
    }
    private static String url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private record Fixture(JdbcIdentityPolicyPort port, SqlSession session, JdbcIdentityPolicyPort.Mapper mapper,
            JdbcIdentityPolicyPort.Fact fact, DataSource source) { }
}
