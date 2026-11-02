package com.skillforge.platform.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.foundation.integration.IntegrationProvenanceMapper;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.invocation.LlmUsageMapper;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.identity.ModelPolicyResource;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.FrozenModelRequest;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import com.skillforge.systemagent.runtime.SystemAgentModelInvocationAudit;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 实际迁移、Mapper 与有效报告视图共同证明部分报告、幂等和 RLS；数据库由用例独占。 */
@Testcontainers
class ProviderUsageReportIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("usage_observation").withUsername("skillforge").withPassword("test-only");
    static JdbcTemplate jdbc;
    static CostRecorder recorder;
    static CostRecorder hostRecorder;
    static SqlSessionTemplate mybatis;
    static DriverManagerDataSource source;
    static TransactionTemplate reportWrites;

    @BeforeAll static void initialize() throws Exception {
        source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        reportWrites = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new com.skillforge.core.foundation.config.TenantRlsInterceptor());
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("usage-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), source));
        try (var xml = new ClassPathResource("mapper/CostEventMapper.xml").getInputStream()) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml, configuration, "mapper/CostEventMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        try (var xml = new ClassPathResource("mapper/ExternalInvocationMapper.xml").getInputStream()) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml, configuration, "mapper/ExternalInvocationMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        try (var xml = new ClassPathResource("mapper/PlatformUsageMapper.xml").getInputStream()) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml, configuration, "mapper/PlatformUsageMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        IntegrationProvenanceService.configure(configuration);
        mybatis = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var provenance=mock(com.skillforge.core.foundation.integration.IntegrationProvenanceService.class);
        when(provenance.resolveRun(anyString(),anyString())).thenReturn(new com.skillforge.core.foundation.integration.IntegrationProvenanceService.Source(
                "system","system:provider-usage-fixture","system:provider-usage-fixture",null,null,null,null,"root",null,null,null,null));
        recorder=recorder(provenance);
        hostRecorder=recorder(new IntegrationProvenanceService(mybatis.getMapper(IntegrationProvenanceMapper.class),mock(InstitutionIntegrationService.class)));
    }

    private static CostRecorder recorder(IntegrationProvenanceService provenance) {
        var exporter=new com.skillforge.core.billing.export.PlatformUsageRecorder(
                mybatis.getMapper(com.skillforge.core.billing.export.PlatformUsageMapper.class),provenance);
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("exporter",exporter));
        return new CostRecorder(mybatis.getMapper(CostEventMapper.class),mock(PriceCalculator.class),
                beans.getBeanProvider(com.skillforge.core.billing.pricing.ExchangeRateProvider.class),
                beans.getBeanProvider(com.skillforge.core.billing.export.PlatformUsageRecorder.class));
    }

    @Test void mergesReportsWithoutCountingSnapshotsOrMoneyTwice() {
        report("7", "a", 0, null, null, null);
        report("7", "a", 1, 100L, null, 80L);
        report("7", "a", 2, null, 10L, null);
        report("7", "a", 2, null, 10L, null);
        assertThrows(IllegalStateException.class, () -> report("7", "a", 2, null, 11L, null));
        report("7", "b", 1, 300L, 20L, 0L);
        report("7", "c", 1, 200L, null, null);
        var result = jdbc.queryForMap("""
                SELECT COUNT(*) AS calls, SUM(prompt_tokens) AS input, SUM(completion_tokens) AS output,
                  COUNT(completion_tokens) AS output_reports,
                  SUM(cached_input_tokens)::numeric / SUM(prompt_tokens) FILTER(WHERE cached_input_tokens IS NOT NULL) AS hit_ratio
                FROM observation_effective_model_usage WHERE tenant_id=7
                """);
        assertEquals(3L, result.get("calls"));
        assertEquals(0, new BigDecimal("600").compareTo((BigDecimal) result.get("input")));
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) result.get("output")));
        assertEquals(2L, result.get("output_reports"));
        assertEquals(0, new BigDecimal("0.2").compareTo((BigDecimal) result.get("hit_ratio")));
        jdbc.update("""
                INSERT INTO nw_usage_events(id,tenant_id,event_type,provider,model,occurred_at,ingested_at,event_id,
                 session_id,root_input_id,run_id,attempt_id,invocation_id,prompt_tokens,completion_tokens,
                 usage_source,total_amount,total_amount_cents,amount_status,usage_event_kind,origin_kind,actor_ref,responsibility_ref)
                VALUES(9000001,7,'llm.chat','provider','model',now(),now(),'measured-a','session','root','run',
                 'a','invocation-a',100,10,'PROVIDER_REPORTED',15,15,'priced','measured','system','system:provider-usage-fixture','system:provider-usage-fixture')
                """);
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM observation_effective_model_usage WHERE tenant_id=7", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM billing_effective_usage WHERE tenant_id=7", Long.class));
    }

    @Test void preservesLargeNumbersAndInvokesTenantRls() throws Exception {
        report("8", "large", 1, 9007199254740993L, null, 0L);
        assertEquals(9007199254740993L, jdbc.queryForObject(
                "SELECT prompt_tokens FROM observation_effective_model_usage WHERE tenant_id=8", Long.class));
        jdbc.execute("CREATE ROLE observation_test_reader NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO observation_test_reader");
        // 真实Tenant RLS下读取源账本与平台发布引用，不授予跨机构权限。
        jdbc.execute("GRANT SELECT ON public.nw_usage_events,public.observation_effective_model_usage,public.nw_usage_publication_events TO observation_test_reader");
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO observation_test_reader");
        assertTrue(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='observation_test_reader'",Boolean.class));
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE observation_test_reader");
                statement.execute("SET LOCAL app.tenant_id='8'");
                try(var rows=statement.executeQuery("SELECT current_user")) {
                    assertTrue(rows.next());assertEquals("observation_test_reader",rows.getString(1));
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM observation_effective_model_usage WHERE tenant_id<>8")) {
                    assertTrue(rows.next()); assertEquals(0L, rows.getLong(1));
                }
                try (var rows = statement.executeQuery("SELECT prompt_tokens FROM observation_effective_model_usage WHERE tenant_id=8")) {
                    assertTrue(rows.next()); assertEquals(9007199254740993L, rows.getLong(1));
                }
            } finally { connection.rollback(); }
        }
    }

    @Test void rejectsContradictorySubsetsAcrossRevisions() {
        report("9", "inconsistent", 1, 4L, null, null);
        report("9", "inconsistent", 2, null, null, 5L);
        var row = jdbc.queryForMap("SELECT prompt_tokens,cached_input_tokens FROM observation_effective_model_usage WHERE tenant_id=9");
        assertEquals(4L, row.get("prompt_tokens"));
        assertNull(row.get("cached_input_tokens"));
    }

    @Test void attemptStartAndInitialReportCommitAtomically() {
        var ledger = new com.skillforge.core.billing.invocation.ExternalInvocationLedger(
                mybatis.getMapper(com.skillforge.core.billing.persistence.ExternalInvocationMapper.class),hostRecorder);
        var direct = atomicDirectModel();
        var failingRecorder = spy(hostRecorder);
        doThrow(new IllegalStateException("report-write-failed")).when(failingRecorder)
                .recordUsageReport(anyString(), any(), any(), anyString(), eq(0L), any(), any());
        var failed = new SystemAgentModelInvocationAudit(ledger, failingRecorder, new DataSourceTransactionManager(source))
                .dispatched(direct.registered(), direct.prepared(), direct.binding());
        var provider = new ModelInvocationAudit.Provider("provider", "service", "model");
        assertEquals("report-write-failed", assertThrows(IllegalStateException.class, () -> failed.start(direct.call(), provider)).getMessage());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM nw_external_invocations WHERE tenant_id=10", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=10", Long.class));
        var successful = new SystemAgentModelInvocationAudit(ledger, hostRecorder, new DataSourceTransactionManager(source))
                .dispatched(direct.registered(), direct.prepared(), direct.binding());
        assertTrue(successful.start(direct.call(), provider));
        assertFalse(successful.start(direct.call(), provider));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM nw_external_invocations WHERE tenant_id=10", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=10", Long.class));
        var fact=jdbc.queryForMap("SELECT origin_kind,actor_ref,responsibility_ref,source_owner_kind,agent_definition_version FROM nw_usage_events WHERE tenant_id=10");
        assertEquals("system",fact.get("origin_kind"));assertEquals("system:platform-system-agent",fact.get("actor_ref"));
        assertEquals("system-agent:11",fact.get("responsibility_ref"));assertEquals("platform",fact.get("source_owner_kind"));
        assertEquals("system-agent:11:debug-run:atomic-business",fact.get("agent_definition_version"));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM nw_runtime_agent_run_bindings WHERE tenant_id='10'",Long.class));
    }

    /** 真实 Session 生命周期、当前身份、冻结绑定和 typed Store 返回原始派发回执。 */
    private DirectModel atomicDirectModel() {
        jdbc.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES(810000,'usage-atomic','用量原子性测试',0.999)");
        jdbc.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status) VALUES(10,'用量测试机构','usage-atomic',810000,'active')");
        var tenant = new TenantId("10");
        var owner = SessionOwner.user(new SessionRef(tenant, "atomic-session"), new AgentId("11"), new EndUserId(9));
        var principal = new PrincipalRef.SystemPrincipal(tenant, "platform-system-agent");
        hostSource(owner);
        var identities = new JdbcIdentityPolicyPort(source, Map.of(principal, Set.of("sessions.write")));
        var authentication = identities.registerTrusted(principal, owner, "worker", "usage-atomic", Duration.ofMinutes(5));
        var actor = assertInstanceOf(IdentityPolicyPort.IdentityResult.Verified.class, identities.verify(new IdentityPolicyPort.IdentityRequest(
                authentication, owner, "worker", "usage-atomic", new RequestId("atomic-verify"), Optional.empty(), "atomic-trace", "atomic-span"))).identity();
        var runRef = new RunRef(tenant, "atomic-run"); var input = new InputId("atomic-input");
        var identity = new RunIdentity(owner, owner.target().requireAgent(), input, runRef,
                new TraceEnvelope("atomic-trace", "atomic-span", new InputId("atomic-root"), Optional.empty()), actor.verificationRef(),
                new RunRequester.System(principal),Optional.empty(),new RunOriginRef(tenant,"atomic-business"));
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class, new JdbcSessionLogPort(source).append(
                new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                        new SessionEventDraft(new EventId("atomic-session-opened"), "session.opened", "1", "atomic-session-opened",
                                identity.trace(), new TypedPayload("test.session.v1", "{}"), Instant.now()))));
        var bindings = new JdbcModelBindings(source, (configuration, profile) -> {});
        var strategy = new RequestedModelStrategy("chat", "v1");
        var published = bindings.publish(tenant, "atomic-model", Optional.of(new AgentId("11")),
                new JdbcModelBindings.Profile(strategy, Set.of(ResponseFormatKind.TEXT), true, false, Duration.ofSeconds(5)),
                new JdbcModelBindings.Source("platform-model:81:3", new JdbcModelBindings.ProviderConfiguration("model", "http://127.0.0.1:1",
                        new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1", "openai-chat-completions",
                                "bearer-api-key", "openai-compatible-chat-v1")), "encrypted-fixture"));
        var binding = bindings.findForOwner(published.target().binding(), identity.agentId()).orElseThrow();
        var request = new FrozenModelRequest(strategy, binding.target(), List.of(new ModelMessage("atomic-message", ModelRole.USER, "hello")),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "atomic-test"),
                new GenerationOptions(OptionalInt.of(10), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
        var codec = new AgentExecutionCodec(new CodecLimits(1_000_000, 10_000, 64));
        var prepared = new Prepared(new ActionRef(tenant, "atomic-action"), 1, Kind.MODEL, binding.target().effectiveModelId(),
                binding.target().capabilities().capabilityFactRef(), new Material(new ResultRef("atomic-request"),
                        codec.encodeModelRequest(new AgentExecutionCodec.ModelCallRequest(request, List.of(), 0, Optional.empty()))), "a".repeat(64));
        var store = new JdbcExecutionStore(source, identities, (session, run, current, actual) -> {
            if (!actual.equals(prepared)) return false;
            return identities.authorize(session, new IdentityPolicyPort.AuthorizationRequest(current, "model.stream",
                    ModelPolicyResource.dispatch(run.identity(), binding.target().binding(), strategy), "atomic-trace", "atomic-span"))
                    instanceof IdentityPolicyPort.AuthorizationResult.Allowed;
        });
        var capacity = new ExecutionBudgetPort.Resources(2, 0, 0, 0, 100, 100, 200);
        var limits = new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity, 3600, 3600, "atomic:v1");
        assertTrue(store.admit(new Admission(actor, identity, "atomic-admission", new ResultRef("atomic-configuration"),
                new Material(new ResultRef("atomic-input"), new TypedPayload("test.input.v1", "{}")),
                new Material(new ResultRef("atomic-context"), new TypedPayload("test.context.v1", "{}")),
                new ExecutionBudgetPort.Registration(runRef, runRef, ExecutionBudgetPort.Role.PRIMARY, new ExecutionBudgetPort.Policy.Enforced(limits)), List.of(), List.of())).created());
        var claimed = store.claim(new Claim(runRef, "atomic-worker", "atomic-claim", Duration.ofMinutes(5))).orElseThrow();
        var demand = new ExecutionBudgetPort.Demand(runRef, "atomic-attempt", prepared.fingerprint(), ExecutionBudgetPort.Category.BUSINESS_MODEL,
                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL, 10, 10), false);
        var registered = assertInstanceOf(DispatchResult.Registered.class, store.dispatch(new Dispatch(runRef, claimed.revision(),
                claimed.lease().orElseThrow(), prepared, demand.attemptId(), demand, actor, Optional.empty(), List.of())));
        assertTrue(store.authorizeSend(registered, prepared));
        var dispatch = new ProviderDispatch(runRef, prepared.action(), prepared.action().actionId(), registered.attempt().id(),
                PortalCallKind.MODEL_STREAM, prepared.request().reference(), prepared.capability(), prepared.fingerprint(), registered.attempt().fence());
        var context = ProviderCallContext.dispatched(new PortCallContext(identity, actor, new InvocationControl(Instant.now().plusSeconds(30), () -> false)), dispatch);
        var call = new ModelCall(context, strategy, binding.target(), prepared.request().reference(), request.messages(), request.response(), request.generation(), request.reasoning());
        return new DirectModel(registered, prepared, binding, call);
    }

    private record DirectModel(DispatchResult.Registered registered, Prepared prepared, JdbcModelBindings.Binding binding, ModelCall call) { }

    /** 与正式SystemAgent Host相同的独立owner和不可变来源；不建立机构Agent或执行binding。 */
    private void hostSource(SessionOwner owner) {
        var config=new com.skillforge.core.agent.systemagent.SystemAgentTypes.Config(
                "原子计量系统助手","验证开始事实与未知报告同事务","完成本次受控模型调用","81",3600,2,List.of());
        String snapshot;
        try {snapshot=new ObjectMapper().writeValueAsString(Map.of("run_id","atomic-business",
                "prompt_source_ref","system-agent:11:debug-run:atomic-business","generation",1,"config",config,"model_revision",3,
                "mounts",List.of(),"input_fingerprint",AgentExecutionCodec.digest("system-agent-input.v1\0hello")));}
        catch(com.fasterxml.jackson.core.JsonProcessingException malformed){throw new IllegalStateException("atomic_host_source_invalid",malformed);}
        TenantContext.runWithTenant(owner.sessionRef().tenantId().value(),()->reportWrites.executeWithoutResult(status->{
            jdbc.update("INSERT INTO nw_runtime_host_owners(tenant_id,target_kind,target_id,agent_id,session_id,end_user_id,integration_id) VALUES(?,?,?,?,?,?,?)",
                    owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),owner.target().requireAgent().value(),owner.sessionRef().sessionId(),owner.requireUser().value(),"system-agents");
            jdbc.update("INSERT INTO nw_system_agent_runtime_capability_sources(tenant_id,session_id,agent_id,end_user_id,input_id,generation,source_data) VALUES(?,?,?,?,?,1,CAST(? AS jsonb))",
                    owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),owner.target().requireAgent().value(),owner.requireUser().value(),"atomic-input",snapshot);
        }));
    }

    private void report(String tenant, String attempt, long revision, Long input, Long output, Long cached) {
        var context = CostContext.builder().sessionId("session").rootInputId("root").requestId("input")
                .runId("run").callPoint("agent-runtime.model").build();
        var invocation = new ExternalInvocationRequest("invocation-" + attempt, attempt, ExternalOperationType.MODEL_CHAT,
                "provider", "service", attempt, new TraceContext("input", "root", "trace", "span", "event", null), Map.of());
        // 手工构造的fixture没有Spring事务代理；原报告、冻结归属与pending必须共用真实事务。
        TenantContext.runWithTenant(tenant, () -> reportWrites.executeWithoutResult(status ->
                recorder.recordUsageReport(tenant, context, invocation, "model", revision,
                        LlmUsageMapper.fromModel(new ModelUsage(optional(input), optional(output), optional(cached), OptionalLong.empty(), OptionalLong.empty())), Map.of())));
    }

    private static OptionalLong optional(Long value) { return value == null ? OptionalLong.empty() : OptionalLong.of(value); }
}
