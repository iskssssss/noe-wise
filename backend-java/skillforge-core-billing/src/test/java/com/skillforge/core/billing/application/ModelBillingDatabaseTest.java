package com.skillforge.core.billing.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.dto.billing.ModelBillingContracts.Monthly;
import com.skillforge.core.billing.persistence.ModelBillingMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
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

/** 完整迁移、正式有效成本视图及受限角色验证月度模型费用，无售价或发票写入。 */
@Testcontainers(disabledWithoutDocker = false)
class ModelBillingDatabaseTest {
    private static final String ROLE = "model_bill_reader";
    private static final String PASSWORD = "isolated-model-bill-reader";
    private static final Instant FROM = Instant.parse("2026-08-31T16:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T16:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-09-01T00:00:00Z");
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate owner;
    private static JdbcTemplate readerJdbc;
    private static TransactionTemplate reader;
    private static ModelBillingMapper mapper;
    private static ModelBillingService service;
    private String previousTenant;

    @BeforeAll static void database() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var admin = source(DATABASE.getUsername(), DATABASE.getPassword()); owner = new JdbcTemplate(admin);
        try (var connection = admin.getConnection()) {
            CurrentHostFixtures.tenant(connection, 1L); CurrentHostFixtures.tenant(connection, 2L);
        }
        owner.update("INSERT INTO nw_platform_models(id,draft) VALUES(910000001,'{}'::jsonb)");
        owner.update("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(910000001,1,'{\"inputPricePerMillionCents\":\"1\"}'::jsonb,'synthetic')");
        owner.update("UPDATE nw_platform_models SET current_revision=1 WHERE id=910000001");
        seed();
        owner.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT ON public.nw_usage_events,public.billing_effective_usage TO " + ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO " + ROLE);
        var dataSource = source(ROLE, PASSWORD); readerJdbc = new JdbcTemplate(dataSource);
        var configuration = new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor());
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/ModelBillingMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ModelBillingMapper.class);
        var access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "42"));
        service = new ModelBillingService(mapper, access, Clock.fixed(TO, ZoneOffset.UTC));
        reader = new TransactionTemplate(new DataSourceTransactionManager(dataSource)); reader.setReadOnly(true);
    }

    @org.junit.jupiter.api.BeforeEach void saveIdentity() { previousTenant = TenantContext.getTenantId(); }
    @AfterEach void identity() {
        if (previousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(previousTenant);
    }

    @Test void sevenModelKindsUseEffectiveCostsOnceAndExcludeOtherAuditOperations() {
        Monthly bill = bill(1L, "2026-09", 1, 200);
        assertEquals(9L, bill.modelCalls()); assertEquals(9L, bill.total());
        assertEquals(Set.of("llm.chat", "llm.embedding", "llm.rerank", "media.image", "media.video", "media.transcription", "media.speech"),
                bill.items().stream().map(line -> line.eventType()).collect(Collectors.toSet()));
        assertEquals(0, new BigDecimal("0.17875").compareTo(new BigDecimal(bill.knownAmount())));
        assertNull(bill.totalAmount()); assertEquals(4L, bill.missingCostCount());
        assertEquals("146", bill.promptTokens()); assertEquals("22", bill.completionTokens());
        var corrected = bill.items().stream().filter(line -> line.id().equals("1001")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.03").compareTo(new BigDecimal(corrected.amount())));
        var foreign = bill.items().stream().filter(line -> line.id().equals("1002")).findFirst().orElseThrow();
        assertEquals("USD", foreign.originalCurrency());
        assertEquals(0, new BigDecimal("0.02").compareTo(new BigDecimal(foreign.originalAmount())));
        assertEquals(0, new BigDecimal("0.145").compareTo(new BigDecimal(foreign.amount())));
        var withoutFx = bill.items().stream().filter(line -> line.id().equals("1005")).findFirst().orElseThrow();
        assertEquals("USD", withoutFx.originalCurrency()); assertNotNull(withoutFx.originalAmount());
        assertNull(withoutFx.amount()); assertEquals("unresolved", withoutFx.costStatus());
    }

    @Test void reportRevisionsMergePartialTokensAndStayUnknownWithoutATerminal() {
        var bill = bill(1L, "2026-09", 1, 200);
        var pending = bill.items().stream().filter(line -> "pending-chat".equals(line.attemptId())).toList();
        assertEquals(1, pending.size()); assertEquals("9", pending.getFirst().promptTokens());
        assertEquals("4", pending.getFirst().completionTokens()); assertNull(pending.getFirst().amount());
        assertEquals("unresolved", pending.getFirst().costStatus());
        assertEquals(1, bill.items().stream().filter(line -> "chat-paid".equals(line.attemptId())).count());
        assertEquals(1, bill.items().stream().filter(line -> "pending-image".equals(line.attemptId())).count());
        assertFalse(bill.items().stream().anyMatch(line -> "not-submitted".equals(line.attemptId())));
        assertEquals(2L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE attempt_id='not-submitted'", Long.class));
    }

    @Test void exactZeroAndBeijingMonthBoundsAreDistinctFromMissingCost() {
        var bill = bill(1L, "2026-09", 1, 200);
        var zero = bill.items().stream().filter(line -> line.id().equals("1003")).findFirst().orElseThrow();
        assertEquals("priced", zero.costStatus()); assertEquals(0, new BigDecimal(zero.amount()).signum());
        assertEquals(1, bill(1L, "2026-08", 1, 20).modelCalls());
        var october = bill(1L, "2026-10", 1, 20);
        assertEquals(1, october.modelCalls()); assertEquals(TO, october.items().getFirst().occurredAt());
        assertTrue(bill.items().stream().noneMatch(line -> line.id().equals("1101") || line.id().equals("1102")));
    }

    @Test void pageAndSummaryUseTheSameScopeWithStableOrdering() {
        var all = bill(1L, "2026-09", 1, 200);
        var first = bill(1L, "2026-09", 1, 2); var second = bill(1L, "2026-09", 2, 2);
        assertEquals(all.items().subList(0, 2), first.items()); assertEquals(all.items().subList(2, 4), second.items());
        assertEquals(all.modelCalls(), second.modelCalls()); assertEquals(all.knownAmount(), second.knownAmount());
        assertEquals(all.missingCostCount(), second.missingCostCount());
    }

    @Test void restrictedRoleCannotReadAnotherTenantEvenThroughTheMapper() {
        var own = bill(2L, "2026-09", 1, 20);
        assertEquals(1, own.modelCalls()); assertEquals("2001", own.items().getFirst().id());
        assertEquals(0, new BigDecimal("4").compareTo(new BigDecimal(own.totalAmount())));
        TenantContext.setTenantId("1");
        var unauthorized = reader.execute(status -> mapper.summarize(2L, FROM, TO));
        assertEquals(0, unauthorized.getModelCalls());
        assertTrue(reader.execute(status -> mapper.list(2L, FROM, TO, 0L, 20)).isEmpty());
        assertEquals(Boolean.FALSE, readerJdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
    }

    @Test void newCurrentModelPricesDoNotRepriceOrWriteAnyOriginalUsage() {
        var before = bill(1L, "2026-09", 1, 200);
        String original = owner.queryForObject("SELECT jsonb_agg(to_jsonb(e) ORDER BY id)::text FROM nw_usage_events e", String.class);
        owner.update("INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(910000001,2,'{\"inputPricePerMillionCents\":\"9999\"}'::jsonb,'synthetic')");
        owner.update("UPDATE nw_platform_models SET current_revision=2 WHERE id=910000001");
        assertEquals(before, bill(1L, "2026-09", 1, 200));
        assertEquals(original, owner.queryForObject("SELECT jsonb_agg(to_jsonb(e) ORDER BY id)::text FROM nw_usage_events e", String.class));
        // 读取角色没有模型目录权限；服务若绕过原用量重读当前价格，本场景会直接失败。
        assertEquals(Boolean.FALSE, readerJdbc.queryForObject("SELECT has_table_privilege(current_user,'nw_platform_models','SELECT')", Boolean.class));
    }

    @Test void migrationRemovesOnlyTheRetiredSalesStorageAndKeepsModelFacts() {
        for (String table : List.of("nw_billing_sales_prices", "nw_billing_price_audit", "nw_billing_invoices", "nw_billing_invoice_usage_claims"))
            assertNull(owner.queryForObject("SELECT to_regclass(?)::text", String.class, "public." + table));
        for (String function : List.of("protect_billing_immutable()", "protect_sealed_invoice()"))
            assertNull(owner.queryForObject("SELECT to_regprocedure(?)::text", String.class, "public." + function));
        assertNotNull(owner.queryForObject("SELECT to_regclass('public.nw_usage_events')::text", String.class));
        assertNotNull(owner.queryForObject("SELECT to_regclass('public.nw_platform_model_versions')::text", String.class));
        assertEquals(1L, owner.queryForObject("SELECT count(*) FROM nw_platform_models WHERE id=910000001", Long.class));
        assertTrue(owner.queryForObject("SELECT count(*) FROM nw_platform_model_versions WHERE model_id=910000001", Long.class) >= 1L);
    }

    private static Monthly bill(long tenant, String month, int page, int size) {
        TenantContext.setTenantId(Long.toString(tenant));
        return reader.execute(status -> service.monthly(tenant, month, page, size));
    }

    private static void seed() {
        event(1001,1,"llm.chat","measured","chat-paid","priced","1.25","CNY","1","PROVIDER_REPORTED",null,3L,5L,null,null,1,OCCURRED,null);
        event(1002,1,"media.video","measured","video-paid","priced","2","USD","7.25","PROVIDER_REPORTED",null,null,null,null,"2.125",1,OCCURRED,null);
        event(1003,1,"llm.embedding","measured","known-zero","priced","0","CNY","1","PROVIDER_REPORTED",null,0L,null,null,null,1,OCCURRED,null);
        event(1004,1,"media.speech","measured","unknown-speech","unpriced",null,"CNY",null,"UNKNOWN",null,null,null,null,null,1,OCCURRED,null);
        event(1005,1,"media.image","measured","missing-fx","unresolved","3","USD",null,"PROVIDER_REPORTED",null,null,null,2L,null,1,OCCURRED,null);
        event(1006,1,"llm.chat","usage_report","pending-chat","unresolved",null,"CNY",null,"UNKNOWN",0L,null,null,null,null,1,OCCURRED,null);
        event(1007,1,"llm.chat","usage_report","pending-chat","unresolved",null,"CNY",null,"PROVIDER_REPORTED",1L,9L,null,null,null,1,OCCURRED.plusSeconds(1),null);
        event(1008,1,"llm.chat","usage_report","pending-chat","unresolved",null,"CNY",null,"PROVIDER_REPORTED",2L,null,4L,null,null,1,OCCURRED.plusSeconds(2),null);
        event(1009,1,"media.image","usage_report","pending-image","unresolved",null,"CNY",null,"UNKNOWN",0L,null,null,null,null,1,OCCURRED,null);
        event(1010,1,"media.image","usage_report","pending-image","unresolved",null,"CNY",null,"PROVIDER_REPORTED",1L,null,null,2L,null,1,OCCURRED,null);
        event(1011,1,"llm.chat","usage_report","chat-paid","unresolved",null,"CNY",null,"PROVIDER_REPORTED",9L,999L,999L,null,null,1,OCCURRED,null);
        event(1012,1,"llm.chat","reconciliation","chat-paid","priced","2.5","CNY","1","PROVIDER_REPORTED",null,null,null,null,null,1,OCCURRED.plusSeconds(3),1001L);
        event(1013,1,"llm.chat","adjustment","chat-paid","priced","0.5","CNY","1","PROVIDER_REPORTED",null,null,null,null,null,1,OCCURRED.plusSeconds(4),1001L);
        event(1014,1,"llm.rerank","measured","rerank-paid","priced","0.25","CNY","1","PROVIDER_REPORTED",null,79L,null,null,null,3,OCCURRED,null);
        event(1015,1,"media.transcription","measured","asr-paid","priced","0.125","CNY","1","PROVIDER_REPORTED",null,55L,13L,null,"2.08",1,OCCURRED,null);
        event(1030,1,"media.video","usage_report","not-submitted","unresolved",null,"CNY",null,"UNKNOWN",0L,null,null,null,null,1,OCCURRED,null);
        event(1031,1,"media.video","measured","not-submitted","not_applicable",null,"CNY",null,"UNKNOWN",null,null,null,null,null,0,OCCURRED,null);
        event(1040,1,"request","measured","request-only","not_applicable",null,"CNY",null,"UNKNOWN",null,null,null,null,null,1,OCCURRED,null);
        event(1041,1,"tool.call","measured","tool-paid","priced","999","CNY","1","UNKNOWN",null,null,null,null,null,1,OCCURRED,null);
        event(1042,1,"knowledge.source","measured","knowledge-paid","priced","999","CNY","1","UNKNOWN",null,null,null,null,null,1,OCCURRED,null);
        event(1043,1,"workflow.external","measured","workflow-paid","priced","999","CNY","1","UNKNOWN",null,null,null,null,null,1,OCCURRED,null);
        event(1044,1,"tool.call","usage_report","unknown-tool","unresolved",null,"CNY",null,"UNKNOWN",0L,null,null,null,null,1,OCCURRED,null);
        event(1101,1,"llm.embedding","measured","before-month","priced","1","CNY","1","PROVIDER_REPORTED",null,1L,null,null,null,1,FROM.minusNanos(1000),null);
        event(1102,1,"llm.embedding","measured","next-month","priced","1","CNY","1","PROVIDER_REPORTED",null,1L,null,null,null,1,TO,null);
        event(2001,2,"llm.chat","measured","chat-paid","priced","400","CNY","1","PROVIDER_REPORTED",null,1L,1L,null,null,1,OCCURRED,null);
    }

    private static void event(long id,long tenant,String type,String kind,String attempt,String status,String amount,String currency,
            String rate,String source,Long revision,Long input,Long output,Long images,String seconds,int quantity,Instant occurred,Long original) {
        boolean media = type.startsWith("media.");
        owner.update("""
                INSERT INTO nw_usage_events(id,tenant_id,event_id,event_type,usage_event_kind,attempt_id,invocation_id,
                  amount_status,total_amount,currency,exchange_rate_to_cny,report_revision,prompt_tokens,completion_tokens,
                  image_count,duration_seconds,quantity,occurred_at,ingested_at,adjustment_of_event_id,usage_source,
                  pricing_snapshot_version,pricing_model_id,pricing_model_revision,origin_kind,actor_ref,responsibility_ref)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'frozen-source-1',?,?,'system','system:model-bill-fixture',?)
                """, id,tenant,"fixture:"+id,type,kind,attempt,"invocation:"+attempt,status,amount==null?null:new BigDecimal(amount),
                currency,rate==null?null:new BigDecimal(rate),revision,input,output,images,seconds==null?null:new BigDecimal(seconds),
                quantity,Timestamp.from(occurred),Timestamp.from(occurred),original,source,media?910000001L:null,media?1L:null,"tenant:"+tenant);
    }

    private static DataSource source(String user,String password) {
        var source = new PGSimpleDataSource(); source.setURL(DATABASE.getJdbcUrl()); source.setUser(user); source.setPassword(password); return source;
    }
}
