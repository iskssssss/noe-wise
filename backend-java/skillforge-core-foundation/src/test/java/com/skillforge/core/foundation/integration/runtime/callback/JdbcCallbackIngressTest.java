package com.skillforge.core.foundation.integration.runtime.callback;

import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.runtime.adapter.jdbc.callback.JdbcCallbackRegistrationPort;


import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 IAM、加密签名密钥、入口证明和 callback registry 交接的 PostgreSQL 闭环。 */
@Testcontainers(disabledWithoutDocker = false)
class JdbcCallbackIngressTest {
    private static final String ROLE = "callback_ingress_test", PASSWORD = "callback_ingress_test_password";
    private static final TenantId TENANT = new TenantId("1");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("10"), new EndUserId(20));
    private static final PrincipalRef.ServicePrincipal SERVICE = new PrincipalRef.ServicePrincipal(TENANT, "service", "integration", 7);
    private static final PrincipalRef.SystemPrincipal SYSTEM = new PrincipalRef.SystemPrincipal(TENANT, "callback-worker");
    private static final CallbackRef CALLBACK = new CallbackRef(TENANT, "callback");
    private static final CallbackDeliveryRef DELIVERY = new CallbackDeliveryRef(TENANT, "delivery");
    private static final byte[] BODY = "[\"1\",\"execution\",\"run\",\"action\",\"7\",\"result.v1\",\"{\\\"message\\\":\\\"private-body-你好\\\"}\"]".getBytes(StandardCharsets.UTF_8);
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("ingress_admin").withPassword("ingress_admin_password");
    private byte[] master, signing;
    private JdbcIdentityPolicyPort identities;
    private JdbcCallbackIngress ingress;
    private JdbcCallbackRegistrationPort registry;
    private RegisterCallback registration;

    @BeforeAll
    static void initializeDatabase() throws Exception {
        com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase.migrate(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO " + ROLE);
        execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO " + ROLE);
        execute("GRANT EXECUTE ON FUNCTION resolve_runtime_authentication_tenant(text) TO " + ROLE);
    }

    @BeforeEach
    void setup() throws Exception {
        // 余额使用ROOT/RUN等多态scope，没有指向根表的FK；必须显式重置，不能依赖CASCADE。
        execute("TRUNCATE agent_runtime_callback_ingress_proofs,agent_runtime_callback_registration_operations,agent_runtime_callback_delivery_operations,agent_runtime_callback_deliveries,agent_runtime_callback_registrations,agent_runtime_callback_audits,nw_runtime_authentication_facts,nw_runtime_identity_verifications,nw_runtime_security_audits,nw_service_principals,nw_agents,nw_end_users,agent_runtime_sessions,agent_runtime_session_owners,agent_runtime_execution_sessions,agent_runtime_execution_materials,agent_runtime_budget_roots,agent_runtime_budget_balances CASCADE");
        master = random(32); signing = random(32); byte[] iv = random(12);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(signing); byte[] wire = Arrays.copyOf(iv, iv.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, wire, iv.length, ciphertext.length);
        try (var connection = admin()) {
            com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures.servicePrincipal(connection,1,1,"service","integration",7,
                    "[\"sessions.write\"]", Base64.getEncoder().encodeToString(wire));
            com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures.agent(connection,1,10);
            com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures.endUser(connection,1,20);
        }
        execute("INSERT INTO agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) VALUES('1','AGENT','10','session',20)");
        execute("INSERT INTO agent_runtime_sessions(tenant_id,target_kind,target_id,session_id,end_user_id,channel,scope_key,persona_version,create_fingerprint,create_command_data,status,session_revision,created_epoch_second,created_nano,updated_epoch_second,updated_nano) "
                + "VALUES('1','AGENT','10','session',20,'web','open-platform:integration','persona',repeat('a',64),'fixture','CREATED',1,0,0,0,0)");
        identities = new JdbcIdentityPolicyPort(dataSource(), Map.of(SYSTEM, Set.of("sessions.write")));
        var authentication = identities.registerTrusted(SERVICE, OWNER, "web", "source", Duration.ofMinutes(10));
        var requester = assertInstanceOf(IdentityPolicyPort.IdentityResult.Verified.class, identities.verify(new IdentityPolicyPort.IdentityRequest(authentication, OWNER,
                "web", "source", new RequestId("register"), Optional.empty(), "trace", "span"))).identity();
        var reference = new RunRef(TENANT,"run");
        var cause = new InputId("cause");
        var trace = new TraceEnvelope("trace","span",cause,Optional.empty());
        var run = new RunIdentity(OWNER, OWNER.target().requireAgent(), cause, reference, trace, requester.verificationRef(), new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), cause.value()));
        var input = new ExecutionStore.Material(new ResultRef("callback-ingress-input"),new TypedPayload("fixture.callback-input","{}"));
        var context = new ExecutionStore.Material(new ResultRef("callback-ingress-context"),new TypedPayload("fixture.callback-context","{}"));
        var capacity = new ExecutionBudgetPort.Resources(1,1,1,1,1_000,100,1_100);
        var budget = new ExecutionBudgetPort.Registration(reference,reference,ExecutionBudgetPort.Role.PRIMARY, new com.skillforge.runtime.api.port.ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD,capacity,capacity,capacity,60,60,"callback-ingress:v1")));
        assertTrue(new JdbcExecutionStore(dataSource(),identities,(session,state,actor,prepared) -> true).admit(new ExecutionStore.Admission(
                requester,run,"callback-ingress",new ResultRef("callback-ingress-profile"),input,context,budget,List.of(),List.of())).created());
        var draft = new CallbackRegistrationDraft(CALLBACK, OWNER,
                new CallbackTarget.ExecutionTarget(new ExecutionRef(TENANT,"execution"),reference,"action",7), "source", CallbackPayloads.EXECUTION_SCHEMA,
                CallbackPayloads.SIGNATURE_POLICY, JdbcCallbackSigningKeys.reference(SERVICE));
        registration = new RegisterCallback(draft, requester, Optional.of(run), cause,
                trace, requester.verificationRef(), new IdempotencyKey("registration"), "registration-fp");
        ingress = newIngress(); registry = newRegistry(ingress);
        assertInstanceOf(CallbackRegisterResult.Registered.class, registry.register(registration));
    }

    @Test
    void authenticatedIngressProofSurvivesRebuildAndChangingHttpTimestampKeepsOriginalCommand() throws Exception {
        String time = timestamp(0);
        var first = ingress.verify(CALLBACK, DELIVERY, time, signature(DELIVERY, time, BODY), BODY);
        assertTrue(ingress.verifyProof(registry.find(CALLBACK).orElseThrow(), first));
        assertInstanceOf(CallbackDeliveryResult.Ready.class, registry.acceptVerified(first));
        long authentications = count("nw_runtime_authentication_facts");
        String later = Long.toString(Long.parseLong(time) + 1);
        var rebuilt = newIngress();
        var replay = rebuilt.verify(CALLBACK, DELIVERY, later, signature(DELIVERY, later, BODY), BODY);
        assertEquals(first, replay); assertEquals(first.envelope().authenticationRef(), replay.envelope().authenticationRef());
        assertEquals(first.verifiedAt(), replay.verifiedAt()); assertEquals(authentications, count("nw_runtime_authentication_facts"));
        assertTrue(rebuilt.verifyProof(newRegistry(rebuilt).find(CALLBACK).orElseThrow(), replay));
        assertInstanceOf(CallbackDeliveryResult.Replayed.class, newRegistry(rebuilt).acceptVerified(replay));
        assertEquals(1, count("agent_runtime_callback_ingress_proofs"));
        assertEquals(1, count("agent_runtime_callback_deliveries"));
    }

    @Test
    void forgedDtoCannotBypassProofAndDoesNotPersistItsEnvelope() throws Exception {
        String time = timestamp(0);
        var verified = ingress.verify(CALLBACK, DELIVERY, time, signature(DELIVERY, time, BODY), BODY);
        var forgedRef = new CallbackDeliveryRef(TENANT, "forged");
        var forged = new VerifiedCallbackDelivery(CALLBACK, verified.expectedRegistrationRevision(), forgedRef, verified.requestFingerprint(), verified.envelope(), verified.verifiedAt());
        assertFalse(ingress.verifyProof(registry.find(CALLBACK).orElseThrow(), forged));
        var rejected = assertInstanceOf(CallbackDeliveryResult.Rejected.class, registry.acceptVerified(forged)).receipt();
        assertTrue(rejected.envelope().isEmpty());
        assertEquals(1, scalar("SELECT count(*) FROM agent_runtime_callback_deliveries WHERE delivery_id='forged' AND status='REJECTED' AND envelope_data IS NULL"));
        var changedTime = new VerifiedCallbackDelivery(CALLBACK, verified.expectedRegistrationRevision(), DELIVERY, verified.requestFingerprint(), verified.envelope(), verified.verifiedAt().plusNanos(1));
        assertFalse(ingress.verifyProof(registry.find(CALLBACK).orElseThrow(), changedTime));
        assertInstanceOf(CallbackDeliveryResult.Ready.class, registry.acceptVerified(verified));
    }

    @Test
    void invalidSignatureSchemaTimestampAndChangedBodyCannotCreateOrReplaceProof() throws Exception {
        String time = timestamp(0); String signature = signature(DELIVERY, time, BODY);
        assertThrows(RuntimeException.class, () -> ingress.verify(CALLBACK, DELIVERY, time, "0".repeat(64), BODY));
        String expired = timestamp(-600);
        assertThrows(RuntimeException.class, () -> ingress.verify(CALLBACK, DELIVERY, expired, signature(DELIVERY, expired, BODY), BODY));
        byte[] wrongSchema = "[\"1\",\"execution\",\"run\",\"action\",\"7\",\"unregistered.v1\",\"{}\"]".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> ingress.verify(CALLBACK, DELIVERY, time, signature(DELIVERY, time, wrongSchema), wrongSchema));
        byte[] changed = "[\"1\",\"execution\",\"run\",\"action\",\"7\",\"result.v1\",\"{}\"]".getBytes(StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> ingress.verify(CALLBACK, DELIVERY, time, signature, changed));
        assertEquals(0, count("agent_runtime_callback_ingress_proofs"));
        ingress.verify(CALLBACK, DELIVERY, time, signature, BODY);
        assertThrows(RuntimeException.class, () -> ingress.verify(CALLBACK, DELIVERY, time, signature(DELIVERY, time, changed), changed));
        assertEquals(1, count("agent_runtime_callback_ingress_proofs"));
    }

    @Test
    void proofPersistsOnlyDigestMetadataAndRestrictedRoleCannotReadForeignTenant() throws Exception {
        String time = timestamp(0); ingress.verify(CALLBACK, DELIVERY, time, signature(DELIVERY, time, BODY), BODY);
        try (var connection = admin(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='agent_runtime_callback_ingress_proofs'")) {
                Set<String> columns = new HashSet<>(); while (rows.next()) columns.add(rows.getString(1));
                assertEquals(Set.of("tenant_id", "callback_id", "delivery_id", "fingerprint", "authentication_ref", "command_digest", "registration_revision", "verified_at"), columns);
            }
            try (var row = statement.executeQuery("SELECT row_to_json(p)::text FROM agent_runtime_callback_ingress_proofs p")) {
                assertTrue(row.next()); assertFalse(row.getString(1).contains("private-body"));
            }
        }
        try (var connection = dataSource().getConnection(); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(row.next()); assertFalse(row.getBoolean(1)); assertFalse(row.getBoolean(2));
            }
            statement.execute("SELECT set_config('app.tenant_id','foreign',false)");
            try (var row = statement.executeQuery("SELECT count(*) FROM agent_runtime_callback_ingress_proofs")) { assertTrue(row.next()); assertEquals(0, row.getLong(1)); }
        }
    }

    private JdbcCallbackIngress newIngress() {
        return new JdbcCallbackIngress(dataSource(), new JdbcCallbackSigningKeys(dataSource(), this::decrypt), identities,
                tenant -> { if (!tenant.equals(TENANT)) throw new IllegalArgumentException("unknown test tenant"); return SYSTEM; },
                payload -> payload.schemaId().equals("result.v1"), Duration.ofSeconds(60), Duration.ofMinutes(10), 8192);
    }
    private JdbcCallbackRegistrationPort newRegistry(JdbcCallbackIngress ingress) {
        // 仅本测试显式信任这一个注册命令；生产注册策略由独立集成测试覆盖。
        return new JdbcCallbackRegistrationPort(dataSource(), registration::equals, (record, command) -> false, ingress::verifyProof);
    }
    private String signature(CallbackDeliveryRef delivery, String time, byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(signing, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(CallbackPayloads.signingBytes(CALLBACK, delivery, time, body)));
    }
    private byte[] decrypt(String encrypted) {
        try {
            byte[] wire = Base64.getDecoder().decode(encrypted); var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, Arrays.copyOf(wire, 12)));
            return cipher.doFinal(wire, 12, wire.length - 12);
        } catch (Exception failure) { throw new IllegalStateException("test_decryption_failed", failure); }
    }
    private static String timestamp(long seconds) { return Long.toString(scalar("SELECT floor(extract(epoch FROM clock_timestamp()))::bigint") + seconds); }
    private static byte[] random(int size) { var value = new byte[size]; new SecureRandom().nextBytes(value); return value; }
    private static long count(String table) { return scalar("SELECT count(*) FROM " + table); }
    private static long scalar(String sql) {
        try (var connection = admin(); var statement = connection.createStatement(); var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getLong(1); }
        catch (SQLException failure) { throw new IllegalStateException(failure); }
    }
    private static DataSource dataSource() { var value = new PGSimpleDataSource(); value.setURL(POSTGRES.getJdbcUrl()); value.setUser(ROLE); value.setPassword(PASSWORD); return value; }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private static void execute(String sql) { try (var connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
}
