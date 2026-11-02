package com.skillforge.runtime.adapter.jdbc.model;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.ModelResolutionPort.*;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 使用真实 PostgreSQL 验证原配置发布、重建 exact 读取、并发版本、租户隔离及无网络解析。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcModelBindingsTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final AgentId AGENT = new AgentId("11");
    private static final RequestedModelStrategy STRATEGY = new RequestedModelStrategy("chat", "v1");
    private static final JdbcModelBindings.Profile PROFILE = new JdbcModelBindings.Profile(STRATEGY,
            Set.of(ResponseFormatKind.TEXT), true, false, Duration.ofSeconds(30));
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("model_admin").withPassword("model_admin_password");
    private JdbcModelBindings bindings;

    @BeforeAll static void initializeDatabase() throws Exception {
        com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase.migrate(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("CREATE ROLE model_reader LOGIN PASSWORD 'model_reader_password' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO model_reader");
        execute("GRANT SELECT,INSERT,UPDATE,DELETE ON agent_runtime_model_bindings TO model_reader");
    }

    @BeforeEach void prepare() {
        execute("TRUNCATE agent_runtime_model_bindings");
        bindings = publishingBindings();
    }

    @Test void freezesWholeSourceAndReplaysSamePublicationAcrossRebuild() {
        var first = publish(Optional.of(AGENT));
        assertEquals("agent-model", first.target().effectiveModelId());
        assertEquals("encrypted-model", first.encryptedCredential());
        assertEquals("custom-openai-compatible", first.providerProfile().serviceId());
        assertEquals("http://localhost:18080/v1", first.endpoint());
        assertEquals("platform-model:agent-model", first.sourceReference());
        assertEquals(1, first.target().binding().revision());
        assertEquals(first, publishingBindings().publish(TENANT, "chat-binding", Optional.of(AGENT), PROFILE, source("agent-model")));
        var second = bindings.publish(TENANT, "chat-binding", Optional.of(AGENT), PROFILE, source("changed-model"));
        assertEquals(2, second.target().binding().revision());
        assertEquals(first, new JdbcModelBindings(dataSource()).find(first.target().binding()).orElseThrow());
        assertNotEquals(first.target().resolutionRef(), second.target().resolutionRef());
        assertFalse(first.toString().contains("encrypted-agent")); assertFalse(first.toString().contains("encrypted-model"));
        assertFalse(first.toString().contains("https://")); assertFalse(first.toString().contains("http://"));
    }

    @Test void freezesExplicitImagePolicyAndRebuildsItWithoutConsultingCurrentModels() {
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4,
                1024, 8192, 2048, 512, 85, 170, "vision.v1");
        var profile = new JdbcModelBindings.Profile(STRATEGY, Set.of(ResponseFormatKind.TEXT),
                true, false, Duration.ofSeconds(30), policy);
        var first = bindings.publish(TENANT, "vision-binding", Optional.of(AGENT), profile, source("vision-model"));
        assertEquals(policy, first.target().capabilities().imagePolicy());
        assertEquals(first, new JdbcModelBindings(dataSource()).find(first.target().binding()).orElseThrow());
        var text = bindings.publish(TENANT, "vision-binding", Optional.of(AGENT), PROFILE, source("vision-model"));
        assertEquals(2, text.target().binding().revision());
        assertFalse(text.target().capabilities().imagePolicy().enabled());
        assertEquals(policy, bindings.find(first.target().binding()).orElseThrow().target().capabilities().imagePolicy());
    }

    /** 真数据库冻结原能力，后续目录/配置变化只能生成新 revision，不能改变原目标。 */
    @Test void freezesPrefixCapabilitiesInExactRevisionAndDigest() {
        var prefix = new PromptPrefixCapabilities(CapabilitySupportStatus.UNVERIFIED,
                ToolUpdateMode.NONE, CapabilitySupportStatus.SUPPORTED);
        var trusted = new JdbcModelBindings(dataSource(), (configuration, profile) -> { });
        var profile = new JdbcModelBindings.Profile(STRATEGY, Set.of(ResponseFormatKind.TEXT),
                true, false, Duration.ofSeconds(30), prefix);
        var first = trusted.publish(TENANT, "prefix-binding", Optional.of(AGENT), profile, source("model"));
        assertEquals(prefix, first.target().capabilities().prefixCapabilities());
        assertEquals(first, new JdbcModelBindings(dataSource()).find(first.target().binding()).orElseThrow());
        var second = trusted.publish(TENANT, "prefix-binding", Optional.of(AGENT), PROFILE, source("model"));
        assertEquals(2, second.target().binding().revision());
        assertNotEquals(first.target().resolutionRef(), second.target().resolutionRef());
        assertEquals(PromptPrefixCapabilities.unverified(), second.target().capabilities().prefixCapabilities());
        assertEquals(prefix, new JdbcModelBindings(dataSource()).find(first.target().binding()).orElseThrow()
                .target().capabilities().prefixCapabilities());
    }

    @Test void sourceIsExplicitAndAgentSelectionDoesNotReadHostBusinessTables() {
        var source = source("tenant-selected-model");
        var binding = bindings.publish(TENANT, "chat-binding", Optional.of(new AgentId("12")), PROFILE, source);
        assertEquals("tenant-selected-model", binding.target().effectiveModelId());
        assertThrows(IllegalArgumentException.class, () -> bindings.publish(TENANT, "other", Optional.of(AGENT), PROFILE,
                new JdbcModelBindings.Source("model:missing", source.configuration(), "")));
        assertEquals(1, count());
    }

    @Test void changedTrustedSourceCreatesNewPublicationWithoutRewritingHistoricalRevision() {
        var first = publish(Optional.empty());
        var second = bindings.publish(TENANT, "chat-binding", Optional.empty(), PROFILE, source("new-selected-model"));
        assertEquals(2, second.target().binding().revision());
        assertEquals(first, bindings.find(first.target().binding()).orElseThrow());
        assertTrue(bindings.find(new ModelBindingRef(TENANT, "chat-binding", 99)).isEmpty());
        assertTrue(bindings.find(new ModelBindingRef(new TenantId("2"), "chat-binding", 1)).isEmpty());
    }

    @Test void databaseRejectsHistoricalBindingWithoutProviderProfile() throws Exception {
        try (var connection = admin(); var statement = connection.createStatement()) {
            var rejected = assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO agent_runtime_model_bindings"
                            + "(tenant_id,binding_id,revision,agent_id,source_kind,source_id,"
                            + "content_digest,endpoint,model_id,encrypted_credential,strategy_id,"
                            + "strategy_version,response_formats,streaming,reasoning,timeout,"
                            + "binding_format_version) VALUES"
                            + "('1','legacy-binding',1,NULL,'TENANT','1',repeat('0',64),"
                            + "'https://legacy.test/v1','legacy-model','encrypted-legacy','chat',"
                            + "'v1','JSON_OBJECT,TEXT',true,false,'PT30S',1)"));
            assertEquals("23502", rejected.getSQLState());
        }
        assertEquals(0, count());
    }

    @Test void publicationRequiresAnExplicitSdkProviderProfileValidator() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new JdbcModelBindings(dataSource()).publish(
                        TENANT, "unvalidated-binding", Optional.empty(), PROFILE, source("unvalidated")));

        assertEquals("model_provider_validation_required", failure.getMessage());
        assertEquals(0, count());
    }

    @Test void profileChangesAndValidatedReasoningCapabilityRoundTripExactly() {
        var first = publish(Optional.empty());
        var other = new JdbcModelBindings.Profile(new RequestedModelStrategy("chat", "v2"),
                Set.of(ResponseFormatKind.TEXT), false, false, Duration.ofSeconds(15));
        var second = bindings.publish(TENANT, "chat-binding", Optional.empty(), other, source("agent-model"));
        assertEquals(2, second.target().binding().revision()); assertEquals(Duration.ofSeconds(15), second.target().timeout());
        assertEquals(PROFILE.timeout(), bindings.find(first.target().binding()).orElseThrow().target().timeout());
        var reasoning = bindings.publish(TENANT, "reasoning", Optional.empty(),
                new JdbcModelBindings.Profile(STRATEGY, Set.of(ResponseFormatKind.TEXT), true, true, Duration.ofSeconds(1)), source("agent-model"));
        assertTrue(reasoning.target().capabilities().reasoning());
        assertEquals(reasoning, publishingBindings().find(reasoning.target().binding()).orElseThrow());
        assertEquals(3, count());
    }

    @Test void concurrentIdenticalPublicationHasSingleRevision() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<JdbcModelBindings.Binding> task = () -> { start.await(); return publishingBindings()
                    .publish(TENANT, "chat-binding", Optional.of(AGENT), PROFILE, source("agent-model")); };
            var a = pool.submit(task); var b = pool.submit(task); start.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)); assertEquals(1, count());
        }
    }

    @Test void immutableRowsAndRestrictedRoleRlsBlockCrossTenantReadAndMutation() throws Exception {
        publish(Optional.of(AGENT));
        try (var connection = dataSource().getConnection(); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(row.next()); assertFalse(row.getBoolean(1)); assertFalse(row.getBoolean(2));
            }
            statement.execute("SELECT set_config('app.tenant_id','2',false)");
            try (var row = statement.executeQuery("SELECT count(*) FROM agent_runtime_model_bindings")) { assertTrue(row.next()); assertEquals(0, row.getInt(1)); }
            statement.execute("SELECT set_config('app.tenant_id','1',false)");
            assertThrows(SQLException.class, () -> statement.execute("UPDATE agent_runtime_model_bindings SET model_id='replace'"));
            assertThrows(SQLException.class, () -> statement.execute("DELETE FROM agent_runtime_model_bindings"));
            statement.execute("SELECT set_config('app.tenant_id','2',false)");
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO agent_runtime_model_bindings"
                    + "(tenant_id,binding_id,revision,agent_id,source_kind,source_id,content_digest,endpoint,model_id,encrypted_credential,strategy_id,strategy_version,response_formats,streaming,reasoning,timeout,image_policy,binding_format_version,provider_id,service_id,profile_version,protocol,authentication_scheme,dialect_version,system_updates_in_history,tool_update_mode,retain_tools_with_tool_choice_none)"
                    + " VALUES('1','bad',1,NULL,'HOST','platform-model:test',repeat('0',64),'https://x.test','m','e','s','1','TEXT',false,false,'PT1S','{\"enabled\":false,\"toolImages\":false,\"maxImages\":0,\"maxImageBytes\":0,\"maxRequestBytes\":0,\"maxDimension\":0,\"tileSize\":0,\"baseTokens\":0,\"tileTokens\":0,\"version\":\"text-only.v1\"}',4,'custom','custom-openai-compatible','custom-v1','openai-chat-completions','bearer-api-key','openai-compatible-chat-v1','UNVERIFIED','UNVERIFIED','UNVERIFIED')"));
        }
    }

    @Test void failedInsertRollsBackAndOwnerGuardRetainsOriginalAgentEvenOnTenantFallback() {
        execute("CREATE FUNCTION model_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected'; END; $$");
        execute("CREATE TRIGGER model_test_fail BEFORE INSERT ON agent_runtime_model_bindings FOR EACH ROW EXECUTE FUNCTION model_test_fail()");
        try { assertThrows(IllegalStateException.class, () -> publish(Optional.empty())); assertEquals(0, count()); }
        finally { execute("DROP TRIGGER model_test_fail ON agent_runtime_model_bindings"); execute("DROP FUNCTION model_test_fail()"); }
        var binding = publish(Optional.of(new AgentId("12")));
        assertEquals(Optional.of(binding), bindings.findForOwner(binding.target().binding(), new AgentId("12")));
        assertTrue(bindings.findForOwner(binding.target().binding(), new AgentId("11")).isEmpty());
    }

    @Test void ownedReadUsesOnePostgresQueryAndConnectionInsteadOfSeparateOwnerAndValueReads() {
        var original=publish(Optional.of(AGENT));
        var probe=new BindingReadProbe(dataSource());
        var storage=new JdbcModelBindings(probe.source());
        // 负控实际执行原先两次独立原行读取；计量必须同时捕获两条 SELECT 和两个连接。
        assertEquals(original,storage.find(original.target().binding()).orElseThrow());
        assertEquals(original,storage.find(original.target().binding()).orElseThrow());
        assertEquals(2,probe.queries.get());assertEquals(2,probe.connections.get());
        probe.reset();
        assertEquals(Optional.of(original),storage.findForOwner(original.target().binding(),AGENT));
        assertEquals(1,probe.queries.get());assertEquals(1,probe.connections.get());
        probe.reset();
        var resolver=new JdbcModelResolutionPort(storage,request->true);
        assertEquals(original.target(),assertInstanceOf(ResolutionResult.Resolved.class,
                resolver.resolve(request(original.target().binding(),STRATEGY))).target());
        assertEquals(1,probe.queries.get(),"解析消费者必须只读一次完整原绑定");
        assertEquals(1,probe.connections.get());
    }

    @Test void ownedReadKeepsAgentTenantAndExactRevisionBoundariesAndTenantWideBindings() {
        var original=publish(Optional.of(AGENT));
        var reference=original.target().binding();
        assertEquals(Optional.of(original),bindings.findForOwner(reference,AGENT));
        assertTrue(bindings.findForOwner(reference,new AgentId("12")).isEmpty());
        assertTrue(bindings.findForOwner(new ModelBindingRef(new TenantId("2"),reference.bindingId(),reference.revision()),AGENT).isEmpty());
        assertTrue(bindings.findForOwner(new ModelBindingRef(TENANT,reference.bindingId(),99),AGENT).isEmpty());
        var tenantBinding=bindings.publish(TENANT,"tenant-wide",Optional.empty(),PROFILE,source("tenant-model"));
        assertEquals(Optional.of(tenantBinding),bindings.findForOwner(tenantBinding.target().binding(),AGENT));
        assertEquals(Optional.of(tenantBinding),bindings.findForOwner(tenantBinding.target().binding(),new AgentId("12")));
        var second=bindings.publish(TENANT,reference.bindingId(),Optional.of(AGENT),PROFILE,source("next-model"));
        assertEquals(2,second.target().binding().revision());
        assertEquals(Optional.of(original),bindings.findForOwner(reference,AGENT),"发布后仍读取精确旧版本");
        var resolver=new JdbcModelResolutionPort(bindings,request->true);
        var wrongOwner=bindings.publish(TENANT,"wrong-owner",Optional.of(new AgentId("12")),PROFILE,source("other-model"));
        for(var unavailable:List.of(wrongOwner.target().binding(),new ModelBindingRef(TENANT,reference.bindingId(),99))){
            assertEquals("model_binding_unavailable",assertInstanceOf(ResolutionResult.Rejected.class,
                    resolver.resolve(request(unavailable,STRATEGY))).failure().reasonCode());
        }
        assertEquals("model_binding_unavailable",assertInstanceOf(ResolutionResult.Rejected.class,
                resolver.resolve(request(reference,new RequestedModelStrategy("chat","wrong")))).failure().reasonCode());
    }

    @Test void ownedReadRestoresAndRejectsInvalidRowBeforeReturningEvenForWrongAgent() {
        var original=publish(Optional.of(AGENT));
        var probe=new BindingReadProbe(dataSource());probe.corruptDigest=true;
        var storage=new JdbcModelBindings(probe.source());
        assertThrows(IllegalStateException.class,()->storage.findForOwner(original.target().binding(),AGENT));
        assertThrows(IllegalStateException.class,()->storage.findForOwner(original.target().binding(),new AgentId("12")),
                "先完整 restore 原行，不能用归属不符掩盖损坏来源");
        var resolver=new JdbcModelResolutionPort(storage,request->true);
        var rejected=assertInstanceOf(ResolutionResult.Rejected.class,resolver.resolve(request(original.target().binding(),STRATEGY)));
        assertEquals("model_resolution_unavailable",rejected.failure().reasonCode());
        assertFalse(rejected.toString().contains("encrypted-model"));
        probe.reset();
        assertEquals("model_resolution_denied",assertInstanceOf(ResolutionResult.Rejected.class,
                new JdbcModelResolutionPort(storage,request->false).resolve(request(original.target().binding(),STRATEGY))).failure().reasonCode());
        assertEquals(0,probe.connections.get(),"当前授权拒绝仍先于绑定读取");
    }

    /** 真实 PostgreSQL 读计量；可仅改写返回摘要列模拟坏行，不修改不可变持久数据。 */
    private static final class BindingReadProbe {
        /** 测试容器的受限数据库角色。 */
        private final DataSource delegate;
        /** 实际申请的数据库连接数。 */
        private final AtomicInteger connections=new AtomicInteger();
        /** 精确版本绑定 SELECT 的实际执行次数。 */
        private final AtomicInteger queries=new AtomicInteger();
        /** 仅影响 SELECT 返回的摘要，保留数据库原行。 */
        private boolean corruptDigest;

        /** 每个测试使用独立计数，不跨测试保留连接或凭据。 */
        private BindingReadProbe(DataSource delegate){this.delegate=delegate;}
        /** 仅清除计数，不改变坏行负控开关。 */
        private void reset(){connections.set(0);queries.set(0);}
        /** 包装实际 JDBC 调用；目标查询仍由 PostgreSQL 执行。 */
        private DataSource source(){
            return (DataSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DataSource.class},(proxy,method,args)->{
                        var value=invoke(method,delegate,args);
                        if(!(value instanceof Connection connection))return value;
                        connections.incrementAndGet();
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{Connection.class},(connectionProxy,operation,parameters)->{
                                    boolean bindingQuery=operation.getName().equals("prepareStatement")&&parameters!=null
                                            &&parameters[0] instanceof String sql&&sql.contains("FROM public.agent_runtime_model_bindings")
                                            &&sql.contains("AND revision =");
                                    if(bindingQuery&&corruptDigest){
                                        parameters=parameters.clone();
                                        parameters[0]=((String)parameters[0]).replace("content_digest, endpoint", "repeat('0',64) AS content_digest, endpoint");
                                    }
                                    var result=invoke(operation,connection,parameters);
                                    if(!bindingQuery||!(result instanceof PreparedStatement statement))return result;
                                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                            new Class<?>[]{PreparedStatement.class},(statementProxy,call,values)->{
                                                if(call.getName().equals("execute")||call.getName().equals("executeQuery"))queries.incrementAndGet();
                                                return invoke(call,statement,values);
                                            });
                                });
                    });
        }
        /** 透传 JDBC 异常，避免反射包装改变适配器拒绝路径。 */
        private static Object invoke(java.lang.reflect.Method method,Object target,Object[] args)throws Throwable{
            try{return method.invoke(target,args);}
            catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        }
    }

    @Test void resolutionRequiresCurrentGuardExactStrategyAndOriginalFrozenTarget() {
        var binding = publish(Optional.of(AGENT));
        var requested = request(binding.target().binding(), STRATEGY);
        var calls = new AtomicInteger();
        var resolver = new JdbcModelResolutionPort(bindings, request -> { calls.incrementAndGet(); return true; });
        assertEquals(binding.target(), assertInstanceOf(ResolutionResult.Resolved.class, resolver.resolve(requested)).target());
        assertEquals(binding.target(), assertInstanceOf(ResolutionResult.Resolved.class, resolver.resolve(requested)).target());
        assertEquals(2, calls.get());
        assertInstanceOf(ResolutionResult.Rejected.class, resolver.resolve(request(binding.target().binding(), new RequestedModelStrategy("chat", "wrong"))));
        assertInstanceOf(ResolutionResult.Rejected.class, new JdbcModelResolutionPort(bindings, request -> false).resolve(requested));
        var rejected = assertInstanceOf(ResolutionResult.Rejected.class, new JdbcModelResolutionPort(bindings,
                request -> { throw new IllegalStateException("secret-provider-message"); }).resolve(requested));
        assertEquals(RetryMode.NONE, rejected.failure().retryMode()); assertFalse(rejected.toString().contains("secret-provider-message"));
    }

    @Test void invalidTrustedSourceEndpointDoesNotPersistOrLeakCredential() {
        var failure = assertThrows(IllegalArgumentException.class, () -> new JdbcModelBindings.Source("platform-model:invalid",
                new JdbcModelBindings.ProviderConfiguration("invalid", "https://user:credential@model.test/v1",
                        new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                                "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1")), "encrypted-model"));
        assertEquals("model_endpoint_invalid", failure.getMessage()); assertNull(failure.getCause()); assertEquals(0, count());
    }

    private JdbcModelBindings.Binding publish(Optional<AgentId> agent) { return bindings.publish(TENANT, "chat-binding", agent, PROFILE, source("agent-model")); }
    private static JdbcModelBindings.Source source(String modelId) {
        return new JdbcModelBindings.Source("platform-model:" + modelId,
                new JdbcModelBindings.ProviderConfiguration(modelId, "http://localhost:18080/v1",
                        new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1",
                                "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1")),
                "encrypted-model");
    }
    private static JdbcModelBindings publishingBindings() {
        var providers = ModelProviderCatalog.standard();
        return new JdbcModelBindings(dataSource(), (configuration, requestedProfile) -> {
            var profile = configuration.providerProfile();
            var resolved = providers.resolve(new ModelProviderCatalog.Selection(
                    profile.providerId(), profile.serviceId(), profile.profileVersion(),
                    configuration.modelId(), ModelProviderCatalog.ModelProtocol.fromId(
                            profile.protocol()),
                    ModelProviderCatalog.AuthenticationScheme.fromId(
                            profile.authenticationScheme()), configuration.endpoint()));
            if (!resolved.dialectVersion().equals(profile.dialectVersion())) {
                throw new IllegalArgumentException("model_dialect_version_mismatch");
            }
            providers.validateCapabilities(resolved, requestedProfile.responseFormats(), requestedProfile.streaming(), requestedProfile.reasoning());
        });
    }
    private static SessionOwner owner(String agent) { return SessionOwner.user(new SessionRef(TENANT,"session"),new AgentId(agent),new EndUserId(7)); }
    private static ModelResolutionRequest request(ModelBindingRef binding, RequestedModelStrategy strategy) {
        var owner = owner("11"); var input = new InputId("input"); var ref = new ResultRef("verified");
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(TENANT,"run"), new TraceEnvelope("trace","span",input,Optional.empty()), ref, new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(TENANT,"model-worker"),owner,Set.of("sessions.write"),1,ref);
        return new ModelResolutionRequest(run,identity,binding,strategy);
    }
    private static int count() { try (var c = admin(); var s = c.createStatement(); var row = s.executeQuery("SELECT count(*) FROM agent_runtime_model_bindings")) { row.next(); return row.getInt(1); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
    private static DataSource dataSource() { var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl()); source.setUser("model_reader"); source.setPassword("model_reader_password"); return source; }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private static void execute(String sql) { try (var c = admin(); var s = c.createStatement()) { s.execute(sql); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
}
