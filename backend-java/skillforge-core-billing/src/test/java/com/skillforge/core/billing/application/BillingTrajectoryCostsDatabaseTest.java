package com.skillforge.core.billing.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.application.BillingTraceFacts.EffectiveCost;
import com.skillforge.core.billing.application.BillingTraceFacts.ReadScope;
import com.skillforge.core.billing.export.BillingAuthorizedSqlRule;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.foundation.access.PlatformObservationTargetMapper;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式有效金额视图、精确来源与受限角色共同验证；不在查询层重定价或修改既有账本。 */
@Testcontainers(disabledWithoutDocker = false)
class BillingTrajectoryCostsDatabaseTest {
    private static final String ROLE = "trajectory_cost_reader";
    private static final String PASSWORD = "isolated-trajectory-cost-reader";
    private static final ReadScope RECONCILED = new ReadScope("session:reconciled", "run:reconciled", "root:reconciled");
    private static final ReadScope UNKNOWN = new ReadScope("session:unknown", "run:unknown", "root:unknown");
    private static final ReadScope EXACT = new ReadScope("session:exact", "run:exact", "root:exact");
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate owner;
    private static JdbcTemplate readerJdbc;
    private static TransactionTemplate reader;
    private static CostEventMapper mapper;
    private static BillingTraceQueryService service;

    @BeforeAll static void database() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var admin = source(DATABASE.getUsername(), DATABASE.getPassword());
        owner = new JdbcTemplate(admin);
        try (var connection = admin.getConnection()) {
            CurrentHostFixtures.tenant(connection, 1L);
            CurrentHostFixtures.tenant(connection, 2L);
        }
        seed();
        owner.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + ROLE);
        // 正式V319的RLS布尔函数仍须显式EXECUTE，不给任意函数或数据写权。
        owner.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO " + ROLE);
        var dataSource = source(ROLE, PASSWORD);
        var configuration = new Configuration();
        configuration.addInterceptor(new TenantRlsInterceptor(List.of(new BillingAuthorizedSqlRule())));
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource); factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/CostEventMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CostEventMapper.class);
        service = new BillingTraceQueryService(mapper, mock(ExternalInvocationMapper.class));
        readerJdbc = new JdbcTemplate(dataSource);
        reader = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        reader.setReadOnly(true);
    }

    @AfterEach void identity() {
        TenantContext.clear();
        assertNull(PlatformObservationAccess.currentAuthorization());
    }

    @Test void latestReconciliationReplacesOriginalWhileSignedAdjustmentsKeepExactConvertedCents() {
        var facts = tenant(1L, () -> service.effectiveCostsForScopes(1L, List.of(RECONCILED, RECONCILED)));
        assertEquals(1, facts.size());
        var fact = facts.getFirst();
        assertEquals("measured:reconciled", fact.eventId());
        assertEquals("attempt:reconciled", fact.attemptId());
        assertEquals(RECONCILED, fact.scope()); assertEquals("priced", fact.costStatus());
        assertEquals("CNY", fact.currency());
        var expected = new BigDecimal("2.333333333333").multiply(new BigDecimal("7.1"))
                .add(new BigDecimal("0.125").multiply(new BigDecimal("7.2"))).subtract(new BigDecimal("0.025"));
        assertEquals(0, expected.compareTo(fact.confirmedCostCents()));
        assertNotEquals(0L, fact.confirmedCostCents().signum());
        assertTrue(fact.confirmedCostCents().scale() > 2, "不得先取整分值再显示金额");
    }

    @Test void unknownUnpricedPartialAndExactZeroRemainDistinctWithoutSyntheticPrices() {
        var facts = byEvent(tenant(1L, () -> service.effectiveCostsForScopes(1L, List.of(UNKNOWN))));
        assertEquals(5, facts.size());
        assertEquals("unpriced", facts.get("measured:unpriced").costStatus());
        assertNull(facts.get("measured:unpriced").confirmedCostCents());
        assertEquals("unresolved", facts.get("measured:unknown-usage").costStatus());
        assertNull(facts.get("measured:unknown-usage").confirmedCostCents());
        assertEquals("unresolved", facts.get("measured:missing-price").costStatus());
        assertNull(facts.get("measured:missing-price").confirmedCostCents());
        assertEquals("priced", facts.get("measured:zero").costStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(facts.get("measured:zero").confirmedCostCents()));
        assertEquals("unresolved", facts.get("measured:partial").costStatus());
        assertEquals(0, new BigDecimal("1.25").compareTo(facts.get("measured:partial").confirmedCostCents()));
    }

    @Test void allScopeIdentitiesAndRlsAreRequiredAndSeparateMeasuredEventsOnOneAttemptAreRetained() {
        var facts = tenant(1L, () -> service.effectiveCostsForScopes(1L, List.of(EXACT)));
        assertEquals(List.of("measured:exact-one", "measured:exact-two"), facts.stream().map(EffectiveCost::eventId).toList());
        assertEquals(1, facts.stream().map(EffectiveCost::attemptId).distinct().count());
        assertTrue(tenant(1L, () -> service.effectiveCostsForScopes(2L, List.of(EXACT))).isEmpty());
        assertTrue(tenant(1L, () -> service.effectiveCostsForScopes(1L,
                List.of(new ReadScope(EXACT.sessionId(), "nonexistent-run", EXACT.rootInputId())))).isEmpty());
        assertTrue(tenant(1L, () -> service.effectiveCostsForScopes(1L,
                List.of(new ReadScope(EXACT.sessionId(), EXACT.runId(), "nonexistent-root")))).isEmpty());
        assertTrue(tenant(1L, () -> service.effectiveCostsForScopes(1L,
                List.of(new ReadScope("nonexistent-session", EXACT.runId(), EXACT.rootInputId())))).isEmpty());
        assertEquals(List.of("measured:other-run"), tenant(1L, () -> service.effectiveCostsForScopes(1L,
                List.of(new ReadScope(EXACT.sessionId(), "run:other", EXACT.rootInputId())))).stream().map(EffectiveCost::eventId).toList());
    }

    @Test void platformWindowUsesOnlyTheExactStatementTargetAndScopesThenRestoresConnectionIdentity() {
        var foundation = mock(FoundationAccess.class);
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "901"));
        var platform = new PlatformObservationAccess(foundation, mock(PlatformObservationTargetMapper.class));
        reader.executeWithoutResult(status -> {
            assertEquals(2, platform.read(1L, () -> {
                assertNull(TenantContext.getTenantId());
                return service.effectiveCostsForScopes(1L, List.of(EXACT));
            }).size());
            assertEquals("", readerJdbc.queryForObject("SELECT COALESCE(current_setting('app.tenant_id',true),'')", String.class));
            assertNotEquals("true", readerJdbc.queryForObject("SELECT COALESCE(current_setting('app.platform_admin',true),'')", String.class));
            assertEquals(List.of("measured:other-tenant"), platform.read(2L,
                    () -> service.effectiveCostsForScopes(2L, List.of(EXACT))).stream().map(EffectiveCost::eventId).toList());
            assertThrows(RuntimeException.class, () -> platform.read(1L, () -> service.effectiveCostsForScopes(2L, List.of(EXACT))));
            assertThrows(RuntimeException.class, () -> platform.read(1L, () -> mapper.listByRootInputId(1L, EXACT.rootInputId())));
            assertNull(TenantContext.getTenantId());
        });
        verify(foundation, atLeastOnce()).require(any(FoundationAccess.Subject.class), eq("foundation.sessions.read"));
        assertEquals(Boolean.FALSE, readerJdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
    }

    @Test void directMapperEmptyAndOversizedScopesFailClosedWithoutScanningATenant() {
        assertTrue(tenant(1L, () -> mapper.listEffectiveCostsByScopes(1L, List.of())).isEmpty());
        assertTrue(tenant(1L, () -> mapper.listEffectiveCostsByScopes(1L, java.util.Collections.nCopies(201, EXACT))).isEmpty());
    }

    private static void seed() {
        event(1001, 1, "measured:reconciled", "measured", null, "priced", "99", "USD", "9", RECONCILED, "attempt:reconciled", 1, "PROVIDER_REPORTED", "llm.chat");
        event(1004, 1, "reconciled:older", "reconciliation", 1001L, "priced", "5", "CNY", "1", RECONCILED, "attempt:reconciled", 10, "PROVIDER_REPORTED", "llm.chat");
        event(1003, 1, "reconciled:latest", "reconciliation", 1001L, "priced", "2.333333333333", "USD", "7.1", RECONCILED, "attempt:reconciled", 20, "PROVIDER_REPORTED", "llm.chat");
        event(1005, 1, "adjusted:positive", "adjustment", 1001L, "priced", "0.125", "USD", "7.2", RECONCILED, "attempt:reconciled", 21, "PROVIDER_REPORTED", "llm.chat");
        event(1006, 1, "adjusted:negative", "adjustment", 1001L, "priced", "-0.025", "CNY", "1", RECONCILED, "attempt:reconciled", 22, "PROVIDER_REPORTED", "llm.chat");
        event(1007, 1, "report:not-cost", "usage_report", null, "unresolved", null, null, null, RECONCILED, "attempt:reconciled", 23, "PROVIDER_REPORTED", "llm.chat");
        event(2001, 1, "measured:unpriced", "measured", null, "unpriced", null, null, null, UNKNOWN, "attempt:unpriced", 1, "PROVIDER_REPORTED", "llm.chat");
        event(2002, 1, "measured:unknown-usage", "measured", null, "priced", "12", "CNY", "1", UNKNOWN, "attempt:unknown", 2, "UNKNOWN", "llm.chat");
        event(2003, 1, "measured:missing-price", "measured", null, "priced", null, "CNY", "1", UNKNOWN, "attempt:missing", 3, "PROVIDER_REPORTED", "llm.chat");
        event(2004, 1, "measured:zero", "measured", null, "priced", "0", "CNY", "1", UNKNOWN, "attempt:zero", 4, "PROVIDER_REPORTED", "llm.chat");
        event(2005, 1, "measured:partial", "measured", null, "priced", "1.25", "CNY", "1", UNKNOWN, "attempt:partial", 5, "PROVIDER_REPORTED", "llm.chat");
        event(2006, 1, "adjusted:unknown", "adjustment", 2005L, "unresolved", null, null, null, UNKNOWN, "attempt:partial", 6, "PROVIDER_REPORTED", "llm.chat");
        event(3001, 1, "measured:exact-one", "measured", null, "priced", "0.001", "CNY", "1", EXACT, "attempt:shared", 1, "PROVIDER_REPORTED", "llm.chat");
        event(3002, 1, "measured:exact-two", "measured", null, "priced", "0.002", "CNY", "1", EXACT, "attempt:shared", 2, "PROVIDER_REPORTED", "llm.chat");
        event(3003, 1, "measured:other-run", "measured", null, "priced", "10", "CNY", "1", new ReadScope(EXACT.sessionId(), "run:other", EXACT.rootInputId()), "attempt:other-run", 3, "PROVIDER_REPORTED", "llm.chat");
        event(3004, 1, "measured:other-root", "measured", null, "priced", "20", "CNY", "1", new ReadScope(EXACT.sessionId(), EXACT.runId(), "root:other"), "attempt:other-root", 4, "PROVIDER_REPORTED", "llm.chat");
        event(3005, 1, "measured:other-session", "measured", null, "priced", "30", "CNY", "1", new ReadScope("session:other", EXACT.runId(), EXACT.rootInputId()), "attempt:other-session", 5, "PROVIDER_REPORTED", "llm.chat");
        event(3006, 2, "measured:other-tenant", "measured", null, "priced", "40", "CNY", "1", EXACT, "attempt:other-tenant", 6, "PROVIDER_REPORTED", "llm.chat");
        event(3007, 1, "request:excluded", "measured", null, "not_applicable", null, null, null, EXACT, null, 7, "UNKNOWN", "request");
    }

    private static void event(long id, long tenant, String event, String kind, Long original, String status,
            String amount, String currency, String rate, ReadScope scope, String attempt, int second, String source, String type) {
        owner.update("""
                INSERT INTO nw_usage_events(id,tenant_id,event_id,event_type,usage_event_kind,adjustment_of_event_id,
                  amount_status,total_amount,currency,exchange_rate_to_cny,occurred_at,ingested_at,
                  session_id,run_id,root_input_id,attempt_id,usage_source,report_revision,origin_kind,actor_ref,responsibility_ref)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'system','system:trajectory-cost-fixture',?)
                """, id, tenant, event, type, kind, original, status, amount == null ? null : new BigDecimal(amount),
                currency, rate == null ? null : new BigDecimal(rate), Timestamp.from(Instant.EPOCH.plusSeconds(second)),
                Timestamp.from(Instant.EPOCH.plusSeconds(second)), scope.sessionId(), scope.runId(), scope.rootInputId(),
                attempt, source, "usage_report".equals(kind) ? 1L : null, "tenant:" + tenant);
    }

    private static <T> T tenant(long tenant, java.util.function.Supplier<T> work) {
        TenantContext.setTenantId(Long.toString(tenant));
        return reader.execute(status -> work.get());
    }
    private static Map<String, EffectiveCost> byEvent(List<EffectiveCost> values) {
        return values.stream().collect(Collectors.toMap(EffectiveCost::eventId, Function.identity()));
    }
    private static DataSource source(String user, String password) {
        var source = new PGSimpleDataSource(); source.setURL(DATABASE.getJdbcUrl());
        source.setUser(user); source.setPassword(password); return source;
    }
}
