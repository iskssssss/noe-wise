package com.skillforge.institution.workspace;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立PG容器执行真实V268与Mapper，聚焦RLS、关闭入口和删除后的持久清理意图。 */
@Testcontainers(disabledWithoutDocker=false)
class SessionWorkspaceMapperPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("session_workspace_test");
    private static SqlSessionFactory factory;

    /** 最小宿主夹具之外使用真实迁移，应用角色明确禁用superuser和bypassrls。 */
    @BeforeAll static void initialize()throws Exception {
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY); CREATE TABLE nw_chat_sessions(tenant_id bigint,session_id text,end_user_id bigint,metadata jsonb); INSERT INTO nw_tenants VALUES(1),(2); INSERT INTO nw_chat_sessions VALUES(1,'product',9,'{}'),(1,'closed',9,'{\"closed_at\":\"test\"}')");
            try(var migration=SessionWorkspaceMapperPostgresTest.class.getClassLoader().getResourceAsStream("db/migration/V268__session_workspaces.sql")) {
                assertNotNull(migration);sql.execute(new String(migration.readAllBytes(),StandardCharsets.UTF_8));
            }
            sql.execute("CREATE ROLE workspace_app LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS; GRANT USAGE ON SCHEMA public TO workspace_app; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO workspace_app");
        }
        var data=new PGSimpleDataSource();data.setURL(PG.getJdbcUrl());data.setUser("workspace_app");data.setPassword("test-only");
        var config=new Configuration(new Environment("workspace",new JdbcTransactionFactory(),data));
        config.setMapUnderscoreToCamelCase(true);config.addInterceptor(new TenantRlsInterceptor());
        try(var resource=SessionWorkspaceMapperPostgresTest.class.getClassLoader().getResourceAsStream("mapper/SessionWorkspaceMapper.xml")) {
            assertNotNull(resource);new XMLMapperBuilder(resource,config,"mapper/SessionWorkspaceMapper.xml",config.getSqlFragments()).parse();
        }
        factory=new SqlSessionFactoryBuilder().build(config);
    }

    /** 测试每次退出恢复线程身份，不依赖连接池残留app.tenant_id。 */
    @AfterEach void clear(){TenantContext.clear();}

    @Test void closedProductsRlsAndDurableDeletionIntentRemainEnforced()throws Exception {
        TenantContext.setTenantId("1");
        var active=new SessionWorkspaceMapper.Row("a".repeat(64),"1","delegation:child","product",9,"active","d".repeat(64),false);
        try(var tx=factory.openSession()) {
            var mapper=tx.getMapper(SessionWorkspaceMapper.class);
            assertFalse(mapper.productActive("1","closed",9));
            assertEquals(0,mapper.insert(new SessionWorkspaceMapper.Row("b".repeat(64),"1","closed","closed",9,"active","d".repeat(64),false)));
            assertEquals(1,mapper.insert(active));assertEquals(0,mapper.insert(active));
            assertEquals("product",mapper.find("1","delegation:child").productSessionId());
            assertFalse(mapper.find("1","delegation:child").initialized());
            assertEquals(0,mapper.confirmInitialization("1",active.workspaceId(),"e".repeat(64)),"不匹配首次调用不能确认初始化");
            assertEquals(1,mapper.confirmInitialization("1",active.workspaceId(),active.initialOperationId()));tx.commit();
        }
        TenantContext.setTenantId("2");
        try(var tx=factory.openSession()) {
            assertNull(tx.getMapper(SessionWorkspaceMapper.class).find("1","delegation:child"),"伪造tenant参数不能绕过RLS");
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,()->tx.getMapper(SessionWorkspaceMapper.class)
                    .insert(new SessionWorkspaceMapper.Row("c".repeat(64),"1","late","product",9,"active","d".repeat(64),false)));tx.rollback();
        }
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var sql=connection.createStatement()) {
            sql.execute("DELETE FROM nw_chat_sessions WHERE tenant_id=1 AND session_id='product'");
        }
        TenantContext.setTenantId("1");
        try(var tx=factory.openSession()) {
            var mapper=tx.getMapper(SessionWorkspaceMapper.class);assertEquals(1,mapper.markDeletedProducts("1"));tx.commit();
        }
        try(var rebuilt=factory.openSession()) {
            var mapper=rebuilt.getMapper(SessionWorkspaceMapper.class);assertEquals(1,mapper.pendingCleanup("1").size());
            assertTrue(mapper.find("1","delegation:child").initialized(),"服务重建保留单向初始化事实");
            assertEquals("pending",mapper.find("1","delegation:child").cleanupState());
            assertEquals(1,mapper.completeCleanup("1",active.workspaceId()));rebuilt.commit();
        }
        try(var tx=factory.openSession()) {
            var mapper=tx.getMapper(SessionWorkspaceMapper.class);assertEquals("complete",mapper.find("1","delegation:child").cleanupState());
            assertEquals(0,mapper.insert(active),"产品不存在时迟到登记不得复活工作区");
        }
    }
}
