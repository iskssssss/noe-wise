package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.export.*;
import com.skillforge.core.billing.invocation.*;
import com.skillforge.core.billing.metering.*;
import com.skillforge.core.billing.persistence.*;
import com.skillforge.core.billing.pricing.*;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.institution.runtime.AgentRuntimeModelInvocationAudit;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** 真实 PostgreSQL 和受限写角色；回执、价格及归因来源是受控测试夹具。 */
@Testcontainers(disabledWithoutDocker = false)
class FailedDirectModelUsagePostgresTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate owner;
    private static DriverManagerDataSource source;
    private static AnnotationConfigApplicationContext context;
    private static DataSourceTransactionManager transactions;
    private static final String ROLE = "failed_usage_writer";
    private static final ModelInvocationAudit.Provider PROVIDER = new ModelInvocationAudit.Provider("provider", "service", "model");

    @Configuration @EnableTransactionManagement static class Transactions { }

    @BeforeAll static void setup() throws Exception {
        CurrentHostDatabase.migrate(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var admin = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        owner = new JdbcTemplate(admin);
        try (var connection = admin.getConnection()) {
            for (long tenant = 71; tenant <= 76; tenant++) CurrentHostFixtures.tenant(connection, tenant);
        }
        owner.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO " + ROLE);
        source = new DriverManagerDataSource(DB.getJdbcUrl(), ROLE, "test-only");
        assertFalse(new JdbcTemplate(source).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            var error = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO agent_runtime_budget_roots(tenant_id,root_id,scenario,source_version,active_limit_seconds) VALUES ('71','unbound-negative','MAINTENANCE','test',60)"));
            assertEquals("42501", error.getSQLState());
        }
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(new TenantRlsInterceptor());
        config.setEnvironment(new Environment("failed-usage", new SpringManagedTransactionFactory(), source));
        for (String xml : List.of("mapper/CostEventMapper.xml", "mapper/ExternalInvocationMapper.xml", "mapper/PlatformUsageMapper.xml")) {
            try (var input = new ClassPathResource(xml).getInputStream()) {
                new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
            }
        }
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var publisher = spy(new PlatformUsageRecorder(sessions.getMapper(PlatformUsageMapper.class), mock(IntegrationProvenanceService.class)));
        doAnswer(call -> { CostEventDO event = call.getArgument(0); event.setOriginKind("native");
            event.setActorRef("member:fixture"); event.setResponsibilityRef("tenant:" + event.getTenantId());
            event.setSourceOwnerKind("tenant"); event.setSourceOwnerTenantId(event.getTenantId()); return null;
        }).when(publisher).attribute(any(CostEventDO.class), nullable(String.class));
        var prices = mock(ModelPriceSource.class);
        when(prices.snapshot(new ModelPriceSource.ModelRevisionRef("81", 3))).thenReturn(new ModelPriceSource.Snapshot("frozen-price",
                Map.of(new ModelPriceSource.Key("provider", "model"), new ModelPriceSource.Price(
                        new BigDecimal("200"), new BigDecimal("800"), null, "CNY", null, null, null))));
        transactions = new DataSourceTransactionManager(source);
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> transactions);
        context.registerBean(PlatformUsageRecorder.class, () -> publisher);
        context.registerBean(CostRecorder.class, () -> new CostRecorder(sessions.getMapper(CostEventMapper.class), new PriceCalculator(prices),
                context.getBeanProvider(ExchangeRateProvider.class), context.getBeanProvider(PlatformUsageRecorder.class)));
        context.registerBean(ExternalInvocationLedger.class, () -> new ExternalInvocationLedger(sessions.getMapper(ExternalInvocationMapper.class), context.getBean(CostRecorder.class)));
        context.refresh();
    }
    @AfterAll static void close() { if (context != null) context.close(); }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void failedMergesOriginalAttemptAndPricesOnce() throws Exception {
        var f = fixture(71); var audit = audit(f);
        assertTrue(audit.start(f.call(), PROVIDER)); assertFalse(audit.start(f.call(), PROVIDER));
        audit.usageReported(f.call(), PROVIDER, 1, usage(100L, null));
        audit.usageReported(f.call(), PROVIDER, 2, usage(null, 10L));
        audit.usageReported(f.call(), PROVIDER, 2, usage(null, 10L));
        assertThrows(IllegalStateException.class, () -> audit.usageReported(f.call(), PROVIDER, 2, usage(null, 11L)));
        audit.failed(f.call(), PROVIDER, "provider_rejected");
        assertEquals("FAILED", status(71));
        var row = measured(71); assertEquals("direct-attempt:usage", row.get("event_id"));
        assertEquals("direct-attempt", row.get("attempt_id")); assertEquals("direct-action", row.get("invocation_id"));
        assertEquals(100L, row.get("prompt_tokens")); assertEquals(10L, row.get("completion_tokens"));
        assertEquals(2L, row.get("usage_snapshot_revision")); assertEquals("priced", row.get("amount_status"));
        assertEquals(0, new BigDecimal("0.028").compareTo((BigDecimal) row.get("total_amount")));
        assertEquals("frozen-price@fx-cny-identity-v1", row.get("pricing_snapshot_version"));
        assertEquals(3L, count(71, "usage_report")); assertEquals(1L, count(71, "measured"));
        assertEquals(1L, owner.queryForObject("SELECT count(*) FROM billing_effective_usage WHERE tenant_id=71 AND attempt_id='direct-attempt'", Long.class));
        assertEquals("model_attempt_terminal_conflict", assertThrows(IllegalStateException.class,
                () -> audit.failed(f.call(), PROVIDER, "provider_rejected")).getMessage());
        assertEquals(1L, count(71, "measured"));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false); statement.execute("SET LOCAL app.tenant_id='72'");
            try (var rows = statement.executeQuery("SELECT count(*) FROM nw_usage_events WHERE tenant_id=71")) {
                assertTrue(rows.next()); assertEquals(0L, rows.getLong(1));
            } finally { connection.rollback(); }
        }
    }

    @Test void failedOutboxWriteRollsBackMeasuredAndRetainsUnresolvedAttempt() {
        var f = fixture(76); var audit = audit(f);
        assertTrue(audit.start(f.call(), PROVIDER));
        audit.usageReported(f.call(), PROVIDER, 1, usage(100L, null));
        audit.usageReported(f.call(), PROVIDER, 2, usage(null, 10L));
        var reports = owner.queryForList("SELECT event_id, invocation_id, attempt_id, report_revision, prompt_tokens, completion_tokens "
                + "FROM nw_usage_events WHERE tenant_id=76 AND usage_event_kind='usage_report' ORDER BY report_revision");
        assertEquals(3, reports.size());
        assertEquals(List.of("76:direct-attempt:usage-report:0", "76:direct-attempt:usage-report:1",
                "76:direct-attempt:usage-report:2"), owner.queryForList(
                "SELECT event_id FROM nw_usage_publication_pending WHERE tenant_id=76 ORDER BY event_id", String.class));
        // 故障只影响本隔离库中本次 attempt 的 measured outbox，不影响已提交报告。
        try {
            owner.execute("""
                    CREATE FUNCTION public.test_reject_failed_usage_pending() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.tenant_id = 76 AND NEW.event_id = '76:direct-attempt:usage' THEN
                        RAISE EXCEPTION 'test_failed_usage_outbox';
                      END IF;
                      RETURN NEW;
                    END $$
                    """);
            owner.execute("CREATE TRIGGER test_reject_failed_usage_pending BEFORE INSERT ON public.nw_usage_publication_pending "
                    + "FOR EACH ROW EXECUTE FUNCTION public.test_reject_failed_usage_pending()");
            var error = assertThrows(RuntimeException.class, () -> audit.failed(f.call(), PROVIDER, "provider_rejected"));
            var messages = new StringBuilder();
            for (Throwable cause = error; cause != null; cause = cause.getCause()) messages.append(cause.getMessage());
            assertTrue(messages.toString().contains("test_failed_usage_outbox"), messages.toString());
        } finally {
            owner.execute("DROP TRIGGER IF EXISTS test_reject_failed_usage_pending ON public.nw_usage_publication_pending");
            owner.execute("DROP FUNCTION IF EXISTS public.test_reject_failed_usage_pending()");
        }
        assertEquals("UNRESOLVED", status(76));
        assertEquals(0L, count(76, "measured"));
        assertEquals(reports, owner.queryForList("SELECT event_id, invocation_id, attempt_id, report_revision, prompt_tokens, completion_tokens "
                + "FROM nw_usage_events WHERE tenant_id=76 AND usage_event_kind='usage_report' ORDER BY report_revision"));
        assertEquals(3L, owner.queryForObject("SELECT count(*) FROM nw_usage_publication_pending WHERE tenant_id=76", Long.class));
        var invocation = owner.queryForMap("SELECT invocation_id, attempt_id, status, error_code FROM nw_external_invocations "
                + "WHERE tenant_id=76 AND attempt_id='direct-attempt'");
        assertEquals("direct-action", invocation.get("invocation_id"));
        assertFalse(audit.start(f.call(), PROVIDER));
        assertEquals(invocation, owner.queryForMap("SELECT invocation_id, attempt_id, status, error_code FROM nw_external_invocations "
                + "WHERE tenant_id=76 AND attempt_id='direct-attempt'"));
        assertEquals(0L, count(76, "measured")); assertEquals(3L, count(76, "usage_report"));
    }

    @Test void missingZeroPartialAndUnknownRemainDistinct() {
        var absent = fixture(72); var a = audit(absent); assertTrue(a.start(absent.call(), PROVIDER));
        a.failed(absent.call(), PROVIDER, "rejected"); assertEquals("FAILED", status(72)); assertEquals(0L, count(72, "measured"));
        var partial = fixture(73); var p = audit(partial); assertTrue(p.start(partial.call(), PROVIDER));
        p.usageReported(partial.call(), PROVIDER, 1, usage(5L, null)); p.failed(partial.call(), PROVIDER, "rejected");
        var partialRow = measured(73); assertEquals(5L, partialRow.get("prompt_tokens")); assertNull(partialRow.get("completion_tokens"));
        assertNull(partialRow.get("total_amount")); assertEquals("unpriced", partialRow.get("amount_status"));
        var zero = fixture(74); var z = audit(zero); assertTrue(z.start(zero.call(), PROVIDER));
        z.usageReported(zero.call(), PROVIDER, 1, usage(0L, 0L)); z.failed(zero.call(), PROVIDER, "rejected");
        var zeroRow = measured(74); assertEquals(0L, zeroRow.get("prompt_tokens")); assertEquals(0L, zeroRow.get("completion_tokens"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) zeroRow.get("total_amount"))); assertEquals("priced", zeroRow.get("amount_status"));
        var unknown = fixture(75); var u = audit(unknown); assertTrue(u.start(unknown.call(), PROVIDER));
        u.usageReported(unknown.call(), PROVIDER, 1, usage(5L, 3L)); u.unresolved(unknown.call(), PROVIDER, "unknown");
        assertEquals("UNRESOLVED", status(75)); assertEquals(0L, count(75, "measured")); assertEquals(2L, count(75, "usage_report"));
    }
    private static ModelUsage usage(Long input, Long output) { return new ModelUsage(input == null ? OptionalLong.empty() : OptionalLong.of(input), output == null ? OptionalLong.empty() : OptionalLong.of(output)); }
    private static long count(int tenant, String kind) { return owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=? AND attempt_id='direct-attempt' AND usage_event_kind=?", Long.class, tenant, kind); }
    private static String status(int tenant) { return owner.queryForObject("SELECT status FROM nw_external_invocations WHERE tenant_id=? AND attempt_id='direct-attempt'", String.class, tenant); }
    private static Map<String,Object> measured(int tenant) { return owner.queryForMap("SELECT event_id,attempt_id,invocation_id,prompt_tokens,completion_tokens,usage_snapshot_revision,amount_status,total_amount,pricing_snapshot_version FROM nw_usage_events WHERE tenant_id=? AND attempt_id='direct-attempt' AND usage_event_kind='measured'", tenant); }
    private static ModelInvocationAudit audit(DirectModel f) { return new AgentRuntimeModelInvocationAudit(context.getBean(ExternalInvocationLedger.class), context.getBean(CostRecorder.class), transactions, WorkflowResourceOriginFixture.ordinary()).dispatched(f.receipt().registered(), f.receipt().prepared(), f.binding()); }
    private static DirectModel fixture(int tenantNumber) {
        var tenant = new TenantId(Integer.toString(tenantNumber)); var input = new InputId("input-1");
        var owner = SessionOwner.user(new SessionRef(tenant, "session-1"), new AgentId("11"), new EndUserId(9));
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(tenant, "run-1"),
                new TraceEnvelope("trace-1", "span-1", new InputId("root-1"), Optional.empty()), new ResultRef("causation"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, "input:input-1"));
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner, Set.of("sessions.write"), 1, new ResultRef("verified"));
        var target = new FrozenModelTarget(new ModelBindingRef(tenant, "chat", 12), "frozen-model",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), true, false, new ResultRef("model-capability")), Duration.ofSeconds(5), new ResultRef("resolution"));
        var strategy = new RequestedModelStrategy("chat", "v1");
        var binding = new JdbcModelBindings.Binding(target, strategy, "http://127.0.0.1:1", "encrypted-fixture",
                new JdbcModelBindings.ProviderProfile("custom", "custom-openai-compatible", "custom-v1", "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1"), "platform-model:81:3");
        var receipt = RegisteredProviderFixture.create(run, actor, ExecutionStore.Kind.MODEL, PortalCallKind.MODEL_STREAM, "chat", new TypedPayload("request.v1", "{}"));
        var call = new ModelCall(receipt.context(), strategy, target, receipt.prepared().request().reference(), List.of(new ModelMessage("message", ModelRole.USER, "hello")),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "fixture"), new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DISABLED);
        return new DirectModel(receipt, binding, call);
    }
    private record DirectModel(RegisteredProviderFixture receipt, JdbcModelBindings.Binding binding, ModelCall call) { }
}
