package com.skillforge.core.foundation.application;

import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.foundation.application.TenantDirectoryService;

import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.application.TenantBillingProfileService.ReconciliationProfile;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 在真实 PostgreSQL 验证正式 Mapper 的列更新和 JSONB/null 绑定；不替代完整迁移或身份验收。 */
@Testcontainers(disabledWithoutDocker = false)
class TenantBillingProfileDatabaseTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE nw_tenants (
                    id bigint PRIMARY KEY, name text NOT NULL, status text NOT NULL, plan_id bigint,
                    override_quota_api_calls_per_month integer,
                    usage_reconciliation_provider text, usage_reconciliation_endpoint text,
                    usage_reconciliation_api_key_encrypted text, usage_reconciliation_api_key_header text,
                    usage_reconciliation_response_schema jsonb
                )
                """);
        var configuration = new Configuration(new Environment("tenant-profile-test", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/TenantMapper.xml";
        try (var xml = TenantBillingProfileDatabaseTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml, resource);
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void prepareRows() {
        jdbc.update("DELETE FROM nw_tenants");
        jdbc.update("INSERT INTO nw_tenants(id,name,status,plan_id) VALUES (7,'原机构','active',17),(8,'其他机构','disabled',18)");
    }

    @Test
    void clearCredentialAndProviderPersistNullWithoutChangingOtherTenantFields() {
        try (var session = sessions.openSession(false)) {
            var service = new TenantBillingProfileService(session.getMapper(TenantMapper.class));
            service.saveReconciliationProfile(7, new ReconciliationProfile("http", "https://billing.example",
                    "encrypted-secret", "Authorization", Map.of("facts_path", "data.rows")));
            session.commit();
            assertEquals("data.rows", jdbc.queryForObject(
                    "SELECT usage_reconciliation_response_schema ->> 'facts_path' FROM nw_tenants WHERE id=7", String.class));

            service.saveReconciliationProfile(7, new ReconciliationProfile("http", "https://billing.example",
                    null, "Authorization", Map.of()));
            session.commit();
            assertNull(jdbc.queryForObject("SELECT usage_reconciliation_api_key_encrypted FROM nw_tenants WHERE id=7", String.class));
            assertEquals("http", jdbc.queryForObject("SELECT usage_reconciliation_provider FROM nw_tenants WHERE id=7", String.class));

            service.saveReconciliationProfile(7, new ReconciliationProfile(null, null, null, null, null));
            session.commit();
        }
        var row = jdbc.queryForMap("""
                SELECT name, status, plan_id, usage_reconciliation_provider, usage_reconciliation_endpoint,
                       usage_reconciliation_api_key_encrypted, usage_reconciliation_api_key_header,
                       usage_reconciliation_response_schema FROM nw_tenants WHERE id=7
                """);
        assertEquals("原机构", row.get("name"));
        assertEquals("active", row.get("status"));
        assertEquals(17L, row.get("plan_id"));
        for (String key : List.of("usage_reconciliation_provider", "usage_reconciliation_endpoint",
                "usage_reconciliation_api_key_encrypted", "usage_reconciliation_api_key_header",
                "usage_reconciliation_response_schema")) assertNull(row.get(key), key);
        assertNull(jdbc.queryForObject("SELECT usage_reconciliation_provider FROM nw_tenants WHERE id=8", String.class));
    }

    @Test
    void directorySeparatesAllTenantIdsFromActiveTenantIds() {
        try (var session = sessions.openSession()) {
            var directory = new TenantDirectoryService(session.getMapper(TenantMapper.class));
            assertEquals(List.of("7", "8"), directory.tenantIds());
            assertEquals(List.of("7"), directory.activeTenantIds());
        }
    }

    @Test
    void callerTransactionCanRollbackProfileChanges() {
        try (var session = sessions.openSession(false)) {
            var service = new TenantBillingProfileService(session.getMapper(TenantMapper.class));
            service.saveReconciliationProfile(7, new ReconciliationProfile("http", "https://billing.example",
                    "encrypted-secret", "Authorization", Map.of()));
            session.rollback();
        }
        assertNull(jdbc.queryForObject("SELECT usage_reconciliation_provider FROM nw_tenants WHERE id=7", String.class));
    }

    @Test
    void billingCandidatesAndStatusCasPreserveConcurrentSuspension() {
        jdbc.update("INSERT INTO nw_tenants(id,name,status,plan_id,override_quota_api_calls_per_month) VALUES (9,'预警机构','warning',19,3)");
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(TenantMapper.class);
            var service = new TenantBillingProfileService(mapper);
            var candidates = service.billingCandidates();
            assertEquals(List.of(7L, 9L), candidates.stream().map(TenantBillingProfileService.BillingCandidate::tenantId).toList());
            assertNull(candidates.getFirst().overrideQuotaApiCallsPerMonth());
            assertEquals(3, candidates.getLast().overrideQuotaApiCallsPerMonth());
            assertThrows(UnsupportedOperationException.class, candidates::clear);

            jdbc.update("UPDATE nw_tenants SET status='suspended' WHERE id=9");
            com.skillforge.common.context.TenantContext.runWithTenant("9", () -> {
                assertEquals(0, service.transitionBillingState(9, "warning", "active"));
                assertThrows(IllegalArgumentException.class, () -> service.transitionBillingState(9, "suspended", "active"));
            });
            assertEquals(0, mapper.transitionBillingState(9, "suspended", "active"));
            com.skillforge.common.context.TenantContext.runWithTenant("7", () -> {
                assertEquals(1, service.transitionBillingState(7, "active", "warning"));
                assertEquals(0, service.transitionBillingState(7, "active", "warning"));
                assertThrows(SecurityException.class, () -> service.transitionBillingState(9, "warning", "active"));
            });
            session.commit();
        }
        assertEquals("suspended", jdbc.queryForObject("SELECT status FROM nw_tenants WHERE id=9", String.class));
        assertEquals("warning", jdbc.queryForObject("SELECT status FROM nw_tenants WHERE id=7", String.class));
        assertEquals("disabled", jdbc.queryForObject("SELECT status FROM nw_tenants WHERE id=8", String.class));
    }
}
