package com.skillforge.platform.operationoptions;

import com.skillforge.platform.controller.PlatformOperationOptionMapper;
import java.io.InputStream;
import java.sql.DriverManager;
import java.util.Set;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离 PostgreSQL 执行正式候选 SQL，核验授权集合先过滤、来源策略及接入 RLS。 */
@Testcontainers(disabledWithoutDocker = false)
class PlatformOperationOptionMapperIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static SqlSessionFactory sessions;

    @BeforeAll static void schema() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var sql = connection.createStatement()) {
            sql.execute("CREATE ROLE option_reader LOGIN PASSWORD 'isolated-test-only'");
            sql.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY,name text,slug text)");
            sql.execute("INSERT INTO nw_tenants VALUES(7,'门店甲','clinic-a'),(8,'其他机构','clinic-b'),(9223372036854775807,'门店乙','clinic-c')");
            sql.execute("CREATE TABLE nw_agent_templates(id bigint PRIMARY KEY,name text,version text,owner_kind text,status text,distribution_policy text)");
            sql.execute("INSERT INTO nw_agent_templates VALUES(31,'可直接授权','v1','platform','published','AUTHORIZED_DEPLOY'),(32,'开放安装','v1','platform','published','OPEN_INSTALL'),(33,'草稿','v1','platform','draft','AUTHORIZED_DEPLOY'),(34,'机构源','v1','tenant','published','AUTHORIZED_DEPLOY')");
            sql.execute("CREATE TABLE nw_institution_integrations(tenant_id bigint,integration_id text,PRIMARY KEY(tenant_id,integration_id))");
            sql.execute("CREATE TABLE nw_external_institution_mappings(tenant_id bigint,integration_id text,namespace text,external_institution_ref text)");
            sql.execute("INSERT INTO nw_institution_integrations VALUES(7,'native'),(7,'mapped'),(8,'hidden')");
            sql.execute("INSERT INTO nw_external_institution_mappings VALUES(7,'mapped','门诊','clinic-7'),(8,'hidden','秘密','private')");
            sql.execute("ALTER TABLE nw_institution_integrations ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE nw_institution_integrations FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY option_tenant ON nw_institution_integrations USING(tenant_id::text=current_setting('app.tenant_id',true))");
            sql.execute("GRANT SELECT ON nw_tenants,nw_agent_templates,nw_institution_integrations,nw_external_institution_mappings TO option_reader");
        }
        var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl());
        source.setUser("option_reader"); source.setPassword("isolated-test-only");
        var configuration = new Configuration(new Environment("options", new JdbcTransactionFactory(), source));
        String resource = "mapper/PlatformOperationOptionMapper.xml";
        try (InputStream input = PlatformOperationOptionMapper.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test void countAndPaginationOnlySeeAuthorizedTargetsAndKeepDecimalIds() {
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(PlatformOperationOptionMapper.class);
            Set<Long> allowed = Set.of(7L, Long.MAX_VALUE);
            assertEquals(2, mapper.tenantCount(allowed, "门店", null));
            var first = mapper.tenants(allowed, "门店", null, 1, 0).getFirst().id();
            var second = mapper.tenants(allowed, "门店", null, 1, 1).getFirst().id();
            assertEquals(Set.of("7", "9223372036854775807"), Set.of(first, second));
            assertEquals(0, mapper.tenantCount(Set.of(), "", null));
            assertTrue(mapper.tenants(Set.of(7L), "", 8L, 20, 0).isEmpty());
        }
    }

    @Test void directGrantCandidatesExcludeOpenInstallDraftAndTenantOwnedSources() {
        try (var session = sessions.openSession()) {
            var mapper = session.getMapper(PlatformOperationOptionMapper.class);
            var allowed = Set.of(31L, 32L, 33L, 34L);
            assertEquals(1, mapper.definitionCount(allowed, true, "", null));
            assertEquals("31", mapper.definitions(allowed, true, "", null, 20, 0).getFirst().id());
            assertEquals(4, mapper.definitionCount(allowed, false, "", null));
        }
    }

    @Test void nativeRegisteredIntegrationIsSelectableWhileForeignTenantIsRejectedByRls() throws Exception {
        try (var session = sessions.openSession(); var statement = session.getConnection().createStatement()) {
            statement.execute("SET app.tenant_id='7'");
            var mapper = session.getMapper(PlatformOperationOptionMapper.class);
            assertEquals(2, mapper.integrationCount(7, "", null));
            assertEquals("native", mapper.integrations(7, "native", null, 20, 0).getFirst().id());
            assertEquals("门诊 · clinic-7", mapper.integrations(7, "门诊", null, 20, 0).getFirst().label());
            assertEquals(0, mapper.integrationCount(8, "", null));
        }
    }
}
