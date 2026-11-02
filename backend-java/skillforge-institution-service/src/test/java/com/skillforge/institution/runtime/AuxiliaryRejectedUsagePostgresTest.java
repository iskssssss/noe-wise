package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.budget.AuxiliaryModelBudgets;
import com.skillforge.core.billing.export.PlatformUsageMapper;
import com.skillforge.core.billing.export.PlatformUsageRecorder;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.foundation.runtimeconfig.ChatBudgetSettings;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.invocation.LlmHttpSupport;
import com.skillforge.core.model.invocation.LlmService;
import com.skillforge.core.model.invocation.ModelCallRecords;
import com.skillforge.core.shared.resilience.ResilienceFixtures;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.port.ModelPort;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实迁移、RLS、预算和账本；目录、价格、归因与供应商使用受控夹具。 */
@Testcontainers(disabledWithoutDocker = false)
class AuxiliaryRejectedUsagePostgresTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate owner;
    private static AnnotationConfigApplicationContext context;
    private static ExternalInvocationGuard guard;
    private static ModelProviderRuntime provider;
    private static java.util.function.Supplier<LlmService> service;
    private static final String ROLE = "auxiliary_usage_writer";

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeAll static void setup() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        owner = new JdbcTemplate(admin);
        try (var connection = admin.getConnection()) { CurrentHostFixtures.tenant(connection, 7L); }
        owner.update("INSERT INTO nw_platform_models(id,current_revision,draft_revision,draft,draft_changed) VALUES(10,NULL,1,'{}'::jsonb,true)");
        owner.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD 'synthetic-auxiliary-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO " + ROLE);
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), ROLE, "synthetic-auxiliary-password");
        assertFalse(new JdbcTemplate(source).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            var denied = assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO agent_runtime_budget_roots(tenant_id,root_id,scenario,source_version,active_limit_seconds) "
                            + "VALUES ('7','unbound-negative','MAINTENANCE','test',60)"));
            assertEquals("42501", denied.getSQLState());
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.setEnvironment(new Environment("auxiliary-usage", new SpringManagedTransactionFactory(), source));
        for (String resource : List.of("mapper/CostEventMapper.xml", "mapper/ExternalInvocationMapper.xml", "mapper/PlatformUsageMapper.xml")) {
            try (var input = new ClassPathResource(resource).getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var publisher = spy(new PlatformUsageRecorder(sessions.getMapper(PlatformUsageMapper.class), mock(IntegrationProvenanceService.class)));
        doAnswer(call -> {
            CostEventDO event = call.getArgument(0);
            event.setOriginKind("native"); event.setActorRef("member:auxiliary-fixture");
            event.setResponsibilityRef("tenant:" + event.getTenantId());
            event.setSourceOwnerKind("tenant"); event.setSourceOwnerTenantId(event.getTenantId());
            return null;
        }).when(publisher).attribute(any(CostEventDO.class), nullable(String.class));
        var prices = mock(ModelPriceSource.class);
        when(prices.snapshot(any(ModelPriceSource.ModelRevisionRef.class))).thenReturn(new ModelPriceSource.Snapshot("fixture",
                Map.of(new ModelPriceSource.Key("deepseek", "model-x"), new ModelPriceSource.Price(null, null, null, "CNY", null, null, null))));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(PlatformUsageRecorder.class, () -> publisher);
        context.registerBean(CostRecorder.class, () -> new CostRecorder(sessions.getMapper(CostEventMapper.class), new PriceCalculator(prices),
                context.getBeanProvider(com.skillforge.core.billing.pricing.ExchangeRateProvider.class), context.getBeanProvider(PlatformUsageRecorder.class)));
        context.registerBean(ExternalInvocationLedger.class, () -> new ExternalInvocationLedger(sessions.getMapper(ExternalInvocationMapper.class), context.getBean(CostRecorder.class)));
        context.refresh();
        var settings = mock(OperationalSettingsService.class);
        when(settings.read(ChatBudgetSettings.GROUP)).thenReturn(new OperationalSettingsService.Snapshot(1, Map.of()));
        var budgets = new AuxiliaryModelBudgets(new JdbcExecutionBudgets(source, new HostBudgetTransactions()), settings);
        guard = spy(new ExternalInvocationGuard(context.getBean(ExternalInvocationLedger.class), budgets));
        provider = mock(ModelProviderRuntime.class);
        var catalog = mock(PlatformModelCatalogService.class);
        var model = new PlatformModelConfig("10", 1, "test", "LLM", true, "deepseek", "deepseek-api", "2026-09-13",
                "openai-chat-completions", "bearer-api-key", "openai-compatible-chat-v1", "https://api.deepseek.com", "model-x",
                "encrypted-key", 8192, 64000, 4096, 0.5, "DEFAULT", 30, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 1L)).thenReturn(model);
        var http = mock(LlmHttpSupport.class); when(http.resolveApiKey("encrypted-key")).thenReturn("synthetic-key");
        service = () -> new LlmService(catalog, http, ResilienceFixtures.withDefaultSettings(), settings, null,
                provider, ModelProviderCatalog.standard(), guard, context.getBean(CostRecorder.class), mock(ModelCallRecords.class));
    }

    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void resetMocks() { reset(provider, guard); TenantContext.setTenantId("7"); }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void rejectedReportedPartialAndZeroRemainDistinct() throws Exception {
        verifyRejected(OptionalLong.of(5), OptionalLong.of(3), 5L, 3L, 8L);
        verifyRejected(OptionalLong.of(5), OptionalLong.empty(), 5L, null, null);
        verifyRejected(OptionalLong.of(0), OptionalLong.of(0), 0L, 0L, 0L);
        verifyRejected(OptionalLong.empty(), OptionalLong.empty(), null, null, null);
    }

    private void verifyRejected(OptionalLong input, OptionalLong output, Long expectedInput, Long expectedOutput, Long expectedTotal) throws Exception {
        clearInvocations(provider, guard);
        when(provider.generate(anyString(), any())).thenReturn(new ModelProvider.InvocationResult.Rejected("provider_rejected", Optional.empty(), usage(input, output)));
        var request = invokeAndCapture(false);
        assertEquals("FAILED", status(request));
        long measuredCount = expectedInput == null && expectedOutput == null ? 0 : 1;
        if (measuredCount == 1) {
            var measured = owner.queryForMap("SELECT prompt_tokens,completion_tokens,tokens FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='measured'", request.attemptId());
            assertEquals(expectedInput, measured.get("prompt_tokens")); assertEquals(expectedOutput, measured.get("completion_tokens")); assertEquals(expectedTotal, measured.get("tokens"));
        }
        assertBudget(request, "INPUT_TOKENS", expectedInput); assertBudget(request, "OUTPUT_TOKENS", expectedOutput); assertBudget(request, "TOTAL_TOKENS", expectedTotal);
        replayOriginal(request);
        assertEquals(measuredCount, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='measured'", Long.class, request.attemptId()));
        assertEquals(1L, owner.queryForObject("SELECT count(*) FROM agent_runtime_budget_result_receipts WHERE tenant_id='7' AND attempt_id=?", Long.class, request.attemptId()));
    }

    @Test void unknownRetainsReservationAndOriginalAttemptCannotSendAgain() throws Exception {
        when(provider.generate(anyString(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown("transport_unknown"));
        var request = invokeAndCapture(true);
        assertEquals("UNRESOLVED", status(request));
        assertEquals("SENT", owner.queryForObject("SELECT state FROM agent_runtime_budget_attempts WHERE tenant_id='7' AND attempt_id=?", String.class, request.attemptId()));
        var row = owner.queryForMap("SELECT reported,reserved,settled FROM agent_runtime_budget_attempt_amounts WHERE tenant_id='7' AND attempt_id=? AND dimension='OUTPUT_TOKENS'", request.attemptId());
        assertNull(row.get("reported")); assertTrue(((Number) row.get("reserved")).longValue() > 0); assertEquals(false, row.get("settled"));
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='measured'", Long.class, request.attemptId()));
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='usage_report' AND report_revision=1", Long.class, request.attemptId()));
        replayOriginal(request);
        assertEquals("UNRESOLVED", status(request));
    }

    @Test void unknownReportedUsagePreservesScopeAndPublicationWithoutSettlingBudget() throws Exception {
        when(provider.generate(anyString(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown(
                "transport_unknown", usage(OptionalLong.of(5), OptionalLong.of(3))));
        var request = invokeAndCapture(true);
        assertEquals("UNRESOLVED", status(request));
        assertEquals("SENT", owner.queryForObject("SELECT state FROM agent_runtime_budget_attempts WHERE tenant_id='7' AND attempt_id=?", String.class, request.attemptId()));
        assertBudget(request, "INPUT_TOKENS", null); assertBudget(request, "OUTPUT_TOKENS", null); assertBudget(request, "TOTAL_TOKENS", null);
        var reports = owner.queryForList("SELECT event_id,invocation_id,attempt_id,report_revision,prompt_tokens,completion_tokens,usage_source,scope_type,scope_id,call_point "
                + "FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='usage_report' ORDER BY report_revision", request.attemptId());
        assertEquals(2, reports.size());
        assertEquals(0L, ((Number) reports.get(0).get("report_revision")).longValue());
        assertNull(reports.get(0).get("prompt_tokens")); assertNull(reports.get(0).get("completion_tokens"));
        var report = reports.get(1);
        assertEquals(request.attemptId() + ":usage-report:1", report.get("event_id"));
        assertEquals(request.invocationId(), report.get("invocation_id")); assertEquals(request.attemptId(), report.get("attempt_id"));
        assertEquals(1L, ((Number) report.get("report_revision")).longValue());
        assertEquals(5L, report.get("prompt_tokens")); assertEquals(3L, report.get("completion_tokens"));
        assertEquals("PROVIDER_REPORTED", report.get("usage_source"));
        assertEquals("control_operation", report.get("scope_type")); assertEquals("scope-auxiliary-test", report.get("scope_id"));
        assertEquals("test", report.get("call_point"));
        assertEquals(2L, owner.queryForObject("SELECT count(*) FROM nw_usage_publication_pending p JOIN nw_usage_events e "
                + "ON e.tenant_id=p.tenant_id AND e.id=p.usage_id WHERE e.tenant_id=7 AND e.attempt_id=? AND e.usage_event_kind='usage_report'", Long.class, request.attemptId()));
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='measured'", Long.class, request.attemptId()));
        context.getBean(CostRecorder.class).recordUsageReport("7", costContext(), request, "model-x", 1, new LlmUsage(5, 3, 8), Map.of());
        assertEquals(reports, owner.queryForList("SELECT event_id,invocation_id,attempt_id,report_revision,prompt_tokens,completion_tokens,usage_source,scope_type,scope_id,call_point "
                + "FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='usage_report' ORDER BY report_revision", request.attemptId()));
        assertEquals(2L, owner.queryForObject("SELECT count(*) FROM nw_usage_publication_pending p JOIN nw_usage_events e "
                + "ON e.tenant_id=p.tenant_id AND e.id=p.usage_id WHERE e.tenant_id=7 AND e.attempt_id=? AND e.usage_event_kind='usage_report'", Long.class, request.attemptId()));
        replayOriginal(request);
        assertEquals("UNRESOLVED", status(request));
    }

    @Test void unknownReportPublicationFailureRollsBackReportAndPreservesUnknown() throws Exception {
        owner.execute("CREATE FUNCTION fixture_reject_usage_publication() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.event_id LIKE '%:usage-report:1' THEN RAISE EXCEPTION 'fixture report publication failure'; END IF; RETURN NEW; END $$");
        try {
            owner.execute("CREATE TRIGGER fixture_reject_usage_publication BEFORE INSERT ON nw_usage_publication_pending "
                    + "FOR EACH ROW EXECUTE FUNCTION fixture_reject_usage_publication()");
            when(provider.generate(anyString(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown(
                    "transport_unknown", usage(OptionalLong.of(5), OptionalLong.of(3))));
            var request = invokeAndCapture(true, true);
            assertEquals("UNRESOLVED", status(request));
            assertEquals("SENT", owner.queryForObject("SELECT state FROM agent_runtime_budget_attempts WHERE tenant_id='7' AND attempt_id=?", String.class, request.attemptId()));
            assertBudget(request, "INPUT_TOKENS", null); assertBudget(request, "OUTPUT_TOKENS", null); assertBudget(request, "TOTAL_TOKENS", null);
            assertEquals(1L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='usage_report' AND report_revision=0", Long.class, request.attemptId()));
            assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='usage_report' AND report_revision=1", Long.class, request.attemptId()));
            assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? AND usage_event_kind='measured'", Long.class, request.attemptId()));
            assertEquals(1L, owner.queryForObject("SELECT count(*) FROM nw_usage_publication_pending p JOIN nw_usage_events e "
                    + "ON e.tenant_id=p.tenant_id AND e.id=p.usage_id WHERE e.tenant_id=7 AND e.attempt_id=?", Long.class, request.attemptId()));
            replayOriginal(request);
            assertEquals("UNRESOLVED", status(request));
        } finally {
            owner.execute("DROP TRIGGER IF EXISTS fixture_reject_usage_publication ON nw_usage_publication_pending");
            owner.execute("DROP FUNCTION fixture_reject_usage_publication()");
        }
    }

    @Test void unknownLedgerWriteFailurePreservesOriginalAttemptAndClassification() throws Exception {
        owner.execute("CREATE FUNCTION fixture_reject_unknown_finish() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.status = 'UNRESOLVED' THEN RAISE EXCEPTION 'fixture unknown finish failure'; END IF; RETURN NEW; END $$");
        try {
            owner.execute("CREATE TRIGGER fixture_reject_unknown_finish BEFORE UPDATE ON nw_external_invocations "
                    + "FOR EACH ROW EXECUTE FUNCTION fixture_reject_unknown_finish()");
            when(provider.generate(anyString(), any())).thenReturn(new ModelProvider.InvocationResult.OutcomeUnknown("transport_unknown"));
            try (var scope = MeterScope.open(costContext())) {
                var failure = assertThrows(LlmService.ModelOutcomeUnknownException.class,
                        () -> service.get().generatePinned("7", "10", 1, "system", "question",
                                new ModelPort.ResponseFormat.Text(), Duration.ofSeconds(4), 73, () -> false));
                var original = assertInstanceOf(ExternalInvocationGuard.OutcomeUnknownException.class, failure.getCause());
                assertEquals(1, original.getSuppressed().length);
            }
            var captured = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
            verify(guard).invokeModel(eq("7"), captured.capture(), any(), any());
            verify(provider, times(1)).generate(anyString(), any());
            var request = captured.getValue();
            var row = owner.queryForMap("SELECT invocation_id,attempt_id,status,error_code,ended_at FROM nw_external_invocations WHERE tenant_id=7 AND attempt_id=?", request.attemptId());
            assertEquals(request.invocationId(), row.get("invocation_id"));
            assertEquals(request.attemptId(), row.get("attempt_id"));
            assertEquals("STARTED", row.get("status"));
            assertNull(row.get("error_code")); assertNull(row.get("ended_at"));
            assertEquals("SENT", owner.queryForObject("SELECT state FROM agent_runtime_budget_attempts WHERE tenant_id='7' AND attempt_id=?", String.class, request.attemptId()));
            assertBudget(request, "INPUT_TOKENS", null); assertBudget(request, "OUTPUT_TOKENS", null);
            assertEquals(0L, owner.queryForObject("SELECT count(*) FROM agent_runtime_budget_result_receipts WHERE tenant_id='7' AND attempt_id=?", Long.class, request.attemptId()));
            replayOriginal(request);
        } finally {
            owner.execute("DROP TRIGGER IF EXISTS fixture_reject_unknown_finish ON nw_external_invocations");
            owner.execute("DROP FUNCTION fixture_reject_unknown_finish()");
        }
    }

    private static CostContext costContext() {
        return CostContext.builder().eventId("aux-test-" + UUID.randomUUID()).callPoint("test").scopeType("control_operation").scopeId("scope-auxiliary-test").build();
    }

    private ExternalInvocationRequest invokeAndCapture(boolean unknown) throws Exception {
        return invokeAndCapture(unknown, false);
    }

    private ExternalInvocationRequest invokeAndCapture(boolean unknown, boolean accountingFailure) throws Exception {
        try (var scope = MeterScope.open(costContext())) {
            var action = (org.junit.jupiter.api.function.Executable) () -> service.get().generatePinned("7", "10", 1, "system", "question",
                    new ModelPort.ResponseFormat.Text(), Duration.ofSeconds(4), 73, () -> false);
            if (unknown) {
                var failure = assertThrows(LlmService.ModelOutcomeUnknownException.class, action);
                if (accountingFailure) {
                    var cause = assertInstanceOf(ExternalInvocationGuard.OutcomeUnknownException.class, failure.getCause());
                    assertEquals(1, cause.getSuppressed().length);
                    assertTrue(cause.getSuppressed()[0].getMessage().contains("fixture report publication failure"));
                }
            } else {
                var failure = assertThrows(com.skillforge.core.shared.exception.ApiException.class, action);
                assertEquals("LLM 服务调用失败", failure.getDetail());
            }
        }
        var request = ArgumentCaptor.forClass(ExternalInvocationRequest.class);
        verify(guard).invokeModel(eq("7"), request.capture(), any(), any());
        verify(provider, times(1)).generate(anyString(), any());
        return request.getValue();
    }

    private void replayOriginal(ExternalInvocationRequest request) throws Exception {
        var budget = ArgumentCaptor.forClass(AuxiliaryModelBudgets.Request.class);
        verify(guard).invokeModel(eq("7"), eq(request), budget.capture(), any());
        var invocationBefore = owner.queryForMap("SELECT status,error_code,ended_at FROM nw_external_invocations WHERE tenant_id=7 AND attempt_id=?", request.attemptId());
        var attemptBefore = owner.queryForMap("SELECT state,result_reference FROM agent_runtime_budget_attempts WHERE tenant_id='7' AND attempt_id=?", request.attemptId());
        var amountsBefore = owner.queryForList("SELECT dimension,reserved,reported,settled FROM agent_runtime_budget_attempt_amounts WHERE tenant_id='7' AND attempt_id=? ORDER BY dimension", request.attemptId());
        var balancesBefore = owner.queryForList("SELECT scope_kind,dimension,consumed,reserved FROM agent_runtime_budget_balances WHERE tenant_id='7' AND scope_id=? ORDER BY scope_kind,dimension", "maintenance:" + request.invocationId());
        var eventsBefore = owner.queryForList("SELECT event_id,usage_event_kind FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? ORDER BY event_id", request.attemptId());
        var receiptsBefore = owner.queryForList("SELECT result_reference FROM agent_runtime_budget_result_receipts WHERE tenant_id='7' AND attempt_id=? ORDER BY result_reference", request.attemptId());
        var sends = new AtomicInteger();
        var duplicate = assertThrows(ExternalInvocationGuard.DuplicateAttemptException.class,
                () -> guard.invokeModel("7", request, budget.getValue(), () -> { sends.incrementAndGet(); return null; }));
        assertEquals("External invocation attempt already exists: " + request.attemptId(), duplicate.getMessage());
        assertEquals(0, sends.get()); verify(provider, times(1)).generate(anyString(), any());
        assertEquals(invocationBefore, owner.queryForMap("SELECT status,error_code,ended_at FROM nw_external_invocations WHERE tenant_id=7 AND attempt_id=?", request.attemptId()));
        assertEquals(attemptBefore, owner.queryForMap("SELECT state,result_reference FROM agent_runtime_budget_attempts WHERE tenant_id='7' AND attempt_id=?", request.attemptId()));
        assertEquals(amountsBefore, owner.queryForList("SELECT dimension,reserved,reported,settled FROM agent_runtime_budget_attempt_amounts WHERE tenant_id='7' AND attempt_id=? ORDER BY dimension", request.attemptId()));
        assertEquals(balancesBefore, owner.queryForList("SELECT scope_kind,dimension,consumed,reserved FROM agent_runtime_budget_balances WHERE tenant_id='7' AND scope_id=? ORDER BY scope_kind,dimension", "maintenance:" + request.invocationId()));
        assertEquals(eventsBefore, owner.queryForList("SELECT event_id,usage_event_kind FROM nw_usage_events WHERE tenant_id=7 AND attempt_id=? ORDER BY event_id", request.attemptId()));
        assertEquals(receiptsBefore, owner.queryForList("SELECT result_reference FROM agent_runtime_budget_result_receipts WHERE tenant_id='7' AND attempt_id=? ORDER BY result_reference", request.attemptId()));
    }

    private void assertBudget(ExternalInvocationRequest request, String dimension, Long reported) {
        var row = owner.queryForMap("SELECT reported,reserved,settled FROM agent_runtime_budget_attempt_amounts WHERE tenant_id='7' AND attempt_id=? AND dimension=?", request.attemptId(), dimension);
        assertEquals(reported, row.get("reported")); assertEquals(reported != null, row.get("settled"));
        if (reported == null) assertTrue(((Number) row.get("reserved")).longValue() > 0);
        var balance = owner.queryForMap("SELECT consumed,reserved FROM agent_runtime_budget_balances WHERE tenant_id='7' AND scope_kind='ROOT' AND scope_id=? AND dimension=?", "maintenance:" + request.invocationId(), dimension);
        assertEquals(reported == null ? 0L : reported, balance.get("consumed"));
        assertEquals(reported == null ? row.get("reserved") : 0L, balance.get("reserved"));
    }

    private String status(ExternalInvocationRequest request) {
        return owner.queryForObject("SELECT status FROM nw_external_invocations WHERE tenant_id=7 AND attempt_id=?", String.class, request.attemptId());
    }
    private static ModelPort.ModelUsage usage(OptionalLong input, OptionalLong output) {
        return new ModelPort.ModelUsage(input, output, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
    }
}
