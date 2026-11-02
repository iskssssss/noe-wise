package com.skillforge.core.model.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.export.PlatformUsageMapper;
import com.skillforge.core.billing.export.PlatformUsageQuery;
import com.skillforge.core.billing.export.PlatformUsageRecorder;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.MediaUsage;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.billing.persistence.ModelBillingMapper;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.persistence.ExternalInvocationMapper;
import com.skillforge.core.billing.pricing.ExchangeRateProvider;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.billing.pricing.MediaCostPrice;
import com.skillforge.core.billing.pricing.MediaPricingContext;
import com.skillforge.core.billing.pricing.PriceCalculator;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 真实Flyway、受限PostgreSQL与生产Mapper验证媒体账本组合；不启动Redis或Runtime Worker。
 * Provider、模型目录/价格读取和归因解析为受控替代；账本、事务、可靠发布与RLS是真实实现。
 * 实际Agent发布/来源授权的验收由独立HTTP场景承担，不以本测试spy的归因替代。
 */
@Testcontainers(disabledWithoutDocker = false)
class ModelMediaMeteringDatabaseTest {
    private static final String ROLE = "media_meter_writer";
    private static final String PASSWORD = "synthetic-media-meter-writer";
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync");
    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static DataSourceTransactionManager manager;
    private static DataSourceTransactionManager platformManager;
    private static TransactionTemplate transactions;
    private static ModelMediaJobMapper jobsMapper;
    private static ModelMediaJobMapper platformJobsMapper;
    private static CostRecorder costs;
    private static ExternalInvocationLedger ledger;
    private static PlatformUsageMapper publications;
    private static ModelBillingMapper billing;
    private static ModelPriceSource prices;
    private static ExchangeRateProvider exchangeRates;
    private static final AtomicBoolean FAIL_ENQUEUE = new AtomicBoolean();

    @BeforeAll static void database() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        owner = new JdbcTemplate(admin);
        try (var connection = admin.getConnection()) {
            CurrentHostFixtures.tenant(connection, 1L); CurrentHostFixtures.tenant(connection, 2L);
            CurrentHostFixtures.agent(connection, 1L, 8L); CurrentHostFixtures.endUser(connection, 1L, 7L);
        }
        owner.update("INSERT INTO nw_platform_models(id,current_revision,draft_revision,draft,draft_changed) VALUES(9001,NULL,1,'{}'::jsonb,true)");
        owner.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        owner.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + ROLE);
        // 与CurrentRuntimeDatabase.grantSessionLogReads一致：原事件KEY SHARE仅需一列UPDATE权，正式不可变触发器仍生效。
        owner.execute("GRANT UPDATE(event_id) ON public.agent_runtime_session_events TO " + ROLE);
        owner.execute("GRANT INSERT ON nw_usage_events,nw_usage_publication_pending TO " + ROLE);
        owner.execute("GRANT INSERT,UPDATE ON nw_external_invocations,nw_model_media_jobs,nw_model_media_assets TO " + ROLE);
        owner.execute("GRANT INSERT ON nw_model_media_input_sources,nw_model_media_input_channels,nw_model_media_input_languages TO " + ROLE);
        owner.execute("GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO " + ROLE);
        // 平台探测使用拥有该域权限的连接；机构路径始终走下面的受限角色与RLS。
        platformManager = new DataSourceTransactionManager(admin);
        var platformConfiguration = new MybatisConfiguration(); platformConfiguration.setMapUnderscoreToCamelCase(true);
        platformConfiguration.setEnvironment(new Environment("platform-media-probe", new SpringManagedTransactionFactory(), admin));
        String jobResource = "mapper/models/ModelMediaJobMapper.xml";
        try (var input = new ClassPathResource(jobResource).getInputStream()) {
            new XMLMapperBuilder(input, platformConfiguration, jobResource, platformConfiguration.getSqlFragments()).parse();
        }
        platformJobsMapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(platformConfiguration)).getMapper(ModelMediaJobMapper.class);
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), ROLE, PASSWORD);
        restricted = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.setEnvironment(new Environment("media-metering", new SpringManagedTransactionFactory(), source));
        for (String resource : List.of("mapper/models/ModelMediaJobMapper.xml", "mapper/CostEventMapper.xml",
                "mapper/ExternalInvocationMapper.xml", "mapper/PlatformUsageMapper.xml", "mapper/ModelBillingMapper.xml")) {
            try (var input = new ClassPathResource(resource).getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        jobsMapper = sessions.getMapper(ModelMediaJobMapper.class);
        publications = sessions.getMapper(PlatformUsageMapper.class);
        billing = sessions.getMapper(ModelBillingMapper.class);
        var publisher = spy(new PlatformUsageRecorder(publications, mock(IntegrationProvenanceService.class)));
        // 只替代本测试无关的Run授权来源解析；源事务、真实enqueue和后继inheritGrantChain不替代。
        doAnswer(call -> {
            CostEventDO event = call.getArgument(0);
            event.setOriginKind("native"); event.setActorRef("member:media-database-fixture");
            event.setResponsibilityRef("tenant:" + event.getTenantId());
            event.setSourceOwnerKind("tenant"); event.setSourceOwnerTenantId(event.getTenantId());
            return null;
        }).when(publisher).attribute(any(CostEventDO.class), anyString());
        doAnswer(call -> {
            call.callRealMethod();
            if (FAIL_ENQUEUE.get()) throw new IllegalStateException("controlled_pending_failure");
            return null;
        }).when(publisher).enqueue(any(CostEventDO.class));
        prices = mock(ModelPriceSource.class); exchangeRates = mock(ExchangeRateProvider.class);
        var beans = new StaticListableBeanFactory(Map.of("usagePublisher", publisher, "rates", exchangeRates));
        costs = new CostRecorder(sessions.getMapper(CostEventMapper.class), new PriceCalculator(prices),
                beans.getBeanProvider(ExchangeRateProvider.class), beans.getBeanProvider(PlatformUsageRecorder.class));
        ledger = new ExternalInvocationLedger(sessions.getMapper(ExternalInvocationMapper.class), costs);
    }

    @BeforeEach void faults() {
        FAIL_ENQUEUE.set(false); reset(prices, exchangeRates);
        when(prices.snapshot(new ModelPriceSource.ModelRevisionRef("9001", 3))).thenReturn(snapshot(null, "CNY"));
        when(exchangeRates.toCny(anyString(), any())).thenReturn(java.util.Optional.empty());
    }
    @AfterEach void clear() { TenantContext.clear(); FAIL_ENQUEUE.set(false); }

    @Test void completedOriginalJobMergesReportsAndPublishesOneMeasuredFactWithOriginalAttribution() {
        assertTrue(owner.queryForObject("SELECT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='338' AND success)", Boolean.class));
        var sends = new AtomicInteger();
        var fixture = service((connection, request) -> {
            connection.beforeDispatch().beforeDispatch(); sends.incrementAndGet();
            return request instanceof DomesticMediaProvider.TaskQuery
                    ? result(DomesticMediaProvider.Status.SUCCEEDED, new DomesticMediaProvider.Usage(55L, null, null, null, null, null))
                    : result(DomesticMediaProvider.Status.PENDING, new DomesticMediaProvider.Usage(null, null, null, 1L, null, new BigDecimal("2.08000000000000001")));
        });
        TenantContext.setTenantId("1");
        var holder = new ModelMediaJob.Owner("1", "7", "completed-session");
        var operation = UUID.randomUUID().toString();
        var source = source("completed-session", "original-create-run");
        var first = fixture.createPublished(holder, operation, "9001", 3, video(), Instant.now().plusSeconds(30), () -> false, source);
        assertEquals(first.id(), fixture.createPublished(holder, operation, "9001", 3, video(), Instant.now().plusSeconds(30), () -> false, source).id());
        try (var ignored = MeterScope.open(CostContext.builder().runId("different-query-run").sessionId(holder.sessionScope()).build())) {
            fixture.query(holder, first.id()); fixture.query(holder, first.id());
        }
        assertEquals(2, sends.get(), "one create and one original query; readbacks do not dispatch");
        var invocation = "media-job:" + first.id();
        var measured = owner.queryForMap("SELECT * FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='measured'", invocation);
        assertEquals("original-create-run", measured.get("run_id"));
        assertEquals("root:original-create-run", measured.get("root_input_id"));
        assertEquals(9001L, measured.get("pricing_model_id")); assertEquals(3L, measured.get("pricing_model_revision"));
        assertEquals(1, measured.get("quantity")); assertEquals(1L, measured.get("video_count"));
        assertEquals(new BigDecimal("2.08000000000000001"), measured.get("duration_seconds"));
        assertEquals(55L, measured.get("prompt_tokens")); assertNull(measured.get("completion_tokens"));
        assertEquals("unpriced", measured.get("amount_status")); assertNull(measured.get("total_amount"));
        long count = eventCount(invocation);
        inTenant("1", () -> { costs.recordMediaObservation("1", invocation, 2, new MediaUsage(55L, null, null, null, null, null), true, new MediaPricingContext("720P")); return null; });
        assertEquals(count, eventCount(invocation), "same report and terminal readback do not double count");
        assertEquals(count, pendingCount(invocation));
        owner.queryForObject("SELECT publish_committed_usage(1000)", Long.class);
        var exported = inTenant("1", () -> publications.list(Set.of(1L), null, PlatformUsageQuery.empty(), 0, publications.streamTail(), 100));
        var actual = exported.stream().filter(row -> invocation.equals(row.getInvocationId()) && "measured".equals(row.getUsageEventKind())).toList();
        assertEquals(1, actual.size()); assertEquals(measured.get("duration_seconds"), actual.getFirst().getDurationSeconds());
        assertEquals("original-create-run", actual.getFirst().getRunId());
        var monthlyRows = inTenant("1", () -> billing.list(1L, Instant.EPOCH, Instant.now().plusSeconds(60), 0, 200));
        var line = monthlyRows.stream().filter(row -> invocation.equals(row.getInvocationId())).findFirst().orElseThrow();
        assertNull(line.getAmountCents(), "missing model cost remains unknown in the readonly monthly projection");
        assertNull(inTenant("2", () -> jobsMapper.find(holder.key(), first.id())));
        assertEquals(0L, inTenant("2", () -> restricted.queryForObject("SELECT count(*) FROM nw_usage_events WHERE invocation_id=?", Long.class, invocation)));
        assertThrows(RuntimeException.class, () -> inTenant("2", () -> { jobsMapper.insert(unauthorizedRow()); return null; }));
        assertFalse(restricted.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
    }

    @Test void beginFactAndReliablePendingRollbackTogetherBeforeAnySupplierSend() {
        var sends = new AtomicInteger();
        var fixture = service((connection, request) -> { connection.beforeDispatch().beforeDispatch(); sends.incrementAndGet(); return result(DomesticMediaProvider.Status.SUCCEEDED, null); });
        TenantContext.setTenantId("1");
        FAIL_ENQUEUE.set(true);
        String operation = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> fixture.createPublished(new ModelMediaJob.Owner("1", "7", "rollback-session"),
                operation, "9001", 3, video(), Instant.now().plusSeconds(30), () -> false, source("rollback-session", "rollback-run")));
        assertEquals(0, sends.get());
        var job = owner.queryForMap("SELECT id,status,reason_code FROM nw_model_media_jobs WHERE operation_id=?", operation);
        String invocation = "media-job:" + job.get("id");
        assertEquals("NOT_SUBMITTED", job.get("status")); assertEquals("model_media_metering_not_submitted", job.get("reason_code"));
        assertEquals(0, eventCount(invocation)); assertEquals(0, pendingCount(invocation));
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_external_invocations WHERE invocation_id=?", Long.class, invocation));
    }

    @Test void observationAndMeasuredPendingRollbackWhileOriginalUnknownStaysARealBillingGap() {
        var fixture = service((connection, request) -> { connection.beforeDispatch().beforeDispatch(); throw new IllegalStateException("controlled_transport_unknown"); });
        TenantContext.setTenantId("1");
        var created = fixture.createPublished(new ModelMediaJob.Owner("1", "7", "unknown-session"), UUID.randomUUID().toString(),
                "9001", 3, video(), Instant.now().plusSeconds(30), () -> false, source("unknown-session", "unknown-run"));
        String invocation = "media-job:" + created.id();
        assertEquals(DomesticMediaProvider.Status.UNKNOWN, created.status());
        assertEquals(1, eventCount(invocation)); assertEquals(1, pendingCount(invocation));
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='measured'", Long.class, invocation));
        assertTrue(inTenant("1", () -> billing.summarize(1L, Instant.EPOCH, Instant.now().plusSeconds(60)).getMissingCostCount()) > 0);
        FAIL_ENQUEUE.set(true);
        assertThrows(IllegalStateException.class, () -> inTenant("1", () -> {
            costs.recordMediaObservation("1", invocation, 2, new MediaUsage(55L, null, null, null, null, new BigDecimal("2.08")), true, new MediaPricingContext("720P"));
            return null;
        }));
        assertEquals(1, eventCount(invocation)); assertEquals(1, pendingCount(invocation));
        assertEquals("UNKNOWN", owner.queryForObject("SELECT status FROM nw_model_media_jobs WHERE id=?", String.class, Long.valueOf(created.id())));
        assertEquals("UNRESOLVED", owner.queryForObject("SELECT status FROM nw_external_invocations WHERE invocation_id=?", String.class, invocation));
    }

    @Test void cancellationBeforeSupplierSendClosesStartedFactWithoutInventingZeroMediaUsage() {
        TenantContext.setTenantId("1");
        var cancelled = new AtomicBoolean(); var sends = new AtomicInteger();
        var fixture = service((connection, request) -> {
            connection.beforeDispatch().beforeDispatch();
            cancelled.set(true);
            if (connection.cancelled().getAsBoolean())
                return new DomesticMediaProvider.Result(DomesticMediaProvider.Status.NOT_SUBMITTED,
                        null, null, List.of(), null, "media_cancelled_before_send", Instant.now());
            sends.incrementAndGet(); return result(DomesticMediaProvider.Status.SUCCEEDED, null);
        });
        var created = fixture.createPublished(new ModelMediaJob.Owner("1", "7", "cancel-session"), UUID.randomUUID().toString(),
                "9001", 3, video(), Instant.now().plusSeconds(30), cancelled::get, source("cancel-session", "cancel-run"));
        String invocation = "media-job:" + created.id();
        assertEquals(0, sends.get()); assertEquals(DomesticMediaProvider.Status.NOT_SUBMITTED, created.status());
        assertEquals(2, eventCount(invocation)); assertEquals(2, pendingCount(invocation));
        var measured = owner.queryForMap("SELECT * FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='measured'", invocation);
        assertEquals(0, measured.get("quantity")); assertEquals("not_applicable", measured.get("amount_status"));
        assertNull(measured.get("image_count")); assertNull(measured.get("video_count")); assertNull(measured.get("duration_seconds"));
        assertNull(measured.get("total_amount"));
        assertEquals("FAILED", owner.queryForObject("SELECT status FROM nw_external_invocations WHERE invocation_id=?", String.class, invocation));
        assertTrue(inTenant("1", () -> billing.list(1L, Instant.EPOCH, Instant.now().plusSeconds(60), 0, 200)).stream()
                .noneMatch(row -> invocation.equals(row.getInvocationId())));

        var noCallback = service((connection, request) -> new DomesticMediaProvider.Result(DomesticMediaProvider.Status.NOT_SUBMITTED,
                null, null, List.of(), null, "media_cancelled_before_send", Instant.now()));
        var unsent = noCallback.createPublished(new ModelMediaJob.Owner("1", "7", "cancel-before-callback-session"), UUID.randomUUID().toString(),
                "9001", 3, video(), Instant.now().plusSeconds(30), () -> false, source("cancel-before-callback-session", "before-callback-run"));
        assertEquals(DomesticMediaProvider.Status.NOT_SUBMITTED, unsent.status());
        assertEquals(0, eventCount("media-job:" + unsent.id()), "no observer callback means no started or measured supplier fact");
    }

    @Test void unsentQueryCannotEraseOriginalSupplierUsageOrSettleTheGeneration() {
        TenantContext.setTenantId("1");
        var fixture = service((connection, request) -> {
            if (request instanceof DomesticMediaProvider.TaskQuery)
                return new DomesticMediaProvider.Result(DomesticMediaProvider.Status.NOT_SUBMITTED,
                        null, null, List.of(), null, "media_cancelled_before_send", Instant.now());
            connection.beforeDispatch().beforeDispatch();
            return result(DomesticMediaProvider.Status.PENDING, new DomesticMediaProvider.Usage(null, null, null, 1L, null, null));
        });
        var holder = new ModelMediaJob.Owner("1", "7", "query-cancel-session");
        var created = fixture.createPublished(holder, UUID.randomUUID().toString(), "9001", 3, video(),
                Instant.now().plusSeconds(30), () -> false, source(holder.sessionScope(), "query-cancel-create-run"));
        String invocation = "media-job:" + created.id(); long before = eventCount(invocation);
        var queried = fixture.query(holder, created.id());
        assertEquals(DomesticMediaProvider.Status.PENDING, queried.status()); assertEquals(1L, queried.usage().videoCount());
        assertEquals(before, eventCount(invocation)); assertEquals(before, pendingCount(invocation));
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='measured'", Long.class, invocation));
        assertEquals("SUCCEEDED", owner.queryForObject("SELECT status FROM nw_external_invocations WHERE invocation_id=?", String.class, invocation));
    }

    @Test void platformProbeAndHistoricalObservationNeverAcquireAnInstitutionEconomicIdentity() {
        long before = owner.queryForObject("SELECT count(*) FROM nw_usage_events", Long.class);
        var fixture = service((connection, request) -> { connection.beforeDispatch().beforeDispatch(); return result(DomesticMediaProvider.Status.SUCCEEDED, null); },
                platformJobsMapper, platformManager);
        fixture.create(new ModelMediaJob.Owner(null, "9"), new ModelMediaJob.Create(UUID.randomUUID().toString(), "9001", null, video()));
        inTenant("1", () -> { costs.recordMediaObservation("1", "media-job:historical-without-start", 1, MediaUsage.empty(), true, new MediaPricingContext(null)); return null; });
        assertEquals(before, owner.queryForObject("SELECT count(*) FROM nw_usage_events", Long.class));
    }

    @Test void configuredVideoPriceUsesOriginalResolutionAndRevisionAndTerminalReadbackNeverRecalculates() {
        var price = new MediaCostPrice(MediaCostPrice.Unit.VIDEO_SECOND, new BigDecimal("5.123456"), null, null, "720P");
        when(prices.snapshot(new ModelPriceSource.ModelRevisionRef("9001", 3))).thenReturn(snapshot(price, "CNY"));
        var sends = new AtomicInteger(); var duration = new BigDecimal("2.08000000000000001");
        var fixture = service((connection, request) -> {
            connection.beforeDispatch().beforeDispatch(); sends.incrementAndGet();
            return request instanceof DomesticMediaProvider.TaskQuery
                    ? result(DomesticMediaProvider.Status.SUCCEEDED, new DomesticMediaProvider.Usage(55L, null, null, null, null, null))
                    : result(DomesticMediaProvider.Status.PENDING, new DomesticMediaProvider.Usage(null, null, null, 1L, null, duration));
        });
        TenantContext.setTenantId("1"); var holder = new ModelMediaJob.Owner("1", "7", "priced-video-session");
        var created = fixture.createPublished(holder, UUID.randomUUID().toString(), "9001", 3, video(),
                Instant.now().plusSeconds(30), () -> false, source(holder.sessionScope(), "priced-video-run"));
        // 当前目录出现另一个版本不能改变原Job的媒体价；本测试仅允许精确原revision读取。
        when(prices.snapshot()).thenReturn(snapshot(new MediaCostPrice(MediaCostPrice.Unit.OUTPUT_VIDEO, new BigDecimal("999"), null, null, null), "CNY"));
        fixture.query(holder, created.id()); String invocation = "media-job:" + created.id();
        var measured = owner.queryForMap("SELECT * FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='measured'", invocation);
        var expected = duration.multiply(price.pricePerUnitCents()).setScale(12, java.math.RoundingMode.HALF_UP);
        assertEquals(expected, measured.get("total_amount")); assertEquals("priced", measured.get("amount_status"));
        assertEquals(duration, measured.get("duration_seconds")); assertEquals(55L, measured.get("prompt_tokens")); assertNull(measured.get("completion_tokens"));
        assertEquals(3L, measured.get("pricing_model_revision")); assertEquals("priced-video-run", measured.get("run_id"));
        verify(prices, atLeastOnce()).snapshot(new ModelPriceSource.ModelRevisionRef("9001", 3)); verify(prices, never()).snapshot();
        owner.queryForObject("SELECT publish_committed_usage(1000)", Long.class);
        var actual = inTenant("1", () -> publications.list(Set.of(1L), null, PlatformUsageQuery.empty(), 0, publications.streamTail(), 100)).stream()
                .filter(row -> invocation.equals(row.getInvocationId()) && "measured".equals(row.getUsageEventKind())).findFirst().orElseThrow();
        assertEquals(expected, actual.getAmountCents()); assertEquals(duration, actual.getDurationSeconds());
        long before = eventCount(invocation); clearInvocations(prices, exchangeRates);
        fixture.detail(holder, created.id()); fixture.query(holder, created.id());
        inTenant("1", () -> { costs.recordMediaObservation("1", invocation, 2, new MediaUsage(55L, null, null, null, null, null), true, new MediaPricingContext("720P")); return null; });
        assertEquals(before, eventCount(invocation)); assertEquals(2, sends.get()); verifyNoInteractions(prices, exchangeRates);
        assertEquals(expected, owner.queryForObject("SELECT total_amount FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='measured'", BigDecimal.class, invocation));
    }

    @Test void configuredPriceWithoutItsOriginalResolutionOrReportedDurationStaysUnpricedWithoutErasingUsage() {
        TenantContext.setTenantId("1");
        for (boolean wrongResolution : List.of(true, false)) {
            var pricing = new MediaCostPrice(MediaCostPrice.Unit.VIDEO_SECOND, BigDecimal.ONE, null, null, wrongResolution ? "1080P" : "720P");
            when(prices.snapshot(new ModelPriceSource.ModelRevisionRef("9001", 3))).thenReturn(snapshot(pricing, "CNY"));
            var fixture = service((connection, request) -> {
                connection.beforeDispatch().beforeDispatch();
                return result(DomesticMediaProvider.Status.SUCCEEDED, new DomesticMediaProvider.Usage(null, null, null, 1L, null,
                        wrongResolution ? new BigDecimal("2.08") : null));
            });
            var session = "missing-video-price-" + wrongResolution;
            var created = fixture.createPublished(new ModelMediaJob.Owner("1", "7", session), UUID.randomUUID().toString(), "9001", 3, video(),
                    Instant.now().plusSeconds(30), () -> false, source(session, "missing-video-run-" + wrongResolution));
            var fact = owner.queryForMap("SELECT amount_status,total_amount,video_count,duration_seconds FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='measured'", "media-job:" + created.id());
            assertEquals("unpriced", fact.get("amount_status")); assertNull(fact.get("total_amount")); assertEquals(1L, fact.get("video_count"));
            assertEquals(wrongResolution ? new BigDecimal("2.08") : null, fact.get("duration_seconds"));
        }
    }

    @Test void foreignCostUsesOriginalStartedTimeAndFreezesRateWhileMissingRateStaysUnresolved() {
        TenantContext.setTenantId("1");
        var pricing = new MediaCostPrice(MediaCostPrice.Unit.OUTPUT_VIDEO, new BigDecimal("7.125"), null, null, null);
        when(prices.snapshot(new ModelPriceSource.ModelRevisionRef("9001", 3))).thenReturn(snapshot(pricing, "USD"));
        for (boolean knownRate : List.of(true, false)) {
            reset(exchangeRates);
            when(exchangeRates.toCny(eq("USD"), any())).thenAnswer(call -> knownRate
                    ? java.util.Optional.of(new ExchangeRateProvider.Rate(new BigDecimal("7.25"), call.getArgument(1), "original-media-fx"))
                    : java.util.Optional.empty());
            var fixture = service((connection, request) -> { connection.beforeDispatch().beforeDispatch();
                return result(DomesticMediaProvider.Status.SUCCEEDED, new DomesticMediaProvider.Usage(null, null, null, 1L, null, null)); });
            var session = "foreign-video-" + knownRate;
            var created = fixture.createPublished(new ModelMediaJob.Owner("1", "7", session), UUID.randomUUID().toString(), "9001", 3, video(),
                    Instant.now().plusSeconds(30), () -> false, source(session, "foreign-video-run-" + knownRate));
            var invocation = "media-job:" + created.id();
            var fact = owner.queryForMap("SELECT amount_status,total_amount,currency,exchange_rate_to_cny,pricing_snapshot_version FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='measured'", invocation);
            assertEquals(new BigDecimal("7.125000000000"), fact.get("total_amount")); assertEquals("USD", fact.get("currency"));
            assertEquals(knownRate ? "priced" : "unresolved", fact.get("amount_status"));
            if (knownRate) assertEquals(0, new BigDecimal("7.25").compareTo((BigDecimal) fact.get("exchange_rate_to_cny")));
            else assertNull(fact.get("exchange_rate_to_cny"));
            var time = org.mockito.ArgumentCaptor.forClass(Instant.class);
            verify(exchangeRates).toCny(eq("USD"), time.capture());
            Instant started = owner.queryForObject("SELECT occurred_at FROM nw_usage_events WHERE invocation_id=? AND usage_event_kind='usage_report' AND report_revision=0",
                    (rs, rowNumber) -> rs.getTimestamp(1).toInstant(), invocation);
            assertEquals(started, time.getValue());
            clearInvocations(exchangeRates); fixture.query(new ModelMediaJob.Owner("1", "7", session), created.id()); verifyNoInteractions(exchangeRates);
        }
    }

    private static ModelPriceSource.Snapshot snapshot(MediaCostPrice pricing, String currency) {
        return new ModelPriceSource.Snapshot("frozen-media-price-fixture", Map.of(new ModelPriceSource.Key("aliyun-bailian", "wan-fixture"),
                new ModelPriceSource.Price(null, null, null, currency, pricing, null, null)));
    }

    private static ModelMediaJobService service(Invocation provider) {
        return service(provider, jobsMapper, manager);
    }
    private static ModelMediaJobService service(Invocation provider, ModelMediaJobMapper jobMapper, DataSourceTransactionManager transactionManager) {
        var models = mock(PlatformModelCatalogService.class); var config = mock(PlatformModelConfig.class);
        when(config.enabled()).thenReturn(true); when(config.kind()).thenReturn("VIDEO_GENERATION");
        when(config.providerId()).thenReturn("aliyun-bailian"); when(config.serviceId()).thenReturn("bailian-cn-beijing-video");
        when(config.protocol()).thenReturn("bailian-native"); when(config.modelId()).thenReturn("wan-fixture");
        when(config.profileVersion()).thenReturn("fixture"); when(config.endpoint()).thenReturn("https://dashscope.aliyuncs.com");
        when(config.apiKeyEncrypted()).thenReturn("synthetic-encrypted"); when(config.timeoutSeconds()).thenReturn(30); when(config.revision()).thenReturn(3L);
        when(models.readRevision("9001", 3)).thenReturn(config); when(models.draftForProbe("9001")).thenReturn(config);
        var crypto = mock(CryptoService.class); when(crypto.decrypt("synthetic-encrypted")).thenReturn("synthetic-key");
        var sdk = mock(DomesticMediaProvider.class); when(sdk.execute(any(), any())).thenAnswer(call -> provider.execute(call.getArgument(0), call.getArgument(1)));
        var providers = mock(DomesticMediaProviders.class); when(providers.require("aliyun-bailian")).thenReturn(sdk);
        return new ModelMediaJobService(models, crypto, jobMapper, mock(ModelMediaAssetStore.class), providers, transactionManager,
                new ObjectMapper().findAndRegisterModules(), ledger, costs);
    }
    private static ModelMediaInput.Video video() { return new ModelMediaInput.Video("fixture", List.of(), "720P", "16:9", 5, false, null, false, false); }
    private static DomesticMediaProvider.Result result(DomesticMediaProvider.Status status, DomesticMediaProvider.Usage usage) {
        return new DomesticMediaProvider.Result(status, "synthetic-receipt", "synthetic-task", List.of(), usage, null, Instant.now());
    }
    private static ModelMediaMeteringSource source(String session, String run) {
        return new ModelMediaMeteringSource(CostContext.builder().requestId("input:" + run).rootInputId("root:" + run)
                .runId(run).attributionRunId(run).sessionId(session).endUserId(7L).agentId(8L).callPoint("media.database-fixture")
                .traceContext(new TraceContext("input:" + run, "root:" + run, "trace", "span", "tool-event", null)).build(), "tool:" + run, "tool-attempt:" + run);
    }
    private static long eventCount(String invocation) { return owner.queryForObject("SELECT count(*) FROM nw_usage_events WHERE invocation_id=?", Long.class, invocation); }
    private static long pendingCount(String invocation) {
        return owner.queryForObject("SELECT count(*) FROM nw_usage_publication_pending p JOIN nw_usage_events e ON e.tenant_id=p.tenant_id AND e.id=p.usage_id WHERE e.invocation_id=?", Long.class, invocation);
    }
    private static <T> T inTenant(String tenant, Supplier<T> work) {
        String previous = TenantContext.getTenantId(); TenantContext.setTenantId(tenant);
        try { return transactions.execute(status -> { restricted.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, tenant); return work.get(); }); }
        finally { if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
    }
    private static ModelMediaJobMapper.Row unauthorizedRow() {
        var row = new ModelMediaJobMapper.Row(); row.id = "89000001"; row.tenantId = "1";
        row.ownerId = new ModelMediaJob.Owner("1", "7", "wrong-tenant").key(); row.operationId = UUID.randomUUID().toString();
        row.commandSha256 = "a".repeat(64); row.modelRevision = 0; row.providerId = "aliyun-bailian";
        row.modelId = "wan-fixture"; row.kind = "VIDEO_GENERATION"; row.endpoint = "https://dashscope.aliyuncs.com";
        row.credentialEncrypted = "synthetic"; row.timeoutSeconds = 30; row.status = "UNKNOWN"; row.createdAt = Instant.now(); row.updatedAt = row.createdAt;
        return row;
    }
    @FunctionalInterface private interface Invocation {
        DomesticMediaProvider.Result execute(DomesticMediaProvider.Connection connection, DomesticMediaProvider.Request request);
    }
}
