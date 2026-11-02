package com.skillforge.core.foundation.integration.runtime.identity;

import com.skillforge.runtime.adapter.jdbc.identity.AdmissionResource;
import com.skillforge.runtime.adapter.jdbc.identity.ModelPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.SessionCapabilityPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.SessionPolicyResource;
import com.skillforge.runtime.adapter.jdbc.identity.ToolPolicyResource;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.core.foundation.integration.runtime.session.JdbcCallbackPolicy;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackSigningKeys;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InboundEnvelope;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.RunTarget;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.IdempotencyKey;
import java.time.Instant;
import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RequestId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationRequest;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AuthorizationResult;
import com.skillforge.runtime.api.port.IdentityPolicyPort.AccessIdentityRequest;
import com.skillforge.runtime.api.port.IdentityPolicyPort.IdentityRequest;
import com.skillforge.runtime.api.port.IdentityPolicyPort.IdentityResult;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 原 IAM、受信引用和独立安全审计在真实 PostgreSQL 下的身份授权闭环。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcIdentityPolicyPortTest {
    private static final String ROLE = "runtime_identity_test";
    private static final String PASSWORD = "runtime_identity_test_password";
    private static final TenantId TENANT = new TenantId("1");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("10"), new EndUserId(20));
    private static final PrincipalRef.ServicePrincipal SERVICE = new PrincipalRef.ServicePrincipal(TENANT, "service", "integration", 7);
    private static final PrincipalRef.SystemPrincipal SYSTEM = new PrincipalRef.SystemPrincipal(TENANT, "scheduler");
    private static final List<String> TABLES = List.of("nw_runtime_authentication_facts",
            "nw_runtime_identity_verifications", "nw_runtime_security_audits");
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("identity_admin").withPassword("identity_admin_password");
    private JdbcIdentityPolicyPort port;

    @Test void capabilityFreezeHasDedicatedOwnerInputResourceAndCurrentWriteScope() throws Exception {
        var auth=register(OWNER);var identity=verified(port.verify(request(auth,OWNER,"channel","source")));
        var resource=SessionCapabilityPolicyResource.resource(OWNER,new InputId("before-admission"));
        assertEquals(11,assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(new AuthorizationRequest(identity,SessionCapabilityPolicyResource.ACTION,resource,"trace","span"))).policyRevision());
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(identity,"session.claim",resource,"trace","span")));
        var foreign=SessionCapabilityPolicyResource.resource(SessionOwner.user(OWNER.sessionRef(),OWNER.target().requireAgent(),new EndUserId(21)),new InputId("before-admission"));
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(identity,SessionCapabilityPolicyResource.ACTION,foreign,"trace","span")));
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=1");
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(identity,SessionCapabilityPolicyResource.ACTION,resource,"trace","span")));
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.write\"]'::jsonb WHERE id=1");
        port.revoke(auth);
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(identity,SessionCapabilityPolicyResource.ACTION,resource,"trace","span")));
    }

    @Test void sessionResourcesAndOriginalVerificationSeparateHistoryFromCurrentAuthority() {
        var authentication=register(OWNER);var original=verified(port.verify(request(authentication,OWNER,"channel","source")));
        var run=new com.skillforge.runtime.api.port.RunIdentity(OWNER,OWNER.target().requireAgent(),new InputId("origin-input"),new com.skillforge.runtime.api.RunRef(TENANT,"origin-run"),
                new TraceEnvelope("trace","span",new InputId("root"),Optional.empty()),original.verificationRef(),
                new RunRequester.EndUser(OWNER.requireUser()),Optional.empty(),new RunOriginRef(TENANT,"execution-input:origin-run"));
        assertEquals(original,newPort().originalVerification(OWNER,original.verificationRef()).orElseThrow());
        assertTrue(port.originalVerification(SessionOwner.user(OWNER.sessionRef(),OWNER.target().requireAgent(),new EndUserId(21)),original.verificationRef()).isEmpty());
        for(var action:List.of("session.execute","session.commit","session.cancel")) {
            var resource=SessionPolicyResource.run(run,Optional.of(new InputId("cancel")));
            assertEquals(11,assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(new AuthorizationRequest(original,action,resource,"trace","span"))).policyRevision());
            assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(original,action,new TypedPayload(resource.schemaId(),resource.canonicalJson()+" "),"trace","span")));
        }
        assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(new AuthorizationRequest(original,"session.claim",SessionPolicyResource.claim(OWNER),"trace","span")));
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(original,"session.commit",SessionPolicyResource.claim(OWNER),"trace","span")));
        port.revoke(authentication);
        assertEquals(original,port.originalVerification(OWNER,original.verificationRef()).orElseThrow());
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(original,"session.commit",SessionPolicyResource.run(run,Optional.empty()),"trace","span")));
    }

    @Test
    void toolInvokeRequiresExplicitResourceCurrentFullVerificationAndIamScope() {
        var auth = register(OWNER);
        var identity = verified(port.verify(request(auth, OWNER, "channel", "source")));
        var run = runIdentity(identity, OWNER.target().requireAgent().value(), "run");
        var resource = toolResource(run);
        String body = resource.canonicalJson();
        assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(new AuthorizationRequest(identity, "tool.invoke", resource, "trace", "span")));
        for (String invalid : List.of(body + " ", body.replace("\"10\"", "\"11\""), body.replace("\"3\"", "\"03\""))) {
            assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity, "tool.invoke",
                    new TypedPayload(resource.schemaId(), invalid), "trace", "span")));
        }
        var forged = new VerifiedIdentity(identity.principal(), identity.owner(), Set.of("sessions.write", "admin"), identity.policyRevision(), identity.verificationRef());
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(forged, "tool.invoke", resource, "trace", "span")));
        execute("UPDATE nw_service_principals SET scopes='[]'::jsonb WHERE principal_id='service'");
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity, "tool.invoke", resource, "trace", "span")));
    }

    @BeforeAll
    static void initializeDatabase() throws Exception {
        // 仅复现生产查询依赖的原 IAM/归属表结构，身份事实和安全审计完整执行真实 V102。
        execute("CREATE TABLE nw_service_principals (id bigint PRIMARY KEY,tenant_id bigint NOT NULL,principal_id text UNIQUE NOT NULL,"
                + "integration_id text NOT NULL,credential_version bigint NOT NULL,status text NOT NULL,scopes jsonb NOT NULL)");
        execute("CREATE TABLE nw_agents (id bigint PRIMARY KEY,tenant_id bigint NOT NULL,status text NOT NULL DEFAULT 'published')");
        execute("CREATE TABLE nw_agent_teams (id text PRIMARY KEY,tenant_id bigint NOT NULL,status text NOT NULL,published_revision bigint)");
        execute("CREATE TABLE nw_agent_team_versions (team_id text NOT NULL,tenant_id bigint NOT NULL,revision bigint NOT NULL,definition jsonb NOT NULL)");
        execute("CREATE TABLE nw_end_users (id bigint PRIMARY KEY,tenant_id bigint NOT NULL,status text NOT NULL)");
        execute("CREATE TABLE agent_runtime_sessions (tenant_id text NOT NULL,target_kind text NOT NULL,target_id text NOT NULL,session_id text NOT NULL,"
                + "end_user_id bigint NOT NULL,scope_key text NOT NULL,PRIMARY KEY(tenant_id,target_kind,target_id,session_id))");
        execute("CREATE TABLE agent_runtime_session_owners (tenant_id text NOT NULL,target_kind text NOT NULL,target_id text NOT NULL,session_id text NOT NULL,"
                + "end_user_id bigint NOT NULL,PRIMARY KEY(tenant_id,target_kind,target_id,session_id))");
        installDirectQueryDependencies();
        execute("CREATE TABLE identity_test_business_probe (id bigint PRIMARY KEY)");
        execute("CREATE TABLE agent_runtime_execution_waits (tenant_id text NOT NULL,run_id text NOT NULL,input_id text NOT NULL)");
        for (String table : List.of("nw_service_principals", "nw_agents", "nw_agent_teams", "nw_agent_team_versions", "nw_end_users", "agent_runtime_sessions", "agent_runtime_session_owners",
                "nw_chat_sessions", "nw_agent_deployments", "nw_runtime_agent_run_bindings", "nw_integration_run_sources", "agent_runtime_execution_waits")) {
            execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            execute("CREATE POLICY tenant_isolation ON " + table
                    + " USING (tenant_id::text = COALESCE(current_setting('app.tenant_id',true),''))"
                    + " WITH CHECK (tenant_id::text = COALESCE(current_setting('app.tenant_id',true),''))");
        }
        execute("CREATE ROLE skillforge NOSUPERUSER NOBYPASSRLS");
        try (var resource = JdbcIdentityPolicyPortTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V102__agent_runtime_identity_policy.sql")) {
            assertNotNull(resource);
            execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        // 与 V219 的身份事实形状保持一致；本夹具不加载无关业务表迁移。
        try (var connection = admin()) {
            com.skillforge.core.foundation.integration.runtime.CurrentHostAuthenticationSchema.installAuthentication(connection);
        }
        execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        execute("GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO " + ROLE);
        execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + ROLE);
        execute("GRANT EXECUTE ON FUNCTION resolve_runtime_authentication_tenant(text) TO " + ROLE);
    }

    @BeforeEach
    void resetFacts() {
        execute("TRUNCATE " + String.join(",", TABLES)
                + ",nw_service_principals,nw_agents,nw_end_users,nw_agent_teams,nw_agent_team_versions,agent_runtime_sessions,agent_runtime_session_owners,identity_test_business_probe,nw_runtime_host_owners"
                + ",nw_tenants,nw_institution_integrations,nw_integration_business_scopes,nw_agent_templates,nw_agent_definition_grants,nw_agent_definition_grant_revisions,nw_agent_deployments,nw_institution_relation_revisions"
                + ",nw_chat_sessions,nw_runtime_agent_run_bindings,nw_integration_run_sources,agent_runtime_execution_waits");
        execute("INSERT INTO nw_tenants(id,status) VALUES(1,'active'),(2,'active')");
        execute("INSERT INTO nw_service_principals VALUES (1,1,'service','integration',7,'active','[\"sessions.write\"]'::jsonb)");
        execute("INSERT INTO nw_agents VALUES (10,1),(11,2)");
        execute("INSERT INTO nw_end_users VALUES (20,1,'active'),(21,2,'active')");
        execute("INSERT INTO agent_runtime_sessions VALUES ('1','AGENT','10','session',20,'open-platform:integration')");
        execute("INSERT INTO agent_runtime_session_owners VALUES ('1','AGENT','10','session',20)");
        port = newPort();
    }

    @Test
    void independentAgentDirectSessionRechecksDirectAssociationAndBusinessScope() {
        directSource(OWNER);
        frozenRun(OWNER, "10", "direct-run", null, OWNER.sessionRef().sessionId());
        var identity=verified(port.verify(request(register(OWNER),OWNER,"channel","source")));
        var execution=execution(identity,"10","direct-run");
        assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(execution));
        for(String suspension:List.of(
                "UPDATE nw_tenants SET status='suspended' WHERE id=1",
                "DELETE FROM nw_institution_integrations WHERE tenant_id=1",
                "UPDATE nw_integration_business_scopes SET status='revoked' WHERE scope_id='902'")) {
            execute(suspension);
            assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(execution),suspension);
            execute("UPDATE nw_tenants SET status='active'");
            execute("INSERT INTO nw_institution_integrations VALUES(1,'integration') ON CONFLICT DO NOTHING");
            execute("UPDATE nw_integration_business_scopes SET status='active'");
            assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(execution));
        }
    }

    @Test
    void warningInstitutionRetainsDirectSourceAndDeployedActorExecutionAuthority() {
        directSource(OWNER);
        deployDirectAgent(10,100);
        frozenRun(OWNER,"10","warning-run",null,OWNER.sessionRef().sessionId());
        var identity=verified(port.verify(request(register(OWNER),OWNER,"channel","source")));
        var execution=execution(identity,"10","warning-run");
        assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(execution));
        execute("UPDATE nw_tenants SET status='warning' WHERE id=1");
        assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(execution));
        assertInstanceOf(IdentityResult.Verified.class,newPort().verify(request(register(OWNER),OWNER,"channel","source")));
        execute("UPDATE nw_tenants SET status='suspended' WHERE id=1");
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(execution));
    }

    @Test
    void frozenTeamRunChecksItsActorAndIgnoresRevokedUnrelatedMembersFromNewPublication() {
        var team=SessionOwner.user(new SessionRef(TENANT,"frozen-team-session"),
                new com.skillforge.runtime.api.SessionTarget(com.skillforge.runtime.api.SessionTarget.Kind.TEAM,"frozen-team"),new EndUserId(20));
        execute("INSERT INTO nw_agents VALUES(12,1,'published')");
        execute("INSERT INTO nw_agent_teams VALUES('frozen-team',1,'published',1)");
        execute("INSERT INTO nw_agent_team_versions VALUES('frozen-team',1,1,'{\"team\":{\"members\":[{\"agent_id\":\"10\"}]}}')");
        directSource(team);
        deployDirectAgent(10,100);
        deployDirectAgent(12,120);
        frozenRun(team,"10","team-v1-run",null,team.sessionRef().sessionId());
        var identity=verified(port.verify(request(register(team),team,"channel","source")));
        var execution=execution(identity,"10","team-v1-run");
        assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(execution));
        execute("INSERT INTO nw_agent_team_versions VALUES('frozen-team',1,2,'{\"team\":{\"members\":[{\"agent_id\":\"12\"}]}}')");
        execute("UPDATE nw_agent_teams SET published_revision=2 WHERE id='frozen-team'");
        execute("UPDATE nw_agent_definition_grants SET status='revoked' WHERE definition_id=120");
        assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(execution),
                "a frozen v1 actor retains authority independently of the v2 member set");
        execute("UPDATE nw_agent_definition_grants SET status='active' WHERE definition_id=120");
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(execution(identity,"12","team-v1-run")),
                "a currently authorized member cannot replace the persisted Run actor");
        execute("UPDATE nw_agent_definition_grants SET status='revoked' WHERE definition_id=100");
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(execution),
                "the actual frozen actor still requires its current direct grant");
    }

    /** 当前实例换授只作用于新受理；派发沿原Run冻结链，不自动借用另一条有效授权。 */
    @Test void deploymentSwitchDoesNotReplaceFrozenRunAuthority() {
        directSource(OWNER);deployDirectAgent(10,100);
        frozenRun(OWNER,"10","original-chain-run",null,OWNER.sessionRef().sessionId());
        var authentication=register(OWNER);
        var identity=verified(port.verify(request(authentication,OWNER,"channel","source")));
        var execution=execution(identity,"10","original-chain-run");
        execute("INSERT INTO nw_agent_definition_grants(grant_id,definition_id,target_tenant_id,status,revision) VALUES(101,100,1,'active',1)");
        execute("INSERT INTO nw_agent_definition_grant_revisions SELECT * FROM nw_agent_definition_grants WHERE grant_id=101");
        execute("UPDATE nw_agent_deployments SET grant_id=101,grant_chain='"+chain(100,101)+"'::jsonb WHERE deployment_id=10");
        execute("UPDATE nw_agent_definition_grants SET status='revoked',revision=2 WHERE grant_id=101");
        assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(execution),"original valid chain survives a replacement-chain revocation");
        assertInstanceOf(IdentityResult.Verified.class,newPort().verifyAccess(access(authentication,TENANT,new AgentId("10"),new EndUserId(20),"channel","source")));
        assertInstanceOf(IdentityResult.Rejected.class,newPort().verifyAccess(access(authentication,new TenantId("2"),new AgentId("11"),new EndUserId(21),"channel","source")));
        assertInstanceOf(AuthorizationResult.Denied.class,newPort().authorize(authorization(identity)),"new admission still requires the selected current deployment chain");
        execute("UPDATE nw_agent_definition_grants SET status='active',revision=1 WHERE grant_id=101");
        execute("UPDATE nw_agent_definition_grants SET status='revoked',revision=2 WHERE grant_id=100");
        assertInstanceOf(AuthorizationResult.Denied.class,newPort().authorize(execution),"valid current deployment cannot restore the original revoked run");
        frozenRun(OWNER,"10","new-chain-run",null,OWNER.sessionRef().sessionId());
        assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(execution(identity,"10","new-chain-run")));
        assertEquals(100,scalar("SELECT (scope_snapshot->'authorizations'->'10'->'grant_chain'->'hops'->0->>'grant_id')::bigint FROM nw_runtime_agent_run_bindings WHERE run_id='original-chain-run'"));
    }

    @Test void responseReplayRequiresAcceptedInputAndCurrentPrincipalActorAndFrozenGrant() {
        directSource(OWNER);deployDirectAgent(10,100);
        frozenRun(OWNER,"10","response-replay-run",null,OWNER.sessionRef().sessionId());
        var identity=verified(port.verify(request(register(OWNER),OWNER,"channel","source")));
        var request=execution(identity,"10","response-replay-run");
        var input=new InputId("accepted-response");
        var factory=joinedSessions(dataSource());
        try(var session=factory.openSession(false)) {
            session.getMapper(JdbcIdentityPolicyPort.Mapper.class).bindTenant(TENANT.value());
            assertInstanceOf(AuthorizationResult.Denied.class,port.authorizeResponseReplay(session,request,input));
            execute("INSERT INTO agent_runtime_execution_waits VALUES('1','response-replay-run','accepted-response')");
            assertInstanceOf(AuthorizationResult.Allowed.class,port.authorizeResponseReplay(session,request,input));
            assertInstanceOf(AuthorizationResult.Denied.class,port.authorizeResponseReplay(session,request,new InputId("different-response")));
            execute("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=1");
            assertInstanceOf(AuthorizationResult.Denied.class,port.authorizeResponseReplay(session,request,input));
            execute("UPDATE nw_service_principals SET scopes='[\"sessions.write\"]'::jsonb WHERE id=1");
            execute("UPDATE nw_agents SET status='disabled' WHERE id=10");
            assertInstanceOf(AuthorizationResult.Denied.class,port.authorizeResponseReplay(session,request,input));
            execute("UPDATE nw_agents SET status='published' WHERE id=10");
            execute("UPDATE nw_agent_definition_grants SET status='revoked' WHERE grant_id=100");
            assertInstanceOf(AuthorizationResult.Denied.class,port.authorizeResponseReplay(session,request,input));
            execute("UPDATE nw_agent_definition_grants SET status='active' WHERE grant_id=100");
            execute("UPDATE nw_service_principals SET status='revoked' WHERE id=1");
            assertInstanceOf(AuthorizationResult.Denied.class,port.authorizeResponseReplay(session,request,input));
            session.rollback();
        }
    }

    @Test void frozenHistoricalRevisionUsesCurrentNarrowedActionsWithoutBlanketRevisionRejection() {
        directSource(OWNER);deployDirectAgent(10,100);
        frozenRun(OWNER,"10","narrow-chain-run",null,OWNER.sessionRef().sessionId());
        var identity=verified(port.verify(request(register(OWNER),OWNER,"channel","source")));
        var execution=execution(identity,"10","narrow-chain-run");
        execute("UPDATE nw_agent_definition_grants SET actions='execute',revision=2 WHERE grant_id=100");
        assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(execution));
        execute("UPDATE nw_agent_definition_grants SET actions='deploy',revision=3 WHERE grant_id=100");
        assertInstanceOf(AuthorizationResult.Denied.class,newPort().authorize(execution));
    }

    @Test void everyFrozenAncestorAndExactRelationRemainRequiredAtActualDispatch() {
        directSource(OWNER);deployDirectAgent(10,100);
        execute("UPDATE nw_tenants SET parent_tenant_id=2,relation_revision=1 WHERE id=1");
        execute("INSERT INTO nw_institution_relation_revisions VALUES(301,1,2,1)");
        execute("UPDATE nw_agent_definition_grants SET target_tenant_id=2,allow_regrant=true,remaining_depth=1 WHERE grant_id=100");
        execute("UPDATE nw_agent_definition_grant_revisions SET target_tenant_id=2,allow_regrant=true,remaining_depth=1 WHERE grant_id=100");
        execute("INSERT INTO nw_agent_definition_grants(grant_id,definition_id,target_tenant_id,status,revision,actions,grantor_kind,grantor_tenant_id,"
                +"parent_grant_id,parent_grant_revision,relation_ref,relation_revision) VALUES(101,100,1,'active',1,'execute','tenant',2,100,1,301,1)");
        execute("INSERT INTO nw_agent_definition_grant_revisions SELECT * FROM nw_agent_definition_grants WHERE grant_id=101");
        execute("UPDATE nw_agent_deployments SET grant_id=101,grant_chain=jsonb_build_object('definition_id','100','owner_kind','platform','owner_tenant_id',NULL,'hops',"
                +"jsonb_build_array(jsonb_build_object('grant_id','100','revision',1,'grantor_kind','platform','grantor_tenant_id',NULL,'target_tenant_id','2',"
                +"'parent_grant_id',NULL,'parent_grant_revision',NULL,'relation_ref',NULL,'relation_revision',NULL),"
                +"jsonb_build_object('grant_id','101','revision',1,'grantor_kind','tenant','grantor_tenant_id','2','target_tenant_id','1',"
                +"'parent_grant_id','100','parent_grant_revision',1,'relation_ref','301','relation_revision',1))) WHERE deployment_id=10");
        frozenRun(OWNER,"10","delegated-chain-run",null,OWNER.sessionRef().sessionId());
        var identity=verified(port.verify(request(register(OWNER),OWNER,"channel","source")));
        var execution=execution(identity,"10","delegated-chain-run");
        assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(execution));
        execute("UPDATE nw_agent_definition_grants SET actions='execute',revision=2 WHERE grant_id=100");
        assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(execution),"ancestor narrowing still covers the exact child execute scope");
        execute("UPDATE nw_tenants SET status='suspended' WHERE id=2");
        assertInstanceOf(AuthorizationResult.Denied.class,newPort().authorize(execution));
        execute("UPDATE nw_tenants SET status='active' WHERE id=2");
        execute("UPDATE nw_tenants SET relation_revision=2 WHERE id=1");
        execute("INSERT INTO nw_institution_relation_revisions VALUES(302,1,2,2)");
        assertInstanceOf(AuthorizationResult.Denied.class,newPort().authorize(execution),"reattaching the same parent does not revive the original relation reference");
    }

    @Test
    void delegatedSessionRechecksInheritedRootDirectWithoutItsOwnProductSession() {
        directSource(OWNER);
        frozenRun(OWNER,"10","parent-direct-run",null,OWNER.sessionRef().sessionId());
        execute("INSERT INTO nw_agents VALUES(12,1,'published')");
        var child=SessionOwner.user(new SessionRef(TENANT,"delegated-session"),new AgentId("12"),new EndUserId(20));
        execute("INSERT INTO agent_runtime_sessions VALUES('1','AGENT','12','delegated-session',20,'open-platform:integration')");
        execute("INSERT INTO agent_runtime_session_owners VALUES('1','AGENT','12','delegated-session',20)");
        frozenRun(child,"12","child-direct-run","parent-direct-run",OWNER.sessionRef().sessionId());
        var identity=verified(port.verify(request(register(child),child,"channel","source")));
        var execution=execution(identity,"12","child-direct-run");
        assertEquals(0,scalar("SELECT count(*) FROM nw_chat_sessions WHERE session_id='delegated-session'"));
        assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(execution));
        execute("UPDATE nw_tenants SET status='suspended' WHERE id=1");
        assertInstanceOf(AuthorizationResult.Denied.class,newPort().authorize(execution));
        execute("UPDATE nw_tenants SET status='active' WHERE id=1");
        assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(execution));
        execute("UPDATE nw_integration_business_scopes SET status='revoked' WHERE scope_id='902'");
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(execution));
    }

    @Test
    void systemResponsibilityCanCommitAfterDirectRevocationButCannotExecuteItsPersistedRun() {
        directSource(OWNER);
        deployDirectAgent(10,100);
        frozenRun(OWNER,"10","system-direct-run",null,OWNER.sessionRef().sessionId());
        var systemPort=new JdbcIdentityPolicyPort(dataSource(),Map.of(SYSTEM,Set.of("sessions.write")));
        var authentication=systemPort.registerTrusted(SYSTEM,OWNER,"timer","scheduler",Duration.ofMinutes(5));
        var identity=verified(systemPort.verify(request(authentication,OWNER,"timer","scheduler")));
        var execution=execution(identity,"10","system-direct-run");
        var run=runIdentity(identity,"10","system-direct-run");
        var model=new AuthorizationRequest(identity,"model.resolve",ModelPolicyResource.resolution(
                new com.skillforge.runtime.api.port.ModelResolutionPort.ModelResolutionRequest(run,identity,
                        new com.skillforge.runtime.api.port.ModelPort.ModelBindingRef(TENANT,"binding",1),
                        new com.skillforge.runtime.api.port.ModelPort.RequestedModelStrategy("strategy","1"))),"trace","span");
        var tool=new AuthorizationRequest(identity,"tool.invoke",toolResource(run),"trace","span");
        for(var invocation:List.of(execution,model,tool)) {
            assertInstanceOf(AuthorizationResult.Allowed.class,systemPort.authorize(invocation),invocation.action());
        }
        execute("UPDATE nw_tenants SET status='suspended' WHERE id=1");
        for(var invocation:List.of(execution,model,tool)) {
            assertInstanceOf(AuthorizationResult.Denied.class,systemPort.authorize(invocation),invocation.action());
        }
        assertInstanceOf(AuthorizationResult.Allowed.class,systemPort.authorize(new AuthorizationRequest(identity,
                "session.claim",SessionPolicyResource.claim(OWNER),"trace","span")));
        assertInstanceOf(AuthorizationResult.Allowed.class,systemPort.authorize(new AuthorizationRequest(identity,
                "session.commit",execution.resource(),"trace","span")),
                "system responsibility may persist the terminal state after execution authority is revoked");
    }

    @Test
    void knowledgeExecutionRequiresStrictOwnerResourceAndCurrentWriteScope() {
        var authentication=register(OWNER);var identity=verified(port.verify(request(authentication,OWNER,"channel","source")));
        var resource=IdentityPolicyResources.knowledge(identity);
        assertEquals(11,assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(new AuthorizationRequest(identity,"knowledge.execute",resource,"trace","span"))).policyRevision());
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(identity,"knowledge.query",resource,"trace","span")));
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(identity,"knowledge.execute",
                new TypedPayload(resource.schemaId(),resource.canonicalJson().replace("\"session\"","\"foreign\"")),"trace","span")));
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=1");
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(identity,"knowledge.execute",resource,"trace","span")));
    }

    @Test
    void trustedHostOwnerDoesNotRequireInstitutionAgentAndRejectsIntegrationDrift() {
        SessionOwner host = SessionOwner.user(new SessionRef(TENANT,"system-agent:1"),
                new AgentId("platform-agent"),new EndUserId(1));
        execute("INSERT INTO nw_runtime_host_owners(tenant_id,agent_id,session_id,end_user_id,integration_id)"
                + " VALUES('1','platform-agent','system-agent:1',1,'integration')");
        var authentication = register(host);
        var identity=verified(newPort().verify(request(authentication,host,"channel","source")));
        assertEquals(host,identity.owner());
        assertInstanceOf(AuthorizationResult.Allowed.class,newPort().authorize(authorization(identity)));
        assertInstanceOf(IdentityResult.Verified.class, newPort().verifyAccess(access(authentication, TENANT,
                host.target().requireAgent(), host.requireUser(), "channel", "source")));
        assertInstanceOf(IdentityResult.Rejected.class, newPort().verifyAccess(access(authentication, TENANT,
                new AgentId("foreign-agent"), host.requireUser(), "channel", "source")));
        SessionOwner foreign = SessionOwner.user(new SessionRef(TENANT,"system-agent:2"),
                host.target().requireAgent(),host.requireUser());
        execute("INSERT INTO nw_runtime_host_owners(tenant_id,agent_id,session_id,end_user_id,integration_id)"
                + " VALUES('1','platform-agent','system-agent:2',1,'another-integration')");
        assertThrows(RuntimeException.class,()->register(foreign));
    }

    @Test
    void trustedServiceVerificationAndAuthorizationSurviveAdapterRebuild() {
        AuthenticationRef authentication = register(OWNER);
        VerifiedIdentity identity = verified(newPort().verify(request(authentication, OWNER, "channel", "source")));
        assertEquals(SERVICE, identity.principal());
        assertEquals(OWNER, identity.owner());
        assertEquals(Set.of("sessions.write"), identity.scopes());
        assertInstanceOf(AuthorizationResult.Allowed.class, newPort().authorize(authorization(identity)));
        assertEquals(1, count(TABLES.get(0)));
        assertEquals(1, count(TABLES.get(1)));
        assertEquals(2, count(TABLES.get(2)));
        // 尚未创建 Session 的可信入站，只要求 Agent/EndUser 已存在且归属正确。
        SessionOwner fresh = SessionOwner.user(new SessionRef(TENANT, "new-session"), OWNER.target().requireAgent(), OWNER.requireUser());
        VerifiedIdentity newSession = verified(port.verify(request(register(fresh), fresh, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(authorization(newSession)));
    }

    @Test
    void facadeAccessRestoresAuthenticatedOwnerRejectsClaimDriftAndUsesCurrentScopes() {
        var authentication = register(OWNER);
        var request = access(authentication, TENANT, OWNER.target().requireAgent(), OWNER.requireUser(),
                "channel", "source");
        var identity = verified(newPort().verifyAccess(request));
        assertEquals(OWNER, identity.owner());
        for (var drift : List.of(
                access(authentication, new TenantId("2"), OWNER.target().requireAgent(), OWNER.requireUser(), "channel", "source"),
                access(authentication, TENANT, new AgentId("11"), OWNER.requireUser(), "channel", "source"),
                access(authentication, TENANT, OWNER.target().requireAgent(), new EndUserId(21), "channel", "source"),
                access(authentication, TENANT, OWNER.target().requireAgent(), OWNER.requireUser(), "wrong", "source"),
                access(authentication, TENANT, OWNER.target().requireAgent(), OWNER.requireUser(), "channel", "wrong"))) {
            assertInstanceOf(IdentityResult.Rejected.class, port.verifyAccess(drift));
        }

        var query = facadeResource("runtime.run.query", "run", "run", "0", "request");
        var cancel = facadeResource("runtime.cancel", "run", "run", "2", "cancel");
        var steer = facadeResource("runtime.run.steer", "run", "run", "0", "steer");
        var human = facadeResource("runtime.session.human-assistance", "session", "session", "0", "human-command");
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=1");
        assertInstanceOf(AuthorizationResult.Allowed.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.run.query", query, "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.cancel", cancel, "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.run.steer", steer, "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.session.human-assistance", human, "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.run.query",
                        new TypedPayload(query.schemaId(), query.canonicalJson() + " "), "trace", "span")));

        execute("UPDATE nw_service_principals SET scopes='[\"sessions.write\"]'::jsonb WHERE id=1");
        assertInstanceOf(AuthorizationResult.Allowed.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.cancel", cancel, "trace", "span")));
        assertInstanceOf(AuthorizationResult.Allowed.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.run.steer", steer, "trace", "span")));
        assertInstanceOf(AuthorizationResult.Allowed.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.session.human-assistance", human, "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.session.human-assistance",
                        facadeResource("runtime.session.human-assistance", "session", "other-session", "0", "human-command"), "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.run.query", query, "trace", "span")));
    }

    @Test
    void facadeSessionCloseRequiresCurrentWriteScopeAndTheCompleteOwner() {
        var authentication = register(OWNER);
        var identity = verified(port.verifyAccess(access(authentication, TENANT, OWNER.target().requireAgent(),
                OWNER.requireUser(), "channel", "source")));
        var close = facadeResource("runtime.session.close", "session", "session", "0", "close-command");
        assertFalse(AgentRuntimeAccessResource.readAction("runtime.session.close"));
        assertInstanceOf(AuthorizationResult.Allowed.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.session.close", close, "trace", "span")));

        execute("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=1");
        assertInstanceOf(AuthorizationResult.Denied.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.session.close", close, "trace", "span")));
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.write\"]'::jsonb WHERE id=1");
        for (var changed : List.of(
                close.canonicalJson().replace("\"1\"", "\"2\""),
                close.canonicalJson().replace("\"AGENT\"", "\"TEAM\""),
                close.canonicalJson().replace("\"10\"", "\"11\""),
                close.canonicalJson().replace("\"session\"", "\"other-session\""),
                close.canonicalJson().replace("\"20\"", "\"21\""),
                facadeResource("runtime.session.close", "run", "session", "0", "close-command").canonicalJson(),
                facadeResource("runtime.session.close", "session", "other-session", "0", "close-command").canonicalJson(),
                facadeResource("runtime.session.close", "session", "session", "1", "close-command").canonicalJson(),
                close.canonicalJson() + " ")) {
            assertInstanceOf(AuthorizationResult.Denied.class,
                    port.authorize(new AuthorizationRequest(identity, "runtime.session.close",
                            new TypedPayload(close.schemaId(), changed), "trace", "span")));
        }
        port.revoke(authentication);
        assertInstanceOf(AuthorizationResult.Denied.class,
                port.authorize(new AuthorizationRequest(identity, "runtime.session.close", close, "trace", "span")));
    }

    @Test
    void workflowActionsRequireExplicitCurrentReadOrWriteScopeAndStrictOwnerResource() {
        var authentication = register(OWNER);
        var identity = verified(port.verify(request(authentication, OWNER, "channel", "source")));
        var resources = IdentityPolicyResources.workflows(identity);
        for (var entry : resources.entrySet()) {
            var result = port.authorize(new AuthorizationRequest(identity, entry.getKey(), entry.getValue(), "trace", "span"));
            if (entry.getKey().equals("workflow.query")) assertInstanceOf(AuthorizationResult.Denied.class, result);
            else assertEquals(11, assertInstanceOf(AuthorizationResult.Allowed.class, result).policyRevision());
            var changed = new TypedPayload(entry.getValue().schemaId(), entry.getValue().canonicalJson().replace("\"session\"", "\"foreign-session\""));
            assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity, entry.getKey(), changed, "trace", "span")));
        }
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.read\"]'::jsonb WHERE id=1");
        assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(new AuthorizationRequest(identity, "workflow.query", resources.get("workflow.query"), "trace", "span")));
        for (String action : List.of("workflow.dispatch", "workflow.resume", "workflow.recover", "workflow.cancel", "workflow.execute"))
            assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity, action, resources.get(action), "trace", "span")));
        port.revoke(authentication);
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity, "workflow.query", resources.get("workflow.query"), "trace", "span")));
    }

    @Test
    void callbackActionsRequireCurrentIdentityAndStrictExecutionResource() {
        var current = verified(port.verify(request(register(OWNER), OWNER, "channel", "source")));
        var command = callbackCommand(current, SERVICE);
        var registerResource=CallbackPolicyResource.registration(command);
        assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(new AuthorizationRequest(current,"callback.register",registerResource,"trace","span")));
        var registration = new CallbackRegistration(command.registration(), command.causationIdentityRef(),
                CallbackRegistrationStatus.ACTIVE, 1, Instant.EPOCH, Instant.EPOCH);
        var status = new ChangeCallbackStatus(registration.draft().callbackRef(), current, 1,
                CallbackRegistrationStatus.SUSPENDED, new InputId("control"), command.trace(), new RequestId("suspend"), "suspend-fp");
        var statusResource=CallbackPolicyResource.status(registration,status);
        assertInstanceOf(AuthorizationResult.Allowed.class,port.authorize(new AuthorizationRequest(current,"callback.status",statusResource,"trace","span")));
        var wire = CallbackPolicyResource.registration(command);
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(current,
                "callback.register", new TypedPayload(wire.schemaId(), wire.canonicalJson().replace("\"session\"", "\"session\",\"extra\"")),
                "trace", "span")));
        execute("UPDATE nw_service_principals SET scopes='[]'::jsonb WHERE id=1");
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(current,"callback.register",registerResource,"trace","span")));
        assertInstanceOf(AuthorizationResult.Denied.class,port.authorize(new AuthorizationRequest(current,"callback.status",statusResource,"trace","span")));
    }

    private static RegisterCallback callbackCommand(VerifiedIdentity identity, PrincipalRef.ServicePrincipal keyOwner) {
        var draft = new CallbackRegistrationDraft(new CallbackRef(TENANT, "callback"), OWNER,
                new CallbackTarget.ExecutionTarget(new com.skillforge.runtime.api.ExecutionRef(TENANT,"callback-execution"),new com.skillforge.runtime.api.RunRef(TENANT,"callback-run"),"action",1), "source", CallbackPayloads.EXECUTION_SCHEMA,
                CallbackPayloads.SIGNATURE_POLICY, JdbcCallbackSigningKeys.reference(keyOwner));
        var trace=new TraceEnvelope("trace","span",new InputId("root"),Optional.empty());
        var run=new com.skillforge.runtime.api.port.RunIdentity(OWNER,OWNER.target().requireAgent(),new InputId("cause"),new com.skillforge.runtime.api.RunRef(TENANT,"callback-run"),trace,identity.verificationRef(),
                new RunRequester.EndUser(OWNER.requireUser()),Optional.empty(),new RunOriginRef(TENANT,"execution-input:callback-run"));
        return new RegisterCallback(draft, identity, Optional.of(run), new InputId("cause"),trace,
                identity.verificationRef(), new IdempotencyKey("callback-key"), "callback-fingerprint");
    }

    @Test
    void forgedIdentityFieldsAndUnknownVerificationCannotAuthorize() {
        VerifiedIdentity original = verified(port.verify(request(register(OWNER), OWNER, "channel", "source")));
        SessionOwner otherOwner = SessionOwner.user(new SessionRef(TENANT, "forged"), OWNER.target().requireAgent(), OWNER.requireUser());
        List<VerifiedIdentity> forgeries = List.of(
                new VerifiedIdentity(new PrincipalRef.ServicePrincipal(TENANT, "other-service", "integration", 7),
                        OWNER, original.scopes(), original.policyRevision(), original.verificationRef()),
                new VerifiedIdentity(SERVICE, otherOwner, original.scopes(), original.policyRevision(), original.verificationRef()),
                new VerifiedIdentity(SERVICE, OWNER, Set.of("sessions.write", "admin"), original.policyRevision(), original.verificationRef()),
                new VerifiedIdentity(SERVICE, OWNER, original.scopes(), original.policyRevision() + 1, original.verificationRef()),
                new VerifiedIdentity(SERVICE, OWNER, original.scopes(), original.policyRevision(), new ResultRef("unknown-verification")));
        for (VerifiedIdentity forgery : forgeries) {
            assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(authorization(forgery)));
        }
        assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(authorization(original)));
        assertEquals(1, count(TABLES.get(1)));
        assertEquals(7, count(TABLES.get(2)));
    }

    @Test
    void authenticationCannotBeReboundToClaimedOwnerChannelOrSource() {
        AuthenticationRef ref = register(OWNER);
        SessionOwner other = SessionOwner.user(new SessionRef(new TenantId("2"), "session"), new AgentId("11"), new EndUserId(21));
        assertInstanceOf(IdentityResult.Rejected.class, port.verify(request(ref, other, "channel", "source")));
        assertInstanceOf(IdentityResult.Rejected.class, port.verify(request(ref, OWNER, "wrong-channel", "source")));
        assertInstanceOf(IdentityResult.Rejected.class, port.verify(request(ref, OWNER, "channel", "wrong-source")));
        assertInstanceOf(IdentityResult.Rejected.class, port.verify(request(new AuthenticationRef("unknown"), OWNER, "channel", "source")));
        VerifiedIdentity identity = verified(port.verify(request(ref, OWNER, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity, "runtime.accept",
                resource(OWNER, "wrong-channel", "source"), "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity, "runtime.accept",
                resource(OWNER, "channel", "wrong-source"), "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity, "runtime.accept",
                resource(other, "channel", "source"), "trace", "span")));
    }

    @Test
    void revocationAndCredentialRotationInvalidateExistingVerification() {
        AuthenticationRef ref = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(ref, OWNER, "channel", "source")));
        newPort().revoke(ref);
        assertInstanceOf(IdentityResult.Rejected.class, port.verify(request(ref, OWNER, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(authorization(identity)));
        AuthenticationRef rotated = register(OWNER);
        VerifiedIdentity beforeRotation = verified(port.verify(request(rotated, OWNER, "channel", "source")));
        execute("UPDATE nw_service_principals SET credential_version=8");
        assertInstanceOf(IdentityResult.Rejected.class, port.verify(request(rotated, OWNER, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(authorization(beforeRotation)));
        assertThrows(TrustedAuthenticationRejectedException.class, () -> register(OWNER));
    }

    @Test
    void currentIamStatusScopesIntegrationAndSessionScopeAreRechecked() {
        AuthenticationRef ref = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(ref, OWNER, "channel", "source")));
        for (String change : List.of("status='revoked'", "scopes='[]'::jsonb", "integration_id='other-integration'")) {
            execute("UPDATE nw_service_principals SET " + change);
            assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(authorization(identity)), change);
            execute("UPDATE nw_service_principals SET status='active',scopes='[\"sessions.write\"]'::jsonb,integration_id='integration'");
        }
        execute("UPDATE agent_runtime_sessions SET scope_key='open-platform:other-integration'");
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(authorization(identity)));
        execute("UPDATE agent_runtime_sessions SET scope_key='open-platform:integration'");
        execute("UPDATE nw_end_users SET status='disabled' WHERE id=20");
        assertInstanceOf(IdentityResult.Rejected.class, port.verify(request(ref, OWNER, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(authorization(identity)));
        execute("UPDATE nw_end_users SET status='active' WHERE id=20");
        execute("UPDATE agent_runtime_session_owners SET end_user_id=999");
        assertInstanceOf(IdentityResult.Rejected.class, port.verify(request(ref, OWNER, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(authorization(identity)));
        execute("UPDATE agent_runtime_session_owners SET end_user_id=20");
        execute("UPDATE nw_agents SET tenant_id=2 WHERE id=10");
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(authorization(identity)));
    }

    @Test
    void authorizationUsesOneCurrentReadAndStillObservesEveryLiveRevocationBoundary() {
        AuthenticationRef authentication = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(authentication, OWNER, "channel", "source")));
        var statements = new ArrayList<String>();
        var measured = new JdbcIdentityPolicyPort(countingDataSource(dataSource(), statements), Map.of());

        assertInstanceOf(AuthorizationResult.Allowed.class, authorizeOnce(measured, identity, statements));
        var forged = new VerifiedIdentity(identity.principal(), identity.owner(), Set.of("sessions.read"),
                identity.policyRevision(), identity.verificationRef());
        assertInstanceOf(AuthorizationResult.Denied.class, authorizeOnce(measured, forged, statements));

        execute("UPDATE nw_service_principals SET scopes='[]'::jsonb WHERE id=1");
        assertInstanceOf(AuthorizationResult.Denied.class, authorizeOnce(measured, identity, statements));
        execute("UPDATE nw_service_principals SET scopes='[\"sessions.write\"]'::jsonb WHERE id=1");

        execute("UPDATE nw_end_users SET status='disabled' WHERE id=20");
        assertInstanceOf(AuthorizationResult.Denied.class, authorizeOnce(measured, identity, statements));
        execute("UPDATE nw_end_users SET status='active' WHERE id=20");

        execute("UPDATE agent_runtime_sessions SET scope_key='open-platform:other-integration'");
        assertInstanceOf(AuthorizationResult.Denied.class, authorizeOnce(measured, identity, statements));
        execute("UPDATE agent_runtime_sessions SET scope_key='open-platform:integration'");

        measured.revoke(authentication);
        assertInstanceOf(AuthorizationResult.Denied.class, authorizeOnce(measured, identity, statements));
    }

    @Test
    void authorizationCommitsAuditBeforeReturningOnOneReadCommittedConnection() {
        AuthenticationRef authentication = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(authentication, OWNER, "channel", "source")));
        var statements = new ArrayList<String>();
        var connections = new AtomicInteger();
        var isolations = new ArrayList<Integer>();
        var measured = new JdbcIdentityPolicyPort(
                observedDataSource(dataSource(), statements, connections, isolations, null), Map.of());
        long audits = count(TABLES.get(2));

        var allowed = assertInstanceOf(AuthorizationResult.Allowed.class,
                measured.authorize(authorization(identity)));

        assertEquals(1, connections.get());
        assertFalse(isolations.isEmpty());
        assertTrue(isolations.stream().allMatch(level -> level == Connection.TRANSACTION_READ_COMMITTED));
        assertEquals(audits + 1, count(TABLES.get(2)));
        assertEquals(1, scalar("SELECT count(*) FROM nw_runtime_security_audits WHERE audit_ref='"
                + allowed.auditRef().value().replace("'", "''") + "' AND result='allowed'"));

        execute("UPDATE nw_service_principals SET scopes='[]'::jsonb WHERE id=1");
        var denied = assertInstanceOf(AuthorizationResult.Denied.class,
                measured.authorize(authorization(identity)));
        assertEquals(2, connections.get());
        assertEquals(audits + 2, count(TABLES.get(2)));
        assertEquals(1, scalar("SELECT count(*) FROM nw_runtime_security_audits WHERE audit_ref='"
                + denied.auditRef().value().replace("'", "''") + "' AND result='authorization_denied'"));
    }

    /** 派发所属事务中的授权审计与业务写入共同提交或回滚，不新开连接或重绑租户。 */
    @Test void joinedAuthorizationAndAuditFollowTheOwningBusinessTransaction() throws Exception {
        var identity = verified(port.verify(request(register(OWNER), OWNER, "channel", "source")));
        var request = new AuthorizationRequest(identity, "session.claim", SessionPolicyResource.claim(OWNER), "trace", "span");
        var statements = new ArrayList<String>(); var connections = new AtomicInteger();
        var factory = joinedSessions(observedDataSource(dataSource(), statements, connections, new ArrayList<>(), null));
        long before = count(TABLES.get(2));
        try (var session = factory.openSession(false)) {
            session.getMapper(JdbcIdentityPolicyPort.Mapper.class).bindTenant(TENANT.value());
            statements.clear();
            var result = assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(session, request));
            assertEquals(1, connections.get()); assertEquals(before, count(TABLES.get(2)));
            assertEquals(2, statements.size(), "one current IAM read and one existing-boundary audit");
            assertTrue(statements.stream().noneMatch(sql -> sql.contains("set_config")));
            try (var insert = session.getConnection().createStatement()) { insert.execute("INSERT INTO identity_test_business_probe VALUES(1)"); }
            assertEquals(1, joinedScalar(session, "SELECT count(*) FROM nw_runtime_security_audits WHERE audit_ref='" + result.auditRef().value() + "'"));
            session.rollback();
        }
        assertEquals(before, count(TABLES.get(2))); assertEquals(0, count("identity_test_business_probe"));
        try (var session = factory.openSession(false)) {
            session.getMapper(JdbcIdentityPolicyPort.Mapper.class).bindTenant(TENANT.value());
            assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(session, request));
            try (var insert = session.getConnection().createStatement()) { insert.execute("INSERT INTO identity_test_business_probe VALUES(2)"); }
            session.commit();
        }
        assertEquals(before + 1, count(TABLES.get(2))); assertEquals(1, count("identity_test_business_probe"));
    }

    /** 原已加载身份不能越过当前撤权或RLS；授权查询不在事务内部切换租户。 */
    @Test void joinedAuthorizationObservesRevocationAndKeepsMismatchedTenantBound() throws Exception {
        var authentication = register(OWNER); var identity = verified(port.verify(request(authentication, OWNER, "channel", "source")));
        var request = new AuthorizationRequest(identity, "session.claim", SessionPolicyResource.claim(OWNER), "trace", "span");
        var factory = joinedSessions(dataSource());
        try (var session = factory.openSession(false)) {
            session.getMapper(JdbcIdentityPolicyPort.Mapper.class).bindTenant(TENANT.value());
            assertEquals(Optional.of(identity), port.originalVerification(session, OWNER, identity.verificationRef()));
            assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(session, request));
            execute("UPDATE nw_service_principals SET scopes='[]'::jsonb WHERE id=1");
            assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(session, request));
            session.rollback();
        }
        try (var session = factory.openSession(false)) {
            session.getMapper(JdbcIdentityPolicyPort.Mapper.class).bindTenant("2");
            assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(session, request));
            assertTrue(port.originalVerification(session, OWNER, identity.verificationRef()).isEmpty());
            try (var query = session.getConnection().createStatement(); var rows = query.executeQuery("SELECT current_setting('app.tenant_id',true)")) {
                assertTrue(rows.next()); assertEquals("2", rows.getString(1));
            }
            session.commit();
        }
        assertEquals(1, scalar("SELECT count(*) FROM nw_runtime_security_audits WHERE operation='authorize' AND verified_tenant_id IS NULL AND result='authorization_denied'"));
    }

    /** 技术故障保留原因并由所属事务回滚，不能另开连接返回伪Allowed或失败审计。 */
    @Test void joinedAuthorizationTechnicalFailureThrowsAndDoesNotCommitAudit() throws Exception {
        var identity = verified(port.verify(request(register(OWNER), OWNER, "channel", "source")));
        long before = count(TABLES.get(2)); var factory = joinedSessions(dataSource());
        execute("REVOKE SELECT ON nw_service_principals FROM " + ROLE);
        try {
            try (var session = factory.openSession(false)) {
                session.getMapper(JdbcIdentityPolicyPort.Mapper.class).bindTenant(TENANT.value());
                var failure = assertThrows(IllegalStateException.class, () -> port.authorize(session, authorization(identity)));
                assertEquals("authorization_transaction_failed", failure.getMessage()); assertNotNull(failure.getCause());
                session.rollback();
            }
            assertEquals(before, count(TABLES.get(2)));
        } finally { execute("GRANT SELECT ON nw_service_principals TO " + ROLE); }
    }

    @Test
    void authorizationCommitFailureCannotReturnAllowedAndPersistsFailureAudit() {
        AuthenticationRef authentication = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(authentication, OWNER, "channel", "source")));
        var statements = new ArrayList<String>();
        var connections = new AtomicInteger();
        var failCommit = new AtomicBoolean(true);
        var measured = new JdbcIdentityPolicyPort(
                observedDataSource(dataSource(), statements, connections, new ArrayList<>(), failCommit), Map.of());
        long audits = count(TABLES.get(2));

        var failed = assertInstanceOf(AuthorizationResult.Failed.class,
                measured.authorize(authorization(identity)));

        assertEquals("policy_unavailable", failed.failure().reasonCode());
        assertEquals(2, connections.get());
        assertEquals(audits + 1, count(TABLES.get(2)),
                "the uncommitted allowed audit must roll back before the failure audit commits");
        assertEquals(1, scalar("SELECT count(*) FROM nw_runtime_security_audits "
                + "WHERE result='policy_unavailable'"));
    }

    @Test
    void originalVerificationCachesOnlyPositiveExactOwnerAndRemainsHistoricalAfterRevocation() {
        AuthenticationRef authentication = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(authentication, OWNER, "channel", "source")));
        var statements = new ArrayList<String>();
        var measured = new JdbcIdentityPolicyPort(countingDataSource(dataSource(), statements), Map.of());

        assertEquals(identity, measured.originalVerification(OWNER, identity.verificationRef()).orElseThrow());
        assertTrue(statements.stream().anyMatch(statement -> statement.contains("nw_runtime_identity_verifications")));
        statements.clear();
        assertEquals(identity, measured.originalVerification(OWNER, identity.verificationRef()).orElseThrow());
        assertTrue(statements.isEmpty());

        var wrongOwner = SessionOwner.user(OWNER.sessionRef(), OWNER.target().requireAgent(), new EndUserId(21));
        for (int attempt = 0; attempt < 2; attempt++) {
            statements.clear();
            assertTrue(measured.originalVerification(wrongOwner, identity.verificationRef()).isEmpty());
            assertTrue(statements.stream().anyMatch(
                    statement -> statement.contains("nw_runtime_identity_verifications")));
        }
        var missing = new ResultRef("missing-verification");
        for (int attempt = 0; attempt < 2; attempt++) {
            statements.clear();
            assertTrue(measured.originalVerification(OWNER, missing).isEmpty());
            assertTrue(statements.stream().anyMatch(
                    statement -> statement.contains("nw_runtime_identity_verifications")));
        }

        measured.revoke(authentication);
        statements.clear();
        assertEquals(identity, measured.originalVerification(OWNER, identity.verificationRef()).orElseThrow());
        assertTrue(statements.isEmpty());
        assertInstanceOf(AuthorizationResult.Denied.class, measured.authorize(authorization(identity)));
    }

    @Test
    void unsupportedActionSchemaAndUnregisteredSystemPrincipalFailClosed() {
        VerifiedIdentity identity = verified(port.verify(request(register(OWNER), OWNER, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity,
                "runtime.admin", resource(OWNER, "channel", "source"), "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity,
                "runtime.accept", new TypedPayload("unknown.v1", "{}"), "trace", "span")));
        assertInstanceOf(AuthorizationResult.Denied.class, port.authorize(new AuthorizationRequest(identity,
                "runtime.accept", new TypedPayload("skillforge.runtime.session.admission-resource.v1", "[]"), "trace", "span")));
        assertThrows(TrustedAuthenticationRejectedException.class, () -> port.registerTrusted(SYSTEM, OWNER, "channel", "source", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> port.registerTrusted(SERVICE, OWNER, "channel", "source", Duration.ofHours(2)));
        SessionOwner fresh = SessionOwner.user(new SessionRef(TENANT, "system-session"), OWNER.target().requireAgent(), OWNER.requireUser());
        JdbcIdentityPolicyPort systemPort = new JdbcIdentityPolicyPort(dataSource(), Map.of(SYSTEM, Set.of("sessions.write")));
        AuthenticationRef systemRef = systemPort.registerTrusted(SYSTEM, fresh, "channel", "source", Duration.ofMinutes(5));
        VerifiedIdentity systemIdentity = verified(systemPort.verify(request(systemRef, fresh, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Allowed.class, systemPort.authorize(authorization(systemIdentity)));
        var noGrants = new JdbcIdentityPolicyPort(dataSource(), Map.of(SYSTEM, Set.of()));
        assertInstanceOf(AuthorizationResult.Denied.class, noGrants.authorize(authorization(systemIdentity)));
    }

    @Test
    void committedSecurityAuditSurvivesIndependentBusinessTransactionRollback() throws Exception {
        AuthenticationRef ref = register(OWNER);
        try (Connection business = dataSource().getConnection(); var statement = business.createStatement()) {
            business.setAutoCommit(false);
            statement.execute("INSERT INTO identity_test_business_probe VALUES (1)");
            VerifiedIdentity identity = verified(port.verify(request(ref, OWNER, "channel", "source")));
            assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(authorization(identity)));
            business.rollback();
        }
        assertEquals(0, count("identity_test_business_probe"));
        assertEquals(1, count(TABLES.get(1)));
        assertEquals(2, count(TABLES.get(2)));
    }

    @Test
    void auditWriteFailureCannotReleaseVerifiedIdentityOrAllowedDecision() {
        AuthenticationRef ref = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(ref, OWNER, "channel", "source")));
        long verifications = count(TABLES.get(1));
        long audits = count(TABLES.get(2));
        execute("CREATE FUNCTION identity_test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected_audit_failure'; END $$");
        execute("CREATE TRIGGER identity_test_fail_audit BEFORE INSERT ON nw_runtime_security_audits FOR EACH ROW EXECUTE FUNCTION identity_test_fail_audit()");
        try {
            assertThrows(IllegalStateException.class, () -> port.verify(request(ref, OWNER, "channel", "source")));
            assertThrows(IllegalStateException.class, () -> port.authorize(authorization(identity)));
            assertEquals(verifications, count(TABLES.get(1)));
            assertEquals(audits, count(TABLES.get(2)));
        } finally {
            execute("DROP TRIGGER identity_test_fail_audit ON nw_runtime_security_audits");
            execute("DROP FUNCTION identity_test_fail_audit()");
        }
        assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(authorization(identity)));
    }

    @Test
    void unavailableIamFailsClosedAndStillCommitsIndependentAudit() {
        AuthenticationRef ref = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(ref, OWNER, "channel", "source")));
        execute("REVOKE SELECT ON nw_service_principals FROM " + ROLE);
        try {
            var rejected = assertInstanceOf(IdentityResult.Rejected.class,
                    port.verify(request(ref, OWNER, "channel", "source")));
            assertEquals("identity_unavailable", rejected.failure().reasonCode());
            var failed = assertInstanceOf(AuthorizationResult.Failed.class, port.authorize(authorization(identity)));
            assertEquals("policy_unavailable", failed.failure().reasonCode());
            assertEquals(1, count(TABLES.get(1)));
            assertEquals(1, scalar("SELECT count(*) FROM nw_runtime_security_audits WHERE result='identity_unavailable'"));
            assertEquals(1, scalar("SELECT count(*) FROM nw_runtime_security_audits WHERE result='policy_unavailable'"));
            assertEquals(3, count(TABLES.get(2)));
        } finally {
            execute("GRANT SELECT ON nw_service_principals TO " + ROLE);
        }
        assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(authorization(identity)));
    }

    @Test
    void restoredWorkerScopesAuthorizeTheSamePersistedSystemActorAndStillCheckCurrentOwner() {
        var originalGrants=Map.of(SYSTEM,Set.of("sessions.write"));
        var original=new JdbcIdentityPolicyPort(dataSource(),originalGrants);
        var authentication=original.registerTrusted(SYSTEM,OWNER,"channel","source",Duration.ofHours(1));
        var actor=verified(original.verify(request(authentication,OWNER,"channel","source")));
        var run=new com.skillforge.runtime.api.port.RunIdentity(OWNER,OWNER.target().requireAgent(),new InputId("origin-input"),
                new com.skillforge.runtime.api.RunRef(TENANT,"recovered-run"),
                new TraceEnvelope("trace","span",new InputId("root"),Optional.empty()),actor.verificationRef(),
                new RunRequester.EndUser(OWNER.requireUser()),Optional.empty(),new RunOriginRef(TENANT,"execution-input:recovered-run"));
        var restartedGrants=new java.util.concurrent.ConcurrentHashMap<PrincipalRef.SystemPrincipal,Set<String>>();
        var restarted=new JdbcIdentityPolicyPort(dataSource(),restartedGrants);
        var restored=restarted.originalVerification(OWNER,actor.verificationRef()).orElseThrow();
        assertEquals(actor,restored);
        var control=new AuthorizationRequest(restored,"session.execute",SessionPolicyResource.run(run,Optional.empty()),"trace","span");
        assertInstanceOf(AuthorizationResult.Denied.class,restarted.authorize(control));
        restartedGrants.put(SYSTEM,Set.of("sessions.write"));
        assertInstanceOf(AuthorizationResult.Allowed.class,restarted.authorize(control));
        assertEquals(actor.verificationRef(),restored.verificationRef());
        execute("UPDATE nw_agents SET status='draft' WHERE id=10");
        assertInstanceOf(AuthorizationResult.Denied.class,restarted.authorize(control));
    }

    @Test
    void longLivedSystemAuthenticationSurvivesRebuildForFrozenAsyncResponsibility() {
        Map<PrincipalRef.SystemPrincipal, Set<String>> grants = Map.of(SYSTEM, Set.of("sessions.write"));
        JdbcIdentityPolicyPort original = new JdbcIdentityPolicyPort(dataSource(), grants);
        AuthenticationRef ref = original.registerTrusted(SYSTEM, OWNER, "channel", "source", Duration.ofHours(24));
        JdbcIdentityPolicyPort rebuilt = new JdbcIdentityPolicyPort(dataSource(), grants);
        VerifiedIdentity identity = verified(rebuilt.verify(request(ref, OWNER, "channel", "source")));
        assertEquals(SYSTEM, identity.principal());
        assertInstanceOf(AuthorizationResult.Allowed.class,
                new JdbcIdentityPolicyPort(dataSource(), grants).authorize(authorization(identity)));
        assertEquals(1, scalar("SELECT count(*) FROM nw_runtime_authentication_facts "
                + "WHERE principal_kind='SYSTEM' AND expires_at > clock_timestamp() + interval '23 hours'"));
    }

    @Test
    void databaseExpiryRejectsAuthenticationAndPreviouslyVerifiedIdentity() throws Exception {
        AuthenticationRef ref = port.registerTrusted(SERVICE, OWNER, "channel", "source", Duration.ofSeconds(2));
        VerifiedIdentity identity = verified(port.verify(request(ref, OWNER, "channel", "source")));
        // 等到持久 expires_at 之后，不篡改不可变认证事实，也不依赖测试 JVM 的时钟。
        try (Connection connection = admin(); var wait = connection.prepareStatement(
                "SELECT pg_sleep(GREATEST(0,EXTRACT(EPOCH FROM expires_at-clock_timestamp()))+0.01) "
                        + "FROM nw_runtime_authentication_facts WHERE authentication_ref=?")) {
            wait.setString(1, ref.value());
            wait.execute();
        }
        assertInstanceOf(IdentityResult.Rejected.class, newPort().verify(request(ref, OWNER, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Denied.class, newPort().authorize(authorization(identity)));
        assertEquals(1, count(TABLES.get(1)));
        assertEquals(3, count(TABLES.get(2)));
    }

    @Test
    void restrictedRoleCannotReadAnotherTenantsFactsAndResolverReturnsOnlyRealTenant() throws Exception {
        AuthenticationRef ref = register(OWNER);
        VerifiedIdentity identity = verified(port.verify(request(ref, OWNER, "channel", "source")));
        assertInstanceOf(AuthorizationResult.Allowed.class, port.authorize(authorization(identity)));
        try (Connection connection = dataSource().getConnection(); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(row.next()); assertFalse(row.getBoolean(1)); assertFalse(row.getBoolean(2));
            }
            for (String tenant : List.of("", "2", "1")) {
                try (var setting = connection.prepareStatement("SELECT set_config('app.tenant_id',?,false)")) {
                    setting.setString(1, tenant); setting.execute();
                }
                for (String table : TABLES) {
                    try (var row = statement.executeQuery("SELECT count(*) FROM " + table)) {
                        assertTrue(row.next());
                        assertEquals(tenant.equals("1") ? (table.equals(TABLES.get(2)) ? 2 : 1) : 0, row.getLong(1), table);
                    }
                }
            }
            statement.execute("SELECT set_config('app.tenant_id','2',false)");
            try (var query = connection.prepareStatement("SELECT resolve_runtime_authentication_tenant(?)")) {
                query.setString(1, ref.value());
                try (var row = query.executeQuery()) { assertTrue(row.next()); assertEquals("1", row.getString(1)); }
                query.setString(1, "unknown");
                try (var row = query.executeQuery()) { assertTrue(row.next()); assertEquals(null, row.getString(1)); }
            }
        }
        assertEquals(3, scalar("SELECT count(*) FROM pg_class WHERE relname IN ('nw_runtime_authentication_facts',"
                + "'nw_runtime_identity_verifications','nw_runtime_security_audits') AND relrowsecurity AND relforcerowsecurity"));
    }

    /** 当前SQL依赖的最小领域结构；真实完整迁移链由宿主隔离库验证。 */
    private static void installDirectQueryDependencies() {
        execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY,status text NOT NULL,parent_tenant_id bigint,relation_revision bigint NOT NULL DEFAULT 0)");
        execute("CREATE TABLE nw_institution_relation_revisions(id bigint PRIMARY KEY,child_tenant_id bigint NOT NULL,parent_tenant_id bigint,revision bigint NOT NULL)");
        execute("CREATE TABLE nw_institution_integrations(tenant_id bigint NOT NULL,integration_id text NOT NULL,PRIMARY KEY(tenant_id,integration_id))");
        execute("CREATE TABLE nw_integration_business_scopes(tenant_id bigint NOT NULL,integration_id text NOT NULL,scope_id varchar(64) NOT NULL,status text NOT NULL,PRIMARY KEY(tenant_id,integration_id,scope_id))");
        execute("CREATE TABLE nw_agent_templates(id bigint PRIMARY KEY,status text NOT NULL,distribution_policy text NOT NULL,owner_kind text NOT NULL DEFAULT 'platform',owner_tenant_id bigint)");
        execute("CREATE TABLE nw_agent_definition_grants(grant_id bigint PRIMARY KEY,definition_id bigint NOT NULL,target_tenant_id bigint NOT NULL,status text NOT NULL,revision bigint NOT NULL,expires_at timestamptz,"
                +"actions text NOT NULL DEFAULT 'deploy,execute',owner_kind text NOT NULL DEFAULT 'platform',owner_tenant_id bigint,grantor_kind text NOT NULL DEFAULT 'platform',grantor_tenant_id bigint,"
                +"parent_grant_id bigint,parent_grant_revision bigint,relation_ref bigint,relation_revision bigint,allow_regrant boolean NOT NULL DEFAULT false,remaining_depth integer NOT NULL DEFAULT 0)");
        execute("CREATE TABLE nw_agent_definition_grant_revisions (LIKE nw_agent_definition_grants)");
        // 完整复用生产链判定函数，测试不实现另一套授权逻辑。
        try(var source=JdbcIdentityPolicyPortTest.class.getClassLoader().getResourceAsStream("db/migration/V304__agent_definition_grant_chains.sql")) {
            assertNotNull(source);String sql=new String(source.readAllBytes(),StandardCharsets.UTF_8);
            execute(sql.substring(sql.indexOf("CREATE FUNCTION public.agent_grant_chain_available"),sql.indexOf("GRANT SELECT,INSERT ON nw_agent_definition_grant_revisions")));
        } catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        execute("CREATE TABLE nw_agent_deployments(deployment_id bigint PRIMARY KEY,tenant_id bigint NOT NULL,integration_id text,agent_id bigint NOT NULL,definition_id bigint NOT NULL,grant_id bigint NOT NULL,grant_revision bigint NOT NULL,grant_chain jsonb NOT NULL)");
        execute("CREATE TABLE nw_chat_sessions(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,session_id text NOT NULL,integration_id text,business_scope_id varchar(64),UNIQUE(tenant_id,session_id))");
        execute("CREATE TABLE nw_runtime_agent_run_bindings(tenant_id text NOT NULL,run_id text NOT NULL,input_id text NOT NULL,session_id text NOT NULL,target_kind text NOT NULL,target_id text NOT NULL,end_user_id bigint NOT NULL,actor_agent_id text NOT NULL,role text NOT NULL,parent_run_id text,scope_snapshot jsonb NOT NULL,frozen_member jsonb NOT NULL,PRIMARY KEY(tenant_id,run_id))");
        execute("CREATE TABLE nw_integration_run_sources(tenant_id bigint NOT NULL,run_id text NOT NULL,parent_run_id text,integration_id text,business_scope_id varchar(64),agent_id bigint NOT NULL,session_id text NOT NULL,grant_id bigint,grant_revision bigint,PRIMARY KEY(tenant_id,run_id))");
    }
    /** 当前机构真实入口来源不需要Direct或默认所有者。 */
    private static void directSource(SessionOwner owner) {
        execute("INSERT INTO nw_institution_integrations VALUES(1,'integration') ON CONFLICT DO NOTHING");
        execute("INSERT INTO nw_integration_business_scopes VALUES(1,'integration','902','active') ON CONFLICT DO NOTHING");
        execute("INSERT INTO nw_chat_sessions VALUES(903,1,'"+owner.sessionRef().sessionId()+"','integration','902')");
    }
    /** 平台原授只绑定该目标机构，冻结Run保存其精确修订。 */
    private static void deployDirectAgent(long agent,long template) {
        execute("INSERT INTO nw_agent_templates(id,status,distribution_policy) VALUES("+template+",'published','AUTHORIZED_DEPLOY')");
        execute("INSERT INTO nw_agent_definition_grants(grant_id,definition_id,target_tenant_id,status,revision) VALUES("+template+","+template+",1,'active',1)");
        execute("INSERT INTO nw_agent_definition_grant_revisions SELECT * FROM nw_agent_definition_grants WHERE grant_id="+template);
        execute("INSERT INTO nw_agent_deployments VALUES("+agent+",1,'integration',"+agent+","+template+","+template+",1,'"+chain(template,template)+"'::jsonb)");
    }

    /** 知识执行与工作流新派发在自身一次 IAM 中核对原 Run 应用执行权，不另写session.execute审计。 */
    @Test void knowledgeAndWorkflowDispatchShareTheExactFrozenRunDirectCheck() {
        directSource(OWNER);
        deployDirectAgent(10,100);
        frozenRun(OWNER,"10","run",null,OWNER.sessionRef().sessionId());
        var systemPort=new JdbcIdentityPolicyPort(dataSource(),Map.of(SYSTEM,Set.of("sessions.write")));
        var authentication=systemPort.registerTrusted(SYSTEM,OWNER,"worker","session",Duration.ofMinutes(10));
        var actor=verified(systemPort.verify(request(authentication,OWNER,"worker","session")));
        var resources=new java.util.LinkedHashMap<String,TypedPayload>();
        resources.put("knowledge.execute",IdentityPolicyResources.knowledge(actor));
        var workflows=IdentityPolicyResources.workflows(actor);
        resources.put("workflow.dispatch",workflows.get("workflow.dispatch"));
        resources.put("workflow.resume",workflows.get("workflow.resume"));
        long before=scalar("SELECT count(*) FROM nw_runtime_security_audits WHERE operation='authorize'");
        resources.forEach((action,resource)->assertInstanceOf(AuthorizationResult.Allowed.class,
                systemPort.authorize(new AuthorizationRequest(actor,action,resource,"trace","span"))));
        assertEquals(before+3,scalar("SELECT count(*) FROM nw_runtime_security_audits WHERE operation='authorize'"));
        assertEquals(1,scalar("SELECT count(*) FROM nw_agent_definition_grants WHERE target_tenant_id=1 AND status='active'"));
        execute("UPDATE nw_agent_definition_grants SET status='revoked',revision=revision+1 WHERE target_tenant_id=1");
        assertEquals(0,scalar("SELECT count(*) FROM nw_agent_definition_grants WHERE target_tenant_id=1 AND status='active'"));
        resources.forEach((action,resource)->assertInstanceOf(AuthorizationResult.Denied.class,
                systemPort.authorize(new AuthorizationRequest(actor,action,resource,"trace","span"))));
        assertEquals(before+6,scalar("SELECT count(*) FROM nw_runtime_security_audits WHERE operation='authorize'"));
    }

    private static String chain(long definition,long grant) {
        return "{\"definition_id\":\""+definition+"\",\"owner_kind\":\"platform\",\"owner_tenant_id\":null,\"hops\":[{\"grant_id\":\""+grant
                +"\",\"revision\":1,\"grantor_kind\":\"platform\",\"grantor_tenant_id\":null,\"target_tenant_id\":\"1\",\"parent_grant_id\":null,\"parent_grant_revision\":null,\"relation_ref\":null,\"relation_revision\":null}]}";
    }

    private static void frozenRun(SessionOwner owner,String agent,String run,String parent,String productSession) {
        String parentSql=parent==null?"NULL":"'"+parent+"'";
        execute("INSERT INTO nw_runtime_agent_run_bindings VALUES('1','"+run+"','input:"+run+"','"+owner.sessionRef().sessionId()+"','"
                +owner.target().kind().name()+"','"+owner.target().id()+"',"+owner.requireUser().value()+",'"+agent+"','"
                +(parent==null?"PRIMARY":"DELEGATE")+"',"+parentSql+",'{\"origin_session_id\":\""+productSession
                +"\",\"team_revision\":1}','{\"agent_id\":\""+agent+"\",\"agent_definition_version\":\"1.0.0\"}')");
        execute("UPDATE nw_runtime_agent_run_bindings binding SET scope_snapshot=scope_snapshot||jsonb_build_object('authorizations',jsonb_build_object('"+agent+"',"
                +"jsonb_build_object('deployment_id',d.deployment_id,'deployment_revision',1,'grant_id',d.grant_id,'grant_revision',d.grant_revision,'grant_chain',d.grant_chain))) "
                +"FROM nw_agent_deployments d WHERE binding.tenant_id='1' AND binding.run_id='"+run+"' AND d.tenant_id=1 AND d.agent_id="+agent);
        execute("INSERT INTO nw_integration_run_sources SELECT 1,'"+run+"',"+parentSql+",'integration','902',"+agent+",'"+productSession+"',"
                +"(SELECT grant_id FROM nw_agent_deployments WHERE tenant_id=1 AND agent_id="+agent+"),"
                +"(SELECT grant_revision FROM nw_agent_deployments WHERE tenant_id=1 AND agent_id="+agent+")");
    }

    private static AuthorizationRequest execution(VerifiedIdentity identity,String agent,String run) {
        return new AuthorizationRequest(identity,"session.execute",SessionPolicyResource.run(runIdentity(identity,agent,run),Optional.empty()),"trace","span");
    }

    private static com.skillforge.runtime.api.port.RunIdentity runIdentity(VerifiedIdentity identity,String agent,String run) {
        return new com.skillforge.runtime.api.port.RunIdentity(identity.owner(),new AgentId(agent),new InputId("input:"+run),
                new com.skillforge.runtime.api.RunRef(TENANT,run),new TraceEnvelope("trace","span",new InputId("root"),Optional.empty()),identity.verificationRef(),
                new RunRequester.EndUser(identity.owner().requireUser()),Optional.empty(),new RunOriginRef(TENANT,"execution-input:"+run));
    }

    private static TypedPayload toolResource(com.skillforge.runtime.api.port.RunIdentity run) {
        var binding=new com.skillforge.runtime.api.port.ToolPortal.ToolBinding(
                com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.TEST_MOCK,"provider","tool");
        var target=new com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget(
                new com.skillforge.runtime.api.port.ToolPortal.ToolHandle("tool:mount"),
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.TOOL,"capability","version"),
                "deployment",3,new TypedPayload("input.v1","{}"),new TypedPayload("output.v1","{}"),
                com.skillforge.runtime.api.ToolEffect.READ_ONLY,false,Duration.ofSeconds(2),true,
                com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),true);
        return ToolPolicyResource.invocation(run,binding,target,new IdempotencyKey("key"));
    }

    private AuthenticationRef register(SessionOwner owner) {
        return port.registerTrusted(SERVICE, owner, "channel", "source", Duration.ofMinutes(5));
    }

    private static IdentityRequest request(AuthenticationRef ref, SessionOwner owner, String channel, String source) {
        return new IdentityRequest(ref, owner, channel, source, new RequestId("request"), Optional.of(new InputId("input")), "trace", "span");
    }

    private static AccessIdentityRequest access(AuthenticationRef ref, TenantId tenantId,
            AgentId agentId, EndUserId endUserId, String channel, String source) {
        return new AccessIdentityRequest(ref, tenantId, SessionTarget.agent(agentId),Optional.of(agentId),SessionSubject.user(endUserId), channel, source,
                new RequestId("request"), "trace", "span");
    }

    private static VerifiedIdentity verified(IdentityResult result) {
        return assertInstanceOf(IdentityResult.Verified.class, result).identity();
    }

    private static AuthorizationRequest authorization(VerifiedIdentity identity) {
        return new AuthorizationRequest(identity, "runtime.accept", resource(identity.owner(), "channel", "source"), "trace", "span");
    }

    private static TypedPayload resource(SessionOwner owner, String channel, String source) {
        var input=new InputId("input");
        var envelope=new InboundEnvelope(new AuthenticationRef("fixture"),Optional.empty(),owner.sessionRef(),owner.target(),owner.target().requireAgent(),
                owner.subject(),new RunRequester.EndUser(owner.requireUser()),input,new EventId("event"),InboundKind.MESSAGE,new RunTarget.NewRun(),channel,source,
                new TraceEnvelope("trace","span",input,Optional.empty()),new TypedPayload("input.v1","{}"),Instant.parse("2026-09-09T00:00:00Z"),
                Optional.empty(),new RunOriginRef(owner.sessionRef().tenantId(),"fixture-origin"));
        return AdmissionResource.encode(envelope);
    }

    private static TypedPayload facadeResource(String action, String targetKind,
            String targetId, String cursor, String operation) {
        return new TypedPayload("skillforge.runtime.facade-access.v1",
                "[\"1\",\"AGENT\",\"10\",\"session\",\"20\",\"" + action
                        + "\",\"" + targetKind + "\",\"" + targetId + "\",\""
                        + cursor + "\",\"" + operation + "\"]");
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static JdbcIdentityPolicyPort newPort() { return new JdbcIdentityPolicyPort(dataSource(), Map.of()); }
    private static AuthorizationResult authorizeOnce(JdbcIdentityPolicyPort identities,
            VerifiedIdentity identity, List<String> statements) {
        statements.clear();
        var result = identities.authorize(authorization(identity));
        var executed = statements.stream()
                .map(statement -> statement.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT))
                .toList();
        assertTrue(executed.size() == 3 || executed.size() == 4, statements.toString());
        assertEquals(1, executed.stream().filter(sql -> sql.contains("agent_runtime_authorization_current")).count());
        if (executed.size() == 4) {
            assertTrue(executed.get(2).contains("nw_agent_teams") && executed.get(2).contains("nw_runtime_host_owners"),
                    "the only additional read checks the actual Agent against the current owner");
        }
        assertTrue(executed.get(1).contains("with runtime_tenant as materialized"),
                statements.toString());
        assertTrue(executed.getFirst().contains("set_config('app.tenant_id'"),
                statements.toString());
        assertTrue(executed.get(1).contains("agent_runtime_authorization_current"),
                statements.toString());
        assertFalse(executed.get(1).contains("set_config"), "the authorization query must not change transaction tenant");
        assertTrue(executed.getLast().startsWith(
                "insert into public.nw_runtime_security_audits"), statements.toString());
        return result;
    }
    /** 与执行Store相同的MyBatis注册方式，身份映射进入同一业务SqlSession。 */
    private static org.apache.ibatis.session.SqlSessionFactory joinedSessions(DataSource source) {
        var config = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                "joined-identity-test", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true); config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        JdbcIdentityPolicyPort.configure(config); return new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
    }
    /** 核对未提交审计只在所属实际连接内可见。 */
    private static long joinedScalar(org.apache.ibatis.session.SqlSession session, String sql) throws SQLException {
        try (var query = session.getConnection().createStatement(); var rows = query.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private static DataSource countingDataSource(DataSource delegate, List<String> statements) {
        return observedDataSource(delegate, statements, null, null, null);
    }
    private static DataSource observedDataSource(DataSource delegate, List<String> statements,
            AtomicInteger connections, List<Integer> isolations, AtomicBoolean failCommit) {
        return (DataSource) Proxy.newProxyInstance(JdbcIdentityPolicyPortTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, arguments) -> {
                    var result = invoke(delegate, method, arguments);
                    if (!(result instanceof Connection connection)) return result;
                    if (connections != null) connections.incrementAndGet();
                    return countingConnection(connection, statements, isolations, failCommit);
                });
    }
    private static Connection countingConnection(Connection delegate, List<String> statements,
            List<Integer> isolations, AtomicBoolean failCommit) {
        return (Connection) Proxy.newProxyInstance(JdbcIdentityPolicyPortTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if ("prepareStatement".equals(method.getName()) && arguments != null
                            && arguments.length > 0 && arguments[0] instanceof String statement) {
                        statements.add(statement);
                        if (isolations != null) isolations.add(delegate.getTransactionIsolation());
                    }
                    if ("commit".equals(method.getName()) && failCommit != null
                            && failCommit.compareAndSet(true, false)) {
                        throw new SQLException("injected authorization commit failure");
                    }
                    return invoke(delegate, method, arguments);
                });
    }
    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
    private static DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(ROLE); source.setPassword(PASSWORD); return source;
    }
    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private static void execute(String sql) {
        try (var connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); }
        catch (SQLException failure) { throw new IllegalStateException(failure); }
    }
    private static long count(String table) { return scalar("SELECT count(*) FROM " + table); }
    private static long scalar(String sql) {
        try (var connection = admin(); var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            assertTrue(row.next()); return row.getLong(1);
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }
}
