package com.skillforge.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.utility.DockerImageName;

/** 正式全链迁移、受限角色和生产XML Mapper验证配置发布与不可变引用。 */
@Testcontainers(disabledWithoutDocker = false)
class TimePricingPresetPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync");
    private static JdbcTemplate owner;
    private static JdbcTemplate platform;
    private static JdbcTemplate institution;
    private static TransactionTemplate transactions;
    private static TimePricingRules rules;
    private static TimePricingPresetService presets;
    private static PlatformModelCatalogService models;
    private static PlatformModelMapper modelMapper;
    private static org.apache.ibatis.session.Configuration modelConfiguration;

    @BeforeAll static void database() throws Exception {
        var admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        owner = new JdbcTemplate(admin);
        owner.execute("CREATE ROLE skillforge_platform LOGIN PASSWORD 'synthetic-time-platform' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE ROLE skillforge_institution LOGIN PASSWORD 'synthetic-time-institution' NOSUPERUSER NOBYPASSRLS");
        // 首次数据库准备发生在完整迁移之前，精确例外必须允许目标表尚不存在。
        applyTimePricingReadOnlyGrants();
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        owner.execute("GRANT USAGE ON SCHEMA public TO skillforge_platform,skillforge_institution");
        owner.execute("GRANT SELECT,INSERT,UPDATE ON nw_platform_models TO skillforge_platform");
        owner.execute("GRANT SELECT,INSERT ON nw_platform_model_versions TO skillforge_platform");
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "skillforge_platform", "synthetic-time-platform");
        var connectionProperties = new java.util.Properties();
        connectionProperties.setProperty("options", "-c app.platform_admin=true");
        source.setConnectionProperties(connectionProperties);
        platform = new JdbcTemplate(source);
        institution = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "skillforge_institution", "synthetic-time-institution"));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("time-pricing-configuration", new SpringManagedTransactionFactory(), source));
        for (String resource : List.of("mapper/models/TimePricingMapper.xml", "mapper/models/PlatformModelMapper.xml")) {
            try (var input = new ClassPathResource(resource).getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        modelConfiguration = configuration;
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var mapper = sessions.getMapper(TimePricingMapper.class);
        rules = new TimePricingRules(mapper);
        presets = new TimePricingPresetService(mapper, rules);
        var properties = new SkillForgeProperties();
        properties.setSecretKey("synthetic-time-pricing-database-key-32");
        modelMapper = sessions.getMapper(PlatformModelMapper.class);
        models = new PlatformModelCatalogService(modelMapper, new CryptoService(properties),
                new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
                ModelProviderCatalog.standard(), rules);
    }

    @Test void directorySummaryMatchesFullDraftViewsWithoutPricingReads() {
        var created = transactions.execute(status -> models.create(modelWrite(TimePricingTestPolicies.configured(List.of())), "summary-test"));
        var expected = models.list().stream().map(view -> new PlatformModelDirectorySummary(view.id(), view.name(),
                view.kind(), view.providerId(), view.modelId(), view.enabled(), view.publishedRevision(), view.hasUnpublishedChanges())).toList();
        var noPricing = org.mockito.Mockito.mock(TimePricingRules.class);
        var summaries = new PlatformModelCatalogService(modelMapper, org.mockito.Mockito.mock(CryptoService.class),
                new ObjectMapper(), ModelProviderCatalog.standard(), noPricing);
        assertThat(summaries.summaries()).containsExactlyElementsOf(expected);
        assertThat(summaries.summaries()).filteredOn(row -> row.id().equals(created.id()))
                .singleElement().satisfies(row -> {
                    assertThat(row.publishedRevision()).isNull();
                    assertThat(row.hasUnpublishedChanges()).isTrue();
                });
        transactions.executeWithoutResult(status -> models.publish(created.id(), created.revision(), "summary-test"));
        assertThat(summaries.summaries()).filteredOn(row -> row.id().equals(created.id()))
                .singleElement().satisfies(row -> {
                    assertThat(row.publishedRevision()).isEqualTo(1L);
                    assertThat(row.hasUnpublishedChanges()).isFalse();
                });
        org.mockito.Mockito.verifyNoInteractions(noPricing);
        var sql = modelConfiguration.getMappedStatement(PlatformModelMapper.class.getName() + ".listSummaries")
                .getBoundSql(null).getSql().replaceAll("\\s+", " ").trim();
        assertThat(sql).contains("draft->>'name'", "draft->>'provider_id'", "ORDER BY id")
                .doesNotContain("draft::text", "config", "time_pricing", "jsonb_build_object", "JOIN");
    }

    @Test void relationalRoundTripPreservesArbitraryCalendarNanoPeriodsUnknownAndZeroRates() {
        var policy = futurePolicy();
        long id = transactions.execute(status -> rules.save(policy));
        assertThat(rules.read(id)).isEqualTo(policy);
        assertThat(rules.read(id).peakPeriods().getFirst().start()).isEqualTo(LocalTime.parse("07:01:02.123456789"));
        assertThat(rules.read(id).peakMultiplier()).isNull();
        assertThat(rules.read(id).offPeakMultiplier()).isEqualTo(BigDecimal.ZERO);
        assertThat(rules.readAll(List.of(id, id))).containsOnlyKeys(id);
        assertThatThrownBy(() -> rules.read(Long.MAX_VALUE)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model_time_pricing_rule_missing");
    }

    @Test void presetPublicationAndModelPublicationKeepExactOldContentAfterEdits() {
        var original = TimePricingTestPolicies.configured(List.of());
        var created = transactions.execute(status -> presets.create(new TimePricingPresetService.Write(null, "独立可管理预设", original), "platform-test"));
        assertThat(created.publishedRevision()).isNull();
        assertThat(presets.history(created.id())).isEmpty();
        transactions.executeWithoutResult(status -> presets.publish(created.id(), created.revision(), "platform-test"));
        var published = presets.history(created.id()).getFirst();
        assertThat(published.revision()).isEqualTo(1);
        assertThat(published.policy()).isEqualTo(original);
        var model = transactions.execute(status -> models.create(modelWrite(published.policy()), "platform-test"));
        transactions.executeWithoutResult(status -> models.publish(model.id(), model.revision(), "platform-test"));
        Long frozenRuleId = platform.queryForObject("SELECT time_pricing_rule_id FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=1", Long.class, model.id());
        assertThat(frozenRuleId).isNotNull();
        assertThat(platform.queryForObject("SELECT jsonb_exists(config,'time_pricing') FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=1", Boolean.class, model.id())).isFalse();
        assertThat(platform.queryForObject("SELECT jsonb_exists(draft,'time_pricing') FROM nw_platform_models WHERE id=CAST(? AS bigint)", Boolean.class, model.id())).isFalse();
        var updated = transactions.execute(status -> presets.update(created.id(), new TimePricingPresetService.Write(created.revision(), "下一版规则", futurePolicy()), "platform-test"));
        assertThat(presets.history(created.id()).getFirst().policy()).isEqualTo(original);
        transactions.executeWithoutResult(status -> presets.publish(created.id(), updated.revision(), "platform-test"));
        assertThat(presets.history(created.id())).extracting(TimePricingPresetService.View::revision).containsExactly(2L, 1L);
        assertThat(presets.history(created.id()).getFirst().policy()).isEqualTo(futurePolicy());
        assertThat(models.readRevision(model.id(), 1).timePricing()).isEqualTo(original);
        assertThat(models.current(model.id()).timePricing()).isEqualTo(original);
        assertThat(platform.queryForObject("SELECT time_pricing_rule_id FROM nw_platform_model_versions WHERE model_id=CAST(? AS bigint) AND revision=1", Long.class, model.id())).isEqualTo(frozenRuleId);
        long count = platform.queryForObject("SELECT count(*) FROM nw_model_time_pricing_rules", Long.class);
        assertThatThrownBy(() -> transactions.execute(status -> presets.update(created.id(), new TimePricingPresetService.Write(created.revision(), "过期编辑", original), "platform-test")))
                .hasMessageContaining("time_pricing_preset_revision_conflict");
        assertThat(platform.queryForObject("SELECT count(*) FROM nw_model_time_pricing_rules", Long.class)).isEqualTo(count);
        assertThat(presets.detail(created.id()).policy()).isEqualTo(futurePolicy());
    }

    @Test void migratedDataIsReadableByInstitutionButOnlyPlatformCanWriteAndPublishedRulesCannotMutate() {
        assertThat(owner.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version='340'", Integer.class)).isEqualTo(1);
        assertThat(institution.queryForObject("SELECT count(*) FROM nw_model_time_pricing_rules", Integer.class)).isPositive();
        assertThat(institution.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(platform.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThatThrownBy(() -> institution.update("INSERT INTO nw_model_time_pricing_rules(id,time_zone,valid_from,valid_through) VALUES(81000001,'UTC','2032-01-01','2032-12-31')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        long id = transactions.execute(status -> rules.save(TimePricingTestPolicies.configured(List.of())));
        assertThatThrownBy(() -> owner.update("UPDATE nw_model_time_pricing_rules SET time_zone='UTC' WHERE id=?", id))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> owner.update("INSERT INTO nw_model_time_pricing_weekdays(rule_id,ordinal,weekday) VALUES(?,10,'SUNDAY')", id))
                .hasMessageContaining("immutable");
        assertThat(rules.read(id)).isEqualTo(TimePricingTestPolicies.configured(List.of()));
    }

    @Test void runtimeGrantsReapplicationKeepsAllTimePricingTablesReadOnlyForInstitution() throws Exception {
        // 重现正式准备入口的广泛授权，再执行同一来源的精确例外。
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO skillforge_institution");
        assertThat(owner.queryForObject("SELECT has_table_privilege('skillforge_institution','nw_model_time_pricing_presets','UPDATE')", Boolean.class)).isTrue();
        applyTimePricingReadOnlyGrants();
        for (String table : List.of("nw_model_time_pricing_rules", "nw_model_time_pricing_weekdays", "nw_model_time_pricing_periods",
                "nw_model_time_pricing_dates", "nw_model_time_pricing_scopes", "nw_model_time_pricing_presets", "nw_model_time_pricing_preset_versions")) {
            assertThat(owner.queryForObject("SELECT has_table_privilege('skillforge_institution',?,'SELECT')", Boolean.class, table)).isTrue();
            assertThat(owner.queryForObject("SELECT has_table_privilege('skillforge_institution',?,'INSERT,UPDATE,DELETE')", Boolean.class, table)).isFalse();
        }
        assertThat(institution.queryForObject("SELECT count(*) FROM nw_model_time_pricing_presets", Integer.class)).isPositive();
        assertThatThrownBy(() -> institution.update("UPDATE nw_model_time_pricing_presets SET name='forbidden' WHERE id=863700000000002"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static void applyTimePricingReadOnlyGrants() throws Exception {
        String source = new ClassPathResource("test-deployment/runtime-grants.sql").getContentAsString(StandardCharsets.UTF_8);
        String section = source.substring(source.indexOf("-- BEGIN MODEL TIME PRICING READ ONLY"), source.indexOf("-- END MODEL TIME PRICING READ ONLY"));
        var ownerTransactions = new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
        ownerTransactions.executeWithoutResult(status -> {
            owner.execute("SET LOCAL skillforge.bootstrap.institution_role='skillforge_institution'");
            owner.execute(section);
        });
    }

    private static TimePricingPolicy futurePolicy() {
        return new TimePricingPolicy("Asia/Tokyo", LocalDate.parse("2032-01-01"), LocalDate.parse("2032-12-31"),
                List.of(DayOfWeek.SUNDAY), List.of(new TimePricingPolicy.Period(LocalTime.parse("07:01:02.123456789"), LocalTime.parse("08:15:00.987654321"))),
                List.of(new TimePricingPolicy.DateRange(LocalDate.parse("2032-02-10"), LocalDate.parse("2032-02-12"))),
                List.of(new TimePricingPolicy.DateRange(LocalDate.parse("2032-03-02"), LocalDate.parse("2032-03-04"))),
                null, BigDecimal.ZERO, List.of(new TimePricingPolicy.Scope(null, null, null, "administrator-model-2032")),
                "https://supplier.example.cn/pricing", "2032-administrator-published", "https://calendar.example.cn/2032");
    }

    private static ModelWrite modelWrite(TimePricingPolicy policy) {
        return new ModelWrite(null, "采用已发布预设", "LLM", false, "custom", "custom-openai-compatible", "custom-v1",
                "openai-chat-completions", "bearer-api-key", "http://127.0.0.1:11434/v1", "administrator-model", "synthetic-key",
                4096, 8192, 1024, null, "DISABLED", 30, null, null, new BigDecimal("10"), new BigDecimal("20"), null, "CNY",
                com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(),
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null, null, policy);
    }
}
