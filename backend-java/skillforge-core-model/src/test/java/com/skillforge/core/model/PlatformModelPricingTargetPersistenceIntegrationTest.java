package com.skillforge.core.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.billing.pricing.CacheCostPrice;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 私有PG小DDL与正式XML：比较同输入旧全目录读与目标读，不调用供应商、不复写价格算法。 */
@Testcontainers(disabledWithoutDocker = false)
class PlatformModelPricingTargetPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_INITDB_ARGS", "--no-sync");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final QueryEvidence EVIDENCE = new QueryEvidence();
    private static JdbcTemplate owner;
    private static JdbcTemplate reader;
    private static PlatformModelCatalogService models;
    private static PlatformModelMapper mapper;

    @BeforeAll static void database() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE pricing_reader LOGIN PASSWORD 'synthetic-pricing-reader' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE ROLE pricing_unprivileged LOGIN PASSWORD 'synthetic-pricing-denied' NOSUPERUSER NOBYPASSRLS");
        owner.execute("CREATE TABLE nw_platform_models(id bigint PRIMARY KEY,current_revision bigint,draft_revision bigint NOT NULL,draft jsonb NOT NULL,draft_changed boolean NOT NULL,time_pricing_rule_id bigint)");
        owner.execute("CREATE TABLE nw_model_time_pricing_rules(id bigint PRIMARY KEY,time_zone text,valid_from date,valid_through date,peak_multiplier numeric,off_peak_multiplier numeric,source_url text,source_version text,calendar_source_url text)");
        owner.execute("CREATE TABLE nw_model_time_pricing_weekdays(rule_id bigint REFERENCES nw_model_time_pricing_rules,ordinal integer,weekday text)");
        owner.execute("CREATE TABLE nw_model_time_pricing_periods(rule_id bigint REFERENCES nw_model_time_pricing_rules,ordinal integer,start_nanos bigint,end_nanos bigint)");
        owner.execute("CREATE TABLE nw_model_time_pricing_dates(rule_id bigint REFERENCES nw_model_time_pricing_rules,band text,ordinal integer,starts_on date,ends_on date)");
        owner.execute("CREATE TABLE nw_model_time_pricing_scopes(rule_id bigint REFERENCES nw_model_time_pricing_rules,ordinal integer,provider_id text,service_id text,profile_version text,model_id text)");
        owner.execute("ALTER TABLE nw_platform_models ADD FOREIGN KEY(time_pricing_rule_id) REFERENCES nw_model_time_pricing_rules(id)");
        owner.execute("GRANT USAGE ON SCHEMA public TO pricing_reader,pricing_unprivileged");
        owner.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO pricing_reader");
        var source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "pricing_reader", "synthetic-pricing-reader");
        reader = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("pricing-target-private-pg", new SpringManagedTransactionFactory(), source));
        configuration.addInterceptor(EVIDENCE);
        for (String resource : List.of("mapper/models/TimePricingMapper.xml", "mapper/models/PlatformModelMapper.xml")) {
            try (var input = new ClassPathResource(resource).getInputStream()) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = sessions.getMapper(PlatformModelMapper.class);
        var properties = new SkillForgeProperties(); properties.setSecretKey("synthetic-target-pricing-independent-master-key-32");
        models = new PlatformModelCatalogService(mapper, new CryptoService(properties), JSON,
                ModelProviderCatalog.standard(), new TimePricingRules(sessions.getMapper(TimePricingMapper.class)));
    }

    @BeforeEach void fixture() throws Exception {
        owner.execute("TRUNCATE nw_platform_models,nw_model_time_pricing_scopes,nw_model_time_pricing_dates,nw_model_time_pricing_periods,nw_model_time_pricing_weekdays,nw_model_time_pricing_rules");
        // 合成持久配置由正式PlatformModelConfig构造；只有外部标识，不执行连接/发现/模型调用。
        for (int i = 1; i <= 24; i++) {
            rule(i);
            insert(1000 + i, "fixture-provider-" + i, "fixture-model-" + i, "LLM", i % 2 == 0,
                    i % 3 == 0 ? 2L : null, (long) i, BigDecimal.TEN, new BigDecimal("20"), null);
        }
        EVIDENCE.clear();
    }

    @Test void missingTargetReturnsNoJsonRowsInOneRealSelectInsteadOfSeven() {
        EVIDENCE.clear(); var old = oldSnapshot("mcp", "synthetic-tool"); var oldReads = EVIDENCE.copy();
        assertThat(oldReads).hasSize(7);
        assertThat(jsonRows(oldReads)).isEqualTo(24);
        assertThat(jsonBytes(oldReads)).isPositive();
        EVIDENCE.clear(); var targeted = new PlatformModelPricingService(models).snapshot("mcp", "synthetic-tool");
        var targetReads = EVIDENCE.copy();
        assertThat(targeted.version()).isEqualTo(old.version()); assertThat(targeted.prices()).isEqualTo(old.prices());
        assertThat(targetReads).hasSize(1); assertThat(targetReads.getFirst().id()).endsWith(".pricingDrafts");
        assertThat(targetReads.getFirst().rows()).isEmpty(); assertThat(jsonRows(targetReads)).isZero(); assertThat(jsonBytes(targetReads)).isZero();
        System.out.printf("pricing-target same-input miss: oldSelects=%d targetSelects=%d oldJsonRows=%d targetJsonRows=0 oldJsonUtf8Bytes=%d targetJsonUtf8Bytes=0%n",
                oldReads.size(), targetReads.size(), jsonRows(oldReads), jsonBytes(oldReads));
    }

    @Test void matchingRowsPreserveAllViewsRulesAndSnapshotIncludingDisabledUnpublishedAndEqualDuplicates() throws Exception {
        rule(101);
        var cache = new ModelCachePricing(CacheCostPrice.Mode.CACHE_READ, BigDecimal.ZERO, null);
        insert(2001, "custom", "deepseek-flash", "LLM", false, null, 101L, null, BigDecimal.ZERO, cache);
        insert(2002, "custom", "deepseek-flash", "LLM", true, 3L, 101L, null, BigDecimal.ZERO, cache);
        // 同provider/model媒体行不能进入非媒体价格目录，也不能引入冲突价格。
        insert(2003, "custom", "deepseek-flash", "IMAGE_GENERATION", true, 1L, 2L, new BigDecimal("999"), BigDecimal.TEN, null);
        var full = models.list(); var expected = filter(full, "custom", "deepseek-flash");
        EVIDENCE.clear(); var actual = models.pricingModels("custom", "deepseek-flash"); var selectedReads = EVIDENCE.copy();
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(actual).hasSize(2); assertThat(actual.getFirst().enabled()).isFalse();
        assertThat(actual.getFirst().publishedRevision()).isNull();
        assertThat(actual.getFirst().inputPricePerMillionCents()).isNull();
        assertThat(actual.getFirst().outputPricePerMillionCents()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(actual.getFirst().cachePricing()).isEqualTo(cache); assertThat(actual.getFirst().timePricing()).isNotNull();
        assertThat(selectedReads).hasSize(6); assertThat(jsonRows(selectedReads)).isEqualTo(2);
        for (var query : selectedReads) {
            for (var row : query.rows()) {
                if (row instanceof TimePricingMapper.RuleRow value) assertThat(value.id()).isEqualTo(101L);
                if (row instanceof TimePricingMapper.WeekdayRow value) assertThat(value.ruleId()).isEqualTo(101L);
                if (row instanceof TimePricingMapper.PeriodRow value) assertThat(value.ruleId()).isEqualTo(101L);
                if (row instanceof TimePricingMapper.DateRow value) assertThat(value.ruleId()).isEqualTo(101L);
                if (row instanceof TimePricingMapper.ScopeRow value) assertThat(value.ruleId()).isEqualTo(101L);
            }
        }
        var old = oldSnapshot("custom", "deepseek-flash");
        var targeted = new PlatformModelPricingService(models).snapshot("custom", "deepseek-flash");
        assertThat(targeted.version()).isEqualTo(old.version()); assertThat(targeted.prices()).isEqualTo(old.prices());
        owner.update("UPDATE nw_platform_models SET draft=jsonb_set(draft,'{output_price_per_million_cents}','\"1\"'::jsonb) WHERE id=2002");
        assertThatThrownBy(() -> oldSnapshot("custom", "deepseek-flash")).isInstanceOf(IllegalStateException.class).hasMessageContaining("不唯一");
        assertThatThrownBy(() -> new PlatformModelPricingService(models).snapshot("custom", "deepseek-flash"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不唯一");
    }

    @Test void nullArgumentsUseOriginalObjectsEqualsSemanticsAndNoRulesStillMeansOneSelect() throws Exception {
        insert(3001, null, null, "LLM", false, null, null, BigDecimal.ZERO, null, null);
        var expected = filter(models.list(), null, null);
        EVIDENCE.clear(); assertThat(models.pricingModels(null, null)).containsExactlyElementsOf(expected);
        assertThat(expected).hasSize(1); assertThat(EVIDENCE.copy()).hasSize(1);
        // 原价格Key不接受null；目标读保留原匹配与后续拒绝，不能把未知键改成空目录成功。
        assertThatThrownBy(() -> oldSnapshot(null, null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("计价键不能为空");
        assertThatThrownBy(() -> new PlatformModelPricingService(models).snapshot(null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("计价键不能为空");
        assertThat(models.pricingModels(null, "fixture-model-1")).isEmpty();
    }

    @Test void productionXmlWorksUnderReadOnlyNonBypassRoleAndDoesNotGrantCatalogAccessToOtherRole() {
        assertThat(reader.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(models.pricingModels("fixture-provider-1", "fixture-model-1")).hasSize(1);
        assertThatThrownBy(() -> reader.update("UPDATE nw_platform_models SET draft_changed=false WHERE id=1001"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var denied = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "pricing_unprivileged", "synthetic-pricing-denied"));
        assertThatThrownBy(() -> denied.queryForList("SELECT id FROM nw_platform_models"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    /** 旧目标读取原样使用真实models.list+原精确过滤；只把所得views交回同一正式计价算法。 */
    private static ModelPriceSource.Snapshot oldSnapshot(String provider, String model) {
        var legacyDirectory = mock(PlatformModelCatalogService.class);
        when(legacyDirectory.pricingModels(provider, model)).thenAnswer(call -> filter(models.list(), provider, model));
        return new PlatformModelPricingService(legacyDirectory).snapshot(provider, model);
    }

    private static List<ModelView> filter(List<ModelView> values, String provider, String model) {
        return values.stream().filter(value -> Objects.equals(provider, value.providerId()) && Objects.equals(model, value.modelId()))
                .filter(value -> !List.of("IMAGE_GENERATION", "VIDEO_GENERATION", "SPEECH_RECOGNITION", "SPEECH_SYNTHESIS").contains(value.kind())).toList();
    }

    private static void insert(long id, String provider, String model, String kind, boolean enabled, Long published,
            Long ruleId, BigDecimal input, BigDecimal output, ModelCachePricing cache) throws Exception {
        long revision = published == null ? 1 : published + 1;
        var config = new PlatformModelConfig(Long.toString(id), revision, "synthetic-model-" + id, kind, enabled,
                provider, "custom-openai-compatible", "custom-v1", "openai-chat-completions", "bearer-api-key",
                "openai-compatible-chat-v1", "http://127.0.0.1:18080/v1", model, null, 4096, 8192, 1024,
                null, "DISABLED", 30, null, null, input, output, null, "CNY",
                com.skillforge.runtime.api.media.ModelImagePolicy.textOnly(), null,
                com.skillforge.runtime.api.port.ModelPort.PromptPrefixCapabilities.unverified(), null, cache, null);
        owner.update("INSERT INTO nw_platform_models VALUES(?,?,?,?::jsonb,true,?)", id, published, revision, JSON.writeValueAsString(config), ruleId);
    }

    private static void rule(long id) {
        owner.update("INSERT INTO nw_model_time_pricing_rules VALUES(?,'Asia/Shanghai','2026-01-01','2026-12-31',1,0.5,'https://pricing.example.cn','synthetic-v1',NULL)", id);
        owner.update("INSERT INTO nw_model_time_pricing_weekdays VALUES(?,0,'MONDAY')", id);
        owner.update("INSERT INTO nw_model_time_pricing_periods VALUES(?,0,32400000000000,43200000000000)", id);
        owner.update("INSERT INTO nw_model_time_pricing_dates VALUES(?,'PEAK',0,'2026-02-01','2026-02-02')", id);
        owner.update("INSERT INTO nw_model_time_pricing_scopes VALUES(?,0,NULL,NULL,NULL,NULL)", id);
    }

    private static long jsonRows(List<Query> queries) {
        return queries.stream().filter(query -> query.id().endsWith(".listDrafts") || query.id().endsWith(".pricingDrafts"))
                .mapToLong(query -> query.rows().size()).sum();
    }
    private static long jsonBytes(List<Query> queries) {
        return queries.stream().flatMap(query -> query.rows().stream()).mapToLong(row ->
                row instanceof String value ? value.getBytes(StandardCharsets.UTF_8).length
                        : row instanceof PlatformModelMapper.PricingDraft value ? value.storedDraft().getBytes(StandardCharsets.UTF_8).length : 0).sum();
    }
    private record Query(String id, List<?> rows) { }

    /** StatementHandler.query围绕实际JDBC查询计数，排除Executor缓存命中与手写估计。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
    private static final class QueryEvidence implements Interceptor {
        private final List<Query> queries = new ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) SystemMetaObject.forObject(invocation.getTarget()).getValue("delegate.mappedStatement");
            var rows = (List<?>) invocation.proceed();
            queries.add(new Query(statement.getId(), List.copyOf(rows)));
            return rows;
        }
        void clear() { queries.clear(); }
        List<Query> copy() { return List.copyOf(queries); }
    }
}
