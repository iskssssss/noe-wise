package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.persistence.CapabilityDefinitionMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import com.skillforge.core.capability.persistence.CapabilityVersionMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation;
import com.skillforge.core.shared.exception.ApiException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 生产 Catalog 迁移、真实平台绑定与受限角色；摘要和完整详情使用同一事务快照。 */
@Testcontainers(disabledWithoutDocker = false)
class SkillCatalogSummaryPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skill_catalog_summary_test");
    private static SqlSessionFactory factory;
    private static SqlSessionFactory tenantFactory;

    @BeforeAll static void initialize() throws Exception {
        try (var connection = owner(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE nw_connector_instances(tenant_id bigint,instance_id text,UNIQUE(tenant_id,instance_id));"
                    + "CREATE TABLE nw_agent_capability_mounts(id bigint);"
                    + "CREATE TABLE nw_workflow_test_sessions(id bigint,capability_definition_id bigint)");
            for (var migration : List.of("V86__capability_registry.sql", "V143__skill_default_version.sql", "V264__capability_current_publications.sql")) {
                try (var source = SkillCatalogSummaryPostgresTest.class.getClassLoader().getResourceAsStream("db/migration/" + migration)) {
                    assertNotNull(source); sql.execute(new String(source.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            seed(connection, 1L, null, "SKILL", "PUBLISHED", true);
            seed(connection, 2L, null, "SKILL", "DRAFT", false);
            seed(connection, 3L, null, "SKILL", "ARCHIVED", true);
            seed(connection, 4L, null, "TOOL", "PUBLISHED", true);
            seed(connection, 5L, 7L, "SKILL", "PUBLISHED", true);
            seed(connection, 6L, null, "SKILL", "DRAFT", false);
            seed(connection, 7L, 8L, "SKILL", "PUBLISHED", true);
            seed(connection, 8L, null, "WORKFLOW", "PUBLISHED", true);
            seed(connection, 9L, null, "KNOWLEDGE_SOURCE", "PUBLISHED", true);
            seed(connection, 10L, 7L, "TOOL", "PUBLISHED", true);
            seed(connection, 11L, 7L, "WORKFLOW", "PUBLISHED", true);
            seed(connection, 12L, 7L, "KNOWLEDGE_SOURCE", "PUBLISHED", true);
            seed(connection, 14L, null, "TOOL", "PUBLISHED", true);
            seed(connection, 15L, null, "TOOL", "PUBLISHED", false);
            seed(connection, 16L, null, "TOOL", "PUBLISHED", true);
            seed(connection, 17L, null, "TOOL", "ARCHIVED", true);
            seed(connection, 18L, null, "TOOL", "DRAFT", true);
            sql.execute("INSERT INTO nw_capability_versions(id,definition_id,tenant_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at,published_at) "
                    + "SELECT x.id,v.definition_id,v.tenant_id,v.capability_type,'history-'||x.id,v.name,v.trust_level,v.descriptor,'history-hash-'||x.id,x.status,'2030-01-01T00:00:00Z',now() "
                    + "FROM (VALUES(1001,11,'DRAFT'),(1002,11,'ARCHIVED'),(1003,11,'DEPRECATED'),(1004,11,'PUBLISHED'),"
                    + "(5001,51,'DRAFT'),(5002,51,'ARCHIVED'),(5003,51,'DEPRECATED'),(5004,51,'PUBLISHED')) x(id,base,status) "
                    + "JOIN nw_capability_versions v ON v.id=x.base");
            sql.execute("INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,capability_key,name,trust_level,status,created_at,updated_at) VALUES"
                    + "(19,7,'SKILL','history-draft','机构草稿','TENANT','DRAFT',now(),now()),"
                    + "(20,7,'SKILL','history-archived','机构归档','TENANT','ARCHIVED',now(),now());"
                    + "INSERT INTO nw_capability_versions(id,definition_id,tenant_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at,published_at) "
                    + "SELECT x.id,x.definition,v.tenant_id,v.capability_type,v.version_key,v.name,v.trust_level,v.descriptor,v.content_hash,x.status,'2030-01-01T00:00:00Z',now() "
                    + "FROM (VALUES(191,19,'DRAFT'),(201,20,'ARCHIVED')) x(id,definition,status) JOIN nw_capability_versions v ON v.id=51");
            sql.execute("INSERT INTO nw_capability_versions(id,definition_id,tenant_id,capability_type,version_key,name,description,trust_level,descriptor,content_hash,status,created_at,published_at) "
                    + "SELECT 20000+v.id*10+x.n,v.definition_id,v.tenant_id,v.capability_type,'generic-history-'||(20000+v.id*10+x.n),v.name,"
                    + "'摘要说明-'||(20000+v.id*10+x.n),v.trust_level,v.descriptor,'generic-hash-'||(20000+v.id*10+x.n),x.status,'2030-01-01T00:00:00Z',now() "
                    + "FROM nw_capability_versions v CROSS JOIN (VALUES(1,'DRAFT'),(2,'ARCHIVED'),(3,'DEPRECATED'),(4,'PUBLISHED')) x(n,status) "
                    + "WHERE v.id IN(41,101,81,111,91,121)");
            sql.execute("INSERT INTO nw_workflow_test_sessions VALUES(1,6),(2,14);"
                    + "UPDATE nw_capability_versions SET status='ARCHIVED' WHERE id IN (31,161);"
                    + "CREATE ROLE skillforge_platform LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS;"
                    + "CREATE ROLE skillforge_app LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS;"
                    + "GRANT USAGE ON SCHEMA public TO skillforge_platform,skillforge_app; GRANT SELECT ON ALL TABLES IN SCHEMA public TO skillforge_platform,skillforge_app");
        }
        for (var role : List.of("skillforge_platform", "skillforge_app")) {
            var data = new PGSimpleDataSource(); data.setURL(PG.getJdbcUrl());
            data.setUser(role); data.setPassword("test-only");
            var config = new MybatisConfiguration(new Environment(role, new JdbcTransactionFactory(), data));
            config.setMapUnderscoreToCamelCase(true); config.addInterceptor(new TenantRlsInterceptor());
            config.addMapper(CapabilityDefinitionMapper.class); config.addMapper(CapabilityVersionMapper.class);
            for (var path : List.of("mapper/CapabilityDefinitionMapper.xml", "mapper/CapabilityVersionMapper.xml", "mapper/CapabilityCatalogContextMapper.xml")) {
                try (var source = SkillCatalogSummaryPostgresTest.class.getClassLoader().getResourceAsStream(path)) {
                    assertNotNull(source); new XMLMapperBuilder(source, config, path, config.getSqlFragments()).parse();
                }
            }
            var built = new MybatisSqlSessionFactoryBuilder().build(config);
            if (role.equals("skillforge_platform")) factory = built; else tenantFactory = built;
        }
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static void seed(Connection connection, long id, Long tenant, String type, String status, boolean published) throws Exception {
        try (var insert = connection.prepareStatement("INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,capability_key,name,trust_level,status,created_at,updated_at,description) VALUES(?,?,?,?,?,?,?,'2026-10-29T12:00:00Z','2026-10-29T12:00:00Z',?)")) {
            insert.setLong(1, id); insert.setObject(2, tenant); insert.setString(3, type);
            insert.setString(4, id == 1 || tenant != null ? "same-key" : "skill-" + id);
            insert.setString(5, "技能-" + id); insert.setString(6, tenant == null ? "PLATFORM" : "TENANT");
            insert.setString(7, status); insert.setString(8, "用途-" + id); insert.executeUpdate();
        }
        if (!published) return;
        for (long version : List.of(id * 10 + 1, id * 10 + 2)) {
            var descriptor = Map.of("estimated_tokens", version * 100, "package", Map.of("entrypoint", "SKILL.md", "files", List.of(Map.of(
                    "path", "SKILL.md", "encoding", "UTF8", "content", "x".repeat(131072)))));
            try (var insert = connection.prepareStatement("INSERT INTO nw_capability_versions(id,definition_id,tenant_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at,published_at) VALUES(?,?,?,?,?,?,?,?::jsonb,?,'PUBLISHED',now(),now())")) {
                insert.setLong(1, version); insert.setLong(2, id); insert.setObject(3, tenant); insert.setString(4, type);
                insert.setString(5, "v" + version); insert.setString(6, "版本-" + version);
                insert.setString(7, tenant == null ? "PLATFORM" : "TENANT");
                insert.setString(8, new ObjectMapper().writeValueAsString(descriptor));
                insert.setString(9, "hash-" + version); insert.executeUpdate();
            }
        }
        try (var sql = connection.createStatement()) {
            sql.execute("UPDATE nw_capability_definitions SET default_version_id=" + (id * 10 + 1) + " WHERE id=" + id);
        }
    }

    private static CapabilityCatalogService catalog(SqlSession session) {
        return new CapabilityCatalogService(session.getMapper(CapabilityDefinitionMapper.class),
                session.getMapper(CapabilityVersionMapper.class), mock(ToolDeploymentRecordMapper.class),
                new CapabilityDescriptorCodec(new ObjectMapper()),
                new CapabilityCatalogDatabaseContext(session.getMapper(CapabilityCatalogContextMapper.class)), mock(WorkflowTestIsolation.class));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void platformSummaryKeepsExactDefaultsAndStatesWithoutReadingPackageBodies() throws Exception {
        TenantContext.clear();
        try (var session = factory.openSession()) {
            session.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            session.getConnection().setReadOnly(true);
            var service = catalog(session);
            var rows = service.listPlatformSkills();
            assertEquals(List.of(
                    new SkillCatalogSummary(3L, "skill-3", "技能-3", "用途-3", "ARCHIVED", 31L, "v31"),
                    new SkillCatalogSummary(2L, "skill-2", "技能-2", "用途-2", "DRAFT", null, null),
                    new SkillCatalogSummary(1L, "same-key", "技能-1", "用途-1", "PUBLISHED", 11L, "v11")), rows);
            // v12比v11晚创建但不能替代default；完整详情继续保留package.files[].content。
            var detail = service.getVersion(CapabilityCatalogScope.platform(), 11L);
            var packageValue = (Map<?, ?>) detail.descriptor().get("package");
            var files = (List<?>) packageValue.get("files");
            assertEquals("x".repeat(131072), ((Map<?, ?>) files.getFirst()).get("content"));
            assertEquals("v31", service.getVersion(CapabilityCatalogScope.platform(), 31L).versionKey());
            try (var sql = session.getConnection().createStatement(); var role = sql.executeQuery(
                    "SELECT current_user,NOT rolsuper AND NOT rolbypassrls,current_setting('app.tenant_id'),current_setting('app.platform_admin') FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(role.next()); assertEquals("skillforge_platform", role.getString(1));
                assertTrue(role.getBoolean(2)); assertEquals("", role.getString(3)); assertEquals("true", role.getString(4));
            }
            assertNull(session.getMapper(CapabilityVersionMapper.class).selectById(51L), "平台绑定不能读取tenant7完整版本");
            try (var sql = session.getConnection().createStatement(); var hidden = sql.executeQuery(
                    "SELECT count(*) FROM nw_capability_definitions WHERE tenant_id IS NOT NULL")) {
                assertTrue(hidden.next()); assertEquals(0, hidden.getLong(1), "真实平台受限角色不可见tenant7/tenant8");
            }
            var query = new LambdaQueryWrapper<CapabilityVersionDO>()
                    .isNull(CapabilityVersionDO::getTenantId).in(CapabilityVersionDO::getId, List.of(11L, 31L));
            var statement = factory.getConfiguration().getMappedStatement(CapabilityVersionMapper.class.getName() + ".selectList");
            var arguments = Map.<String, Object>of("ew", query);
            var bound = statement.getBoundSql(arguments);
            long baselineBytes;
            try (var aggregate = session.getConnection().prepareStatement("SELECT sum(octet_length(row_to_json(r)::text)) FROM (" + bound.getSql() + ") r")) {
                new DefaultParameterHandler(statement, arguments, bound).setParameters(aggregate);
                try (var result = aggregate.executeQuery()) { assertTrue(result.next()); baselineBytes = result.getLong(1); }
            }
            var summary = factory.getConfiguration().getMappedStatement(CapabilityDefinitionMapper.class.getName() + ".listPlatformSkills").getBoundSql(null).getSql();
            try (var sql = session.getConnection().createStatement(); var result = sql.executeQuery(summary)) {
                assertEquals(7, result.getMetaData().getColumnCount());
                for (int column = 1; column <= 7; column++) assertNotEquals("jsonb", result.getMetaData().getColumnTypeName(column));
            }
            try (var sql = session.getConnection().createStatement(); var aggregate = sql.executeQuery(
                    "SELECT count(*),sum(octet_length(row_to_json(r)::text)) FROM (" + summary + ") r WHERE default_version_id IS NOT NULL")) {
                assertTrue(aggregate.next()); assertEquals(2, aggregate.getLong(1));
                long summaryBytes = aggregate.getLong(2); assertTrue(baselineBytes > summaryBytes + 262144);
                System.out.println("SKILL_CATALOG_READ_BYTES default_rows=2 full_versions=" + baselineBytes + " summary=" + summaryBytes
                        + " boundary=selected-default-row-json-only-no-latency-or-disk-claim");
            }
        }
    }

    @Test void existingTenantContextCannotBecomePlatformCatalogScope() {
        TenantContext.setTenantId("7");
        try (var session = factory.openSession()) {
            var failure = assertThrows(ApiException.class, () -> catalog(session).listPlatformSkills());
            assertEquals(HttpStatus.FORBIDDEN, failure.getStatus());
        }
    }

    @Test void requirementOptionsUseExactPublishedDefaultsAcrossAllTypesAndBothScopes() throws Exception {
        var platformIds = Map.of(CapabilityType.SKILL, 1L, CapabilityType.TOOL, 4L,
                CapabilityType.WORKFLOW, 8L, CapabilityType.KNOWLEDGE_SOURCE, 9L);
        var ownIds = Map.of(CapabilityType.SKILL, 5L, CapabilityType.TOOL, 10L,
                CapabilityType.WORKFLOW, 11L, CapabilityType.KNOWLEDGE_SOURCE, 12L);
        TenantContext.clear();
        try (var session = factory.openSession()) {
            var service = catalog(session);
            for (var type : CapabilityType.values()) {
                long id = platformIds.get(type);
                assertEquals(List.of(new CapabilityRequirementOption(id, "技能-" + id, id * 10 + 1, "v" + (id * 10 + 1),
                                type == CapabilityType.SKILL ? (id * 10 + 1) * 100 : null)),
                        service.listRequirementOptions(CapabilityCatalogScope.platform(), type));
            }
            assertNotNull(service.getVersion(CapabilityCatalogScope.platform(), 31L).descriptor().get("package"),
                    "归档版本虽不在依赖目录，完整详情仍可读");
        }
        TenantContext.setTenantId("7");
        try (var session = tenantFactory.openSession()) {
            session.getConnection().setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            session.getConnection().setReadOnly(true);
            var service = catalog(session);
            for (var type : CapabilityType.values()) {
                long own = ownIds.get(type), shared = platformIds.get(type);
                var options = service.listRequirementOptions(CapabilityCatalogScope.tenant(7L), type);
                assertEquals(List.of(own, shared), options.stream().map(CapabilityRequirementOption::definitionId).toList());
                assertTrue(options.stream().allMatch(option -> option.versionId() == option.definitionId() * 10 + 1),
                        "旧default不能被较新的第二版本替代");
                for (var option : options) {
                    assertEquals(type == CapabilityType.SKILL ? option.versionId() * 100 : null,
                            option.skillEstimatedTokens(), "入口估算必须来自精确发布版本；其他能力不携带技能估算");
                }
            }
            var mapper = session.getMapper(CapabilityDefinitionMapper.class);
            assertEquals(List.of(1L), mapper.listRequirementOptions(8L, CapabilityType.SKILL).stream()
                    .map(CapabilityRequirementOption::definitionId).toList(), "伪造tenant8参数仍只可见已发布平台行");
            var rejected = assertThrows(ApiException.class,
                    () -> service.listRequirementOptions(CapabilityCatalogScope.tenant(8L), CapabilityType.SKILL));
            assertEquals(HttpStatus.FORBIDDEN, rejected.getStatus());
            try (var sql = session.getConnection().createStatement(); var role = sql.executeQuery(
                    "SELECT current_user,NOT rolsuper AND NOT rolbypassrls,current_setting('app.tenant_id'),current_setting('app.platform_admin') FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(role.next()); assertEquals("skillforge_app", role.getString(1));
                assertTrue(role.getBoolean(2)); assertEquals("7", role.getString(3)); assertEquals("false", role.getString(4));
            }
            var statement = tenantFactory.getConfiguration().getMappedStatement(CapabilityDefinitionMapper.class.getName() + ".listRequirementOptions");
            var arguments = Map.<String, Object>of("tenant", 7L, "type", CapabilityType.SKILL);
            var bound = statement.getBoundSql(arguments);
            try (var query = session.getConnection().prepareStatement(bound.getSql())) {
                new DefaultParameterHandler(statement, arguments, bound).setParameters(query);
                try (var rows = query.executeQuery()) {
                    assertEquals(5, rows.getMetaData().getColumnCount());
                    for (int column = 1; column <= 5; column++) assertNotEquals("jsonb", rows.getMetaData().getColumnTypeName(column));
                }
            }
            assertNotNull(service.getVersion(CapabilityCatalogScope.tenant(7L), 51L).descriptor().get("package"));

        }
    }

    @Test void productionDefaultAndVersionGuardsRejectMismatchedIdentityPairs() throws Exception {
        try (var connection = owner(); var sql = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class,
                    () -> sql.execute("UPDATE nw_capability_definitions SET default_version_id=51 WHERE id=1"));
            assertThrows(java.sql.SQLException.class, () -> sql.execute("INSERT INTO nw_capability_versions"
                    + "(id,definition_id,tenant_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at)"
                    + " VALUES(199,1,7,'SKILL','wrong-owner','wrong','TENANT','{}','wrong-owner','DRAFT',now())"));
            assertThrows(java.sql.SQLException.class, () -> sql.execute("INSERT INTO nw_capability_versions"
                    + "(id,definition_id,tenant_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at)"
                    + " VALUES(198,1,NULL,'TOOL','wrong-type','wrong','PLATFORM','{}','wrong-type','DRAFT',now())"));
        }
        TenantContext.clear();
        try (var session = factory.openSession()) {
            assertEquals(List.of(new CapabilityRequirementOption(1L, "技能-1", 11L, "v11", 1100L)),
                    catalog(session).listRequirementOptions(CapabilityCatalogScope.platform(), CapabilityType.SKILL));
        }
    }

    @Test void skillVersionHistoryKeepsVisibleStatusesAndStableOrderWithoutPackageColumns() throws Exception {
        TenantContext.clear();
        try (var session = factory.openSession()) {
            var service = catalog(session);
            var history = service.listSkillVersionSummaries(CapabilityCatalogScope.platform(), 1L);
            assertEquals(List.of(1004L, 1003L, 1002L, 1001L, 12L, 11L), history.stream().map(SkillVersionSummary::id).toList());
            assertEquals(List.of("PUBLISHED", "DEPRECATED", "ARCHIVED", "DRAFT"), history.subList(0, 4).stream()
                    .map(SkillVersionSummary::status).toList());
            assertEquals(List.of(32L, 31L), service.listSkillVersionSummaries(CapabilityCatalogScope.platform(), 3L)
                    .stream().map(SkillVersionSummary::id).toList(), "平台可读取归档定义的完整可见历史");
            assertTrue(service.listSkillVersionSummaries(CapabilityCatalogScope.platform(), 2L).isEmpty());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                    () -> service.listSkillVersionSummaries(CapabilityCatalogScope.platform(), 4L)).getStatus());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                    () -> service.listSkillVersionSummaries(CapabilityCatalogScope.platform(), 9999L)).getStatus());
        }
        TenantContext.setTenantId("7");
        try (var session = tenantFactory.openSession()) {
            var service = catalog(session);
            var own = service.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 5L);
            assertEquals(List.of(5004L, 5003L, 5002L, 5001L, 52L, 51L), own.stream().map(SkillVersionSummary::id).toList());
            var shared = service.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 1L);
            assertEquals(List.of(1004L, 12L, 11L), shared.stream().map(SkillVersionSummary::id).toList());
            assertTrue(shared.stream().allMatch(version -> version.status().equals("PUBLISHED")));
            assertEquals(List.of(new SkillVersionSummary(191L, "v51", "DRAFT")),
                    service.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 19L));
            assertEquals(List.of(new SkillVersionSummary(201L, "v51", "ARCHIVED")),
                    service.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), 20L));
            for (long hidden : List.of(2L, 3L, 7L, 9999L)) {
                assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                        () -> service.listSkillVersionSummaries(CapabilityCatalogScope.tenant(7L), hidden)).getStatus());
            }
            var statement = tenantFactory.getConfiguration().getMappedStatement(CapabilityVersionMapper.class.getName() + ".listSkillVersionSummaries");
            var arguments = Map.<String, Object>of("tenant", 7L, "definitionId", 5L);
            var bound = statement.getBoundSql(arguments);
            try (var query = session.getConnection().prepareStatement(bound.getSql())) {
                new DefaultParameterHandler(statement, arguments, bound).setParameters(query);
                try (var rows = query.executeQuery()) {
                    assertEquals(3, rows.getMetaData().getColumnCount());
                    for (int column = 1; column <= 3; column++) assertNotEquals("jsonb", rows.getMetaData().getColumnTypeName(column));
                }
            }
            assertNotNull(service.getVersion(CapabilityCatalogScope.tenant(7L), 51L).descriptor().get("package"));
        }
    }

    @Test void genericVersionCardsKeepAllTypesAndVisibleStatusesWithFiveScalarColumns() throws Exception {
        var publicDefinitions = Map.of(CapabilityType.SKILL, 1L, CapabilityType.TOOL, 4L,
                CapabilityType.WORKFLOW, 8L, CapabilityType.KNOWLEDGE_SOURCE, 9L);
        var ownDefinitions = Map.of(CapabilityType.SKILL, 5L, CapabilityType.TOOL, 10L,
                CapabilityType.WORKFLOW, 11L, CapabilityType.KNOWLEDGE_SOURCE, 12L);
        try (var platformSession = factory.openSession(); var tenantSession = tenantFactory.openSession()) {
            var platform = catalog(platformSession); var tenant = catalog(tenantSession);
            for (var type : CapabilityType.values()) {
                long sharedId = publicDefinitions.get(type), ownId = ownDefinitions.get(type);
                long sharedBase = sharedId * 10 + 1, ownBase = ownId * 10 + 1;
                long sharedHistory = type == CapabilityType.SKILL ? 1000 : 20000 + sharedBase * 10;
                long ownHistory = type == CapabilityType.SKILL ? 5000 : 20000 + ownBase * 10;
                TenantContext.clear();
                var platformCards = platform.listVersionSummaries(CapabilityCatalogScope.platform(), sharedId);
                assertEquals(List.of(sharedHistory + 4, sharedHistory + 3, sharedHistory + 2, sharedHistory + 1,
                        sharedBase + 1, sharedBase), platformCards.stream().map(CapabilityVersionSummary::id).toList());
                assertEquals(List.of("PUBLISHED", "DEPRECATED", "ARCHIVED", "DRAFT"), platformCards.subList(0, 4)
                        .stream().map(CapabilityVersionSummary::status).toList());
                var full = platform.getVersion(CapabilityCatalogScope.platform(), sharedBase);
                var baselineCard = platformCards.getLast();
                assertEquals(new CapabilityVersionSummary(full.id(), full.versionKey(), full.name(), full.description(), full.status()), baselineCard);
                assertNotNull(full.descriptor().get("package"));
                if (type != CapabilityType.SKILL) {
                    assertEquals("摘要说明-" + (sharedHistory + 4), platformCards.getFirst().description());
                    assertEquals("版本-" + sharedBase, platformCards.getFirst().name());
                }
                TenantContext.setTenantId("7");
                var ownCards = tenant.listVersionSummaries(CapabilityCatalogScope.tenant(7L), ownId);
                assertEquals(List.of(ownHistory + 4, ownHistory + 3, ownHistory + 2, ownHistory + 1, ownBase + 1, ownBase),
                        ownCards.stream().map(CapabilityVersionSummary::id).toList());
                var sharedCards = tenant.listVersionSummaries(CapabilityCatalogScope.tenant(7L), sharedId);
                assertEquals(List.of(sharedHistory + 4, sharedBase + 1, sharedBase),
                        sharedCards.stream().map(CapabilityVersionSummary::id).toList());
                assertTrue(sharedCards.stream().allMatch(card -> card.status().equals("PUBLISHED")));
            }
            assertEquals("DRAFT", tenant.listVersionSummaries(CapabilityCatalogScope.tenant(7L), 19L).getFirst().status());
            assertEquals("ARCHIVED", tenant.listVersionSummaries(CapabilityCatalogScope.tenant(7L), 20L).getFirst().status());
            for (long hidden : List.of(2L, 3L, 7L, 9999L)) {
                assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                        () -> tenant.listVersionSummaries(CapabilityCatalogScope.tenant(7L), hidden)).getStatus());
            }
            var statement = tenantFactory.getConfiguration().getMappedStatement(CapabilityVersionMapper.class.getName() + ".listVersionSummaries");
            var arguments = Map.<String, Object>of("tenant", 7L, "definitionId", 12L);
            var bound = statement.getBoundSql(arguments);
            try (var query = tenantSession.getConnection().prepareStatement(bound.getSql())) {
                new DefaultParameterHandler(statement, arguments, bound).setParameters(query);
                try (var rows = query.executeQuery()) {
                    assertEquals(5, rows.getMetaData().getColumnCount());
                    for (int column = 1; column <= 5; column++) assertNotEquals("jsonb", rows.getMetaData().getColumnTypeName(column));
                }
            }
            TenantContext.clear();
            assertTrue(platform.listVersionSummaries(CapabilityCatalogScope.platform(), 2L).isEmpty());
            assertEquals(List.of(32L, 31L), platform.listVersionSummaries(CapabilityCatalogScope.platform(), 3L)
                    .stream().map(CapabilityVersionSummary::id).toList());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                    () -> platform.listVersionSummaries(CapabilityCatalogScope.platform(), 5L)).getStatus());
        }
    }
}
