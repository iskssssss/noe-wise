package com.skillforge.core.knowledge;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.foundation.access.PlatformObservationTargetMapper;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.knowledge.integration.runtime.knowledge.CurrentKnowledgeFixtures;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.knowledge.integration.runtime.knowledge.KnowledgeBindingDatabaseFixture;
import com.skillforge.core.knowledge.mapper.KnowledgeObservationAccessMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeHandle;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 完整正式schema、不可变冻结来源与受限PG角色验证知识轨迹的当前读取边界。 */
@Testcontainers(disabledWithoutDocker = false)
class KnowledgeObservationAccessDatabaseTest {
    private static final String ROLE = "knowledge_observation_reader";
    private static final String PASSWORD = "knowledge-observation-fixture";
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static DataSource admin;
    private static KnowledgeObservationAccessMapper mapper;
    private static TransactionTemplate reader;
    private static JdbcTemplate readerJdbc;
    private static FrozenKnowledgeTarget target;
    private static List<KnowledgeSourceVersion> versions;

    @BeforeAll static void database() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        admin = source(DATABASE.getUsername(), DATABASE.getPassword());
        try (var connection = admin.getConnection()) {
            CurrentHostFixtures.tenant(connection, 1);
            CurrentHostFixtures.tenant(connection, 2);
        }
        CurrentKnowledgeFixtures.model(admin);
        CurrentKnowledgeFixtures.seed(admin);
        target = KnowledgeBindingDatabaseFixture.target();
        versions = List.of(new KnowledgeSourceVersion("10", "v1"), new KnowledgeSourceVersion("10", "v2"));
        new JdbcKnowledgeBindings(admin).publish(new TenantId("1"), target);
        sql("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        sql("GRANT USAGE ON SCHEMA public TO " + ROLE);
        sql("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + ROLE);
        var source = source(ROLE, PASSWORD);
        var configuration = new Configuration();
        configuration.addInterceptor(new TenantRlsInterceptor(List.of(new KnowledgeAuthorizedSqlRule())));
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/KnowledgeObservationAccessMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(KnowledgeObservationAccessMapper.class);
        reader = new TransactionTemplate(new DataSourceTransactionManager(source));
        reader.setReadOnly(true);
        readerJdbc = new JdbcTemplate(source);
    }

    @BeforeEach void currentSources() throws Exception {
        sql("UPDATE nw_knowledge_bases SET status='active' WHERE tenant_id=1 AND id=10");
        sql("UPDATE nw_knowledge_documents SET deleted_at=NULL WHERE tenant_id=1 AND id=100");
    }

    @AfterEach void clearIdentity() { TenantContext.clear(); }

    @Test void historicalReadyVersionIsReadableWithoutFollowingCurrentDocumentVersion() throws Exception {
        // 文档100当前版本为v3；原冻结v1仍ready且有当前读取权，不能漂移成v3。
        assertTrue(readable("1", target, List.of(versions.getFirst())));
        assertFalse(readable("1", target, List.of(new KnowledgeSourceVersion("10", "v3"))));
        assertFalse(readable("2", target, List.of(versions.getFirst())));
    }

    @Test void deletedSelectedSourceAndArchivedBaseDenyWithoutRejectingAnUnrelatedSelectedVersion() throws Exception {
        sql("UPDATE nw_knowledge_documents SET deleted_at=clock_timestamp() WHERE tenant_id=1 AND id=100");
        assertFalse(readable("1", target, List.of(versions.getFirst())));
        assertTrue(readable("1", target, List.of(versions.getLast())));
        sql("UPDATE nw_knowledge_bases SET status='archived' WHERE tenant_id=1 AND id=10");
        assertFalse(readable("1", target, List.of(versions.getLast())));
    }

    @Test void targetWithoutExactFrozenBindingDoesNotReadSourcesByMatchingOnlyTheirBase() throws Exception {
        var another = new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:unbound"), target.capability(),
                target.descriptorId(), target.descriptorRevision(), target.sourceVersions(), target.allowedOperators(), target.timeout());
        assertFalse(readable("1", another, List.of(versions.getFirst())));
    }

    @Test void platformReadKeepsPlatformThreadIdentityAndOnlyBindsTheExplicitTenantInTheManagedTransaction() {
        var foundation = mock(FoundationAccess.class);
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "901"));
        var platform = new PlatformObservationAccess(foundation, mock(PlatformObservationTargetMapper.class));
        KnowledgeObservationAccessMapper checked = (tenant, selected, all, sources) -> {
            assertNull(TenantContext.getTenantId());
            return mapper.readable(tenant, selected, all, sources);
        };
        var service = new KnowledgeObservationAccess(foundation, platform, checked);
        TenantContext.clear();
        assertDoesNotThrow(() -> reader.executeWithoutResult(status -> {
            service.requireRead(new TenantId("1"), new AgentId("11"),
                    new SessionRef(new TenantId("1"), "session-original"), target, Set.of(versions.getFirst()), true);
            assertEquals("", readerJdbc.queryForObject("SELECT COALESCE(current_setting('app.tenant_id',true),'')", String.class));
            assertNotEquals("true", readerJdbc.queryForObject("SELECT COALESCE(current_setting('app.platform_admin',true),'')", String.class));
        }));
        assertNull(TenantContext.getTenantId());
        var wrongTarget = assertThrows(ApiException.class, () -> reader.executeWithoutResult(status ->
                service.requireRead(new TenantId("2"), new AgentId("11"),
                        new SessionRef(new TenantId("2"), "session-original"), target,
                        Set.of(versions.getFirst()), true)));
        assertEquals("knowledge.observation.denied", wrongTarget.getCode());
        assertNull(TenantContext.getTenantId());
        assertThrows(ApiException.class, () -> reader.executeWithoutResult(status -> service.requireRead(new TenantId("1"),
                new AgentId("11"), new SessionRef(new TenantId("1"), "session-original"), target,
                Set.of(new KnowledgeSourceVersion("10", "v3")), true)));
        assertNull(PlatformObservationAccess.currentAuthorization());
    }

    @Test void platformReadDoesNotBypassCurrentSourceDeletion() throws Exception {
        var foundation = mock(FoundationAccess.class);
        when(foundation.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "901"));
        var service = new KnowledgeObservationAccess(foundation,
                new PlatformObservationAccess(foundation, mock(PlatformObservationTargetMapper.class)), mapper);
        sql("UPDATE nw_knowledge_documents SET deleted_at=clock_timestamp() WHERE tenant_id=1 AND id=100");
        assertThrows(ApiException.class, () -> reader.executeWithoutResult(status -> service.requireRead(new TenantId("1"),
                new AgentId("11"), new SessionRef(new TenantId("1"), "session-original"), target,
                Set.of(versions.getFirst()), true)));
        assertDoesNotThrow(() -> reader.executeWithoutResult(status -> service.requireRead(new TenantId("1"),
                new AgentId("11"), new SessionRef(new TenantId("1"), "session-original"), target,
                Set.of(versions.getLast()), true)));
        assertNull(TenantContext.getTenantId());
        assertNull(PlatformObservationAccess.currentAuthorization());
    }

    private static boolean readable(String boundTenant, FrozenKnowledgeTarget selected, List<KnowledgeSourceVersion> sources) {
        TenantContext.setTenantId(boundTenant);
        return reader.execute(status -> mapper.readable("1", selected, versions, sources));
    }

    private static DataSource source(String user, String password) {
        var source = new PGSimpleDataSource();
        source.setURL(DATABASE.getJdbcUrl());source.setUser(user);source.setPassword(password);
        return source;
    }
    private static void sql(String sql) throws Exception {
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
