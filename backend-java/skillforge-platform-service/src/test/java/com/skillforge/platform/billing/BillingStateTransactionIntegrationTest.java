package com.skillforge.platform.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.application.BillingStateService;
import com.skillforge.core.billing.export.BillingAuthorizedSqlRule;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.foundation.persistence.TenantMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式迁移、平台非超管角色与真实Mapper组合，扫描变更和手动回执必须原子提交。 */
@Testcontainers(disabledWithoutDocker=false)
class BillingStateTransactionIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate owner;
    private static JdbcTemplate caller;
    private static TransactionTemplate transactions;
    private static PlatformBillingScanService scans;
    private static TenantMapper tenants;

    @BeforeAll static void database() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        var admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        owner=new JdbcTemplate(admin);
        try(var connection=admin.getConnection()){CurrentHostFixtures.tenant(connection,1);CurrentHostFixtures.tenant(connection,2);}
        owner.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(42,'billing-scan-fixture','synthetic-not-login-hash','active')");
        owner.update("UPDATE nw_billing_plans SET quota_api_calls_per_month=10 WHERE id=(SELECT plan_id FROM nw_tenants WHERE id=2)");
        owner.update("UPDATE nw_tenants SET override_quota_api_calls_per_month=1 WHERE id=1");
        var occurred=Timestamp.from(Instant.parse("2026-11-01T01:00:00Z"));
        owner.update("""
                INSERT INTO nw_usage_events(id,tenant_id,event_id,event_type,usage_event_kind,amount_status,
                    quantity,occurred_at,ingested_at,usage_source,origin_kind,actor_ref,responsibility_ref)
                VALUES(501,1,'billing-scan-request','request','measured','not_applicable',1,?,?,'UNKNOWN','system','system:billing-scan-fixture','tenant:1')
                """,occurred,occurred);
        owner.execute("CREATE ROLE billing_scan_platform LOGIN PASSWORD 'synthetic-billing-platform' NOSUPERUSER BYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO billing_scan_platform");
        owner.execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO billing_scan_platform");
        owner.execute("""
                CREATE FUNCTION billing_scan_test_fail_complete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.operation_id='fail-complete' THEN RAISE EXCEPTION 'synthetic_receipt_failure'; END IF; RETURN NEW; END $$
                """);
        owner.execute("CREATE TRIGGER billing_scan_test_fail BEFORE UPDATE OF completed_at ON nw_platform_billing_scans FOR EACH ROW EXECUTE FUNCTION billing_scan_test_fail_complete()");
        var source=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"billing_scan_platform","synthetic-billing-platform");
        caller=new JdbcTemplate(source);transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration=new MybatisConfiguration(new Environment("billing-scan",new SpringManagedTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor(List.of(new BillingAuthorizedSqlRule())));
        configuration.addMapper(PlanMapper.class);
        for(String path:List.of("mapper/TenantMapper.xml","mapper/CostEventMapper.xml","mapper/BillingScanMapper.xml")){
            try(var xml=new ClassPathResource(path).getInputStream()){
                new XMLMapperBuilder(xml,configuration,path,configuration.getSqlFragments()).parse();
            }
        }
        var sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        tenants=sessions.getMapper(TenantMapper.class);
        var billing=new BillingStateService(new TenantBillingProfileService(tenants),sessions.getMapper(PlanMapper.class),
                sessions.getMapper(CostEventMapper.class),Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"),ZoneOffset.UTC));
        scans=new PlatformBillingScanService(sessions.getMapper(BillingScanMapper.class),billing);
    }

    @BeforeEach void initialStates(){TenantContext.clear();owner.update("UPDATE nw_tenants SET status=CASE WHEN id=1 THEN 'active' ELSE 'warning' END WHERE id IN(1,2)");}
    @AfterEach void clear(){TenantContext.clear();}

    @Test void twoTenantChangesAndReceiptCommitTogetherAndReplayKeepsOriginalCounters(){
        var first=transactions.execute(status->scans.process(42,"two-tenant-scan"));
        assertEquals(2,first.scanned());assertEquals(1,first.warning());assertEquals(1,first.active());
        assertEquals("warning",owner.queryForObject("SELECT status FROM nw_tenants WHERE id=1",String.class));
        assertEquals("active",owner.queryForObject("SELECT status FROM nw_tenants WHERE id=2",String.class));
        assertEquals(first,transactions.execute(status->scans.process(42,"two-tenant-scan")));
        assertNull(TenantContext.getTenantId());
        transactions.executeWithoutResult(status->assertEquals("",caller.queryForObject("SELECT COALESCE(current_setting('app.tenant_id',true),'')",String.class)));
    }

    @Test void receiptCompletionFailureRollsBackBothTenantChangesAndClaim(){
        assertThrows(RuntimeException.class,()->transactions.execute(status->scans.process(42,"fail-complete")));
        assertEquals("active",owner.queryForObject("SELECT status FROM nw_tenants WHERE id=1",String.class));
        assertEquals("warning",owner.queryForObject("SELECT status FROM nw_tenants WHERE id=2",String.class));
        assertEquals(0L,owner.queryForObject("SELECT count(*) FROM nw_platform_billing_scans WHERE operation_id='fail-complete'",Long.class));
        assertNull(TenantContext.getTenantId());
    }

    @Test void billingWindowRejectsUnrelatedMapperAndOrdinaryTransactionStillRejectsTenantSwitch(){
        transactions.executeWithoutResult(status->assertThrows(RuntimeException.class,()->
                TenantRlsInterceptor.withBillingScanTarget(1,()->tenants.selectTenantIds())));
        assertNull(TenantContext.getTenantId());
        transactions.executeWithoutResult(status->{
            TenantContext.setTenantId("1");tenants.selectTenantIds();
            TenantContext.setTenantId("2");assertThrows(RuntimeException.class,()->tenants.selectTenantIds());
        });
    }
}
