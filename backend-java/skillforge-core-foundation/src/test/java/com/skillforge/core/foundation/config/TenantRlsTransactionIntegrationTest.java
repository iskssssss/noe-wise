package com.skillforge.core.foundation.config;

import com.skillforge.core.foundation.config.TenantRlsInterceptor;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立基础设施探针验证实际Spring事务、连接池与FORCE RLS；不替代产品IAM或业务迁移验收。 */
@Testcontainers(disabledWithoutDocker = false)
class TenantRlsTransactionIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("tenant_rls_transaction").withUsername("admin").withPassword("admin_password");

    @BeforeAll static void schema() throws Exception {
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var statement=connection.createStatement()) {
            statement.execute("CREATE ROLE tenant_rls_probe LOGIN PASSWORD 'probe_password' NOSUPERUSER NOBYPASSRLS");
            statement.execute("CREATE TABLE tenant_rls_scope_probe(tenant_id text NOT NULL,row_key text NOT NULL,PRIMARY KEY(tenant_id,row_key))");
            statement.execute("INSERT INTO tenant_rls_scope_probe VALUES('tenant-a','a'),('tenant-b','b')");
            statement.execute("ALTER TABLE tenant_rls_scope_probe ENABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE tenant_rls_scope_probe FORCE ROW LEVEL SECURITY");
            statement.execute("CREATE POLICY tenant_isolation ON tenant_rls_scope_probe USING(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id=current_setting('app.tenant_id',true))");
            statement.execute("GRANT USAGE ON SCHEMA public TO tenant_rls_probe");
            statement.execute("GRANT SELECT,INSERT,UPDATE ON tenant_rls_scope_probe TO tenant_rls_probe");
        }
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void springTransactionBindsOnceAndRejectsAnotherTenantBeforeItsQuery() throws Exception {
        try(var fixture=new Fixture(1)) {
            TenantContext.setTenantId("tenant-a");
            fixture.required.executeWithoutResult(status -> {
                assertEquals(1,fixture.mapper.visible()); assertEquals("tenant-a",fixture.mapper.bound());
                assertEquals(fixture.mapper.backendPid(1),fixture.mapper.backendPid(2));
                TenantContext.setTenantId("tenant-b");
                assertScopeMismatch(assertThrows(RuntimeException.class,fixture.mapper::visible));
                TenantContext.setTenantId("tenant-a"); assertEquals(1,fixture.mapper.visible());
                assertEquals(1,fixture.source.binds.get());
            });
            fixture.assertPooledIdentityEmpty();
        }
    }

    @Test void anonymousLookupCanEstablishIdentityAndClearItWithoutCachedTenantRows() throws Exception {
        try(var fixture=new Fixture(1)) {
            fixture.required.executeWithoutResult(status -> {
                TenantContext.clear(); assertEquals(0,fixture.mapper.visible());
                TenantContext.setTenantId("tenant-a"); assertEquals(1,fixture.mapper.visible());
                TenantContext.clear(); assertEquals(0,fixture.mapper.visible());
                TenantContext.setTenantId("tenant-b");
                assertScopeMismatch(assertThrows(RuntimeException.class,fixture.mapper::visible));
                TenantContext.setTenantId("tenant-a"); assertEquals(1,fixture.mapper.visible());
                assertEquals(4,fixture.source.binds.get());
            });
            fixture.assertPooledIdentityEmpty();
        }
    }

    @Test void requiresNewSuspendsAndRestoresOuterBindingWithoutRebindingOrLeakingIdentity() throws Exception {
        try(var fixture=new Fixture(2)) {
            var independent=new TransactionTemplate(fixture.transactions);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            TenantContext.setTenantId("tenant-a");
            fixture.required.executeWithoutResult(status -> {
                int outer=fixture.mapper.backendPid(1); assertEquals("tenant-a",fixture.mapper.bound());
                try {
                    TenantContext.setTenantId("tenant-b");
                    independent.executeWithoutResult(inner -> {
                        assertNotEquals(outer,fixture.mapper.backendPid(2)); assertEquals("tenant-b",fixture.mapper.bound());
                        assertEquals(1,fixture.mapper.visible());
                    });
                } finally { TenantContext.setTenantId("tenant-a"); }
                assertEquals(outer,fixture.mapper.backendPid(3)); assertEquals("tenant-a",fixture.mapper.bound());
                assertEquals(2,fixture.source.binds.get());
            });
            fixture.assertPooledIdentityEmpty();
        }
    }

    @Test void commitAndRollbackReleaseProofBeforeTheSamePoolConnectionIsBorrowedAgain() throws Exception {
        try(var fixture=new Fixture(1)) {
            TenantContext.setTenantId("tenant-a");
            int first=fixture.required.execute(status -> fixture.mapper.backendPid(1));
            var failure=assertThrows(RuntimeException.class,() -> fixture.required.executeWithoutResult(status -> {
                assertEquals(first,fixture.mapper.backendPid(2));
                fixture.mapper.insert("tenant-a","rolled-back"); fixture.mapper.divideByZero();
            }));
            assertSqlState(failure,"22012"); fixture.assertPooledIdentityEmpty();
            TenantContext.setTenantId("tenant-b");
            fixture.required.executeWithoutResult(status -> {
                assertEquals(first,fixture.mapper.backendPid(3)); assertEquals("tenant-b",fixture.mapper.bound());
                assertEquals(1,fixture.mapper.visible());
            });
            TenantContext.setTenantId("tenant-a");
            fixture.required.executeWithoutResult(status -> assertEquals(1,fixture.mapper.visible()));
            assertEquals(4,fixture.source.binds.get()); fixture.assertPooledIdentityEmpty();
        }
    }

    @Test void unchangedAutoCommitBoundaryBindsAndClearsEveryStatementAndRlsRejectsForeignWrites() throws Exception {
        try(var fixture=new Fixture(1)) {
            TenantContext.setTenantId("tenant-a"); assertEquals(1,fixture.mapper.visible());
            TenantContext.setTenantId("tenant-b"); assertEquals(1,fixture.mapper.visible());
            TenantContext.clear(); assertEquals(0,fixture.mapper.visible());
            assertEquals(6,fixture.source.binds.get()); fixture.assertPooledIdentityEmpty();
            TenantContext.setTenantId("tenant-a");
            var failure=assertThrows(RuntimeException.class,() -> fixture.required.executeWithoutResult(status ->
                    fixture.mapper.insert("tenant-b","foreign-write")));
            assertSqlState(failure,"42501"); fixture.assertPooledIdentityEmpty();
        }
    }

    @Test void explicitSettingCannotLeaveAFalseBindingProofForTheNextMapper() throws Exception {
        try(var fixture=new Fixture(1)) {
            TenantContext.setTenantId("tenant-a");
            fixture.required.executeWithoutResult(status -> {
                assertEquals("tenant-a",fixture.mapper.bound());
                assertEquals("tenant-b",fixture.mapper.explicitBind("tenant-b"));
                assertEquals("tenant-a",fixture.mapper.bound()); assertEquals(1,fixture.mapper.visible());
                assertEquals(3,fixture.source.binds.get());
            });
            fixture.assertPooledIdentityEmpty();
        }
    }

    @Test void savepointRollbackRebindsTheCurrentAnonymousScopeInsteadOfReusingRevertedGuc() throws Exception {
        try(var fixture=new Fixture(1)) {
            var nested=new TransactionTemplate(fixture.transactions);
            nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            TenantContext.setTenantId("tenant-a");
            fixture.required.executeWithoutResult(status -> {
                assertEquals("tenant-a",fixture.mapper.bound());
                nested.executeWithoutResult(inner -> {
                    TenantContext.clear(); assertEquals("",fixture.mapper.bound()); inner.setRollbackOnly();
                });
                // Savepoint回滚恢复了数据库tenant-a；当前入口仍匿名，不能复用已回滚的空GUC证明。
                assertEquals(0,fixture.mapper.visible());
                TenantContext.setTenantId("tenant-a"); assertEquals(1,fixture.mapper.visible());
                assertEquals(4,fixture.source.binds.get());
            });
            fixture.assertPooledIdentityEmpty();
        }
    }

    @Test void sharedSpringConnectionDoesNotReuseAnotherExecutorsPreviousTenantCache() throws Exception {
        try(var fixture=new Fixture(1)) {
            var other=fixture.newMapper(); TenantContext.setTenantId("tenant-a");
            fixture.required.executeWithoutResult(status -> {
                assertEquals(1,fixture.mapper.visible()); assertEquals(1,other.visible());
                assertEquals(fixture.mapper.backendPid(1),other.backendPid(2));
                TenantContext.clear(); assertEquals(0,other.visible());
                assertEquals(0,fixture.mapper.visible(),"连接已清空也不能从另一个Executor返回旧租户缓存");
                assertEquals(2,fixture.source.binds.get());
            });
            fixture.assertPooledIdentityEmpty();
        }
    }

    @Test void commentedSetAndResetInvalidateProofWhileOrdinaryUpdateSetKeepsIt() throws Exception {
        try(var fixture=new Fixture(1)) {
            TenantContext.setTenantId("tenant-a");
            fixture.required.executeWithoutResult(status -> {
                assertEquals("tenant-a",fixture.mapper.bound());
                fixture.mapper.commentedSet();
                assertEquals("tenant-b",new JdbcTemplate(fixture.source).queryForObject("SELECT current_setting('app.tenant_id',true)",String.class));
                assertEquals("tenant-a",fixture.mapper.bound(),"带注释SET后下一条Mapper必须重绑原租户");
                fixture.mapper.commentedReset();
                assertEquals("",new JdbcTemplate(fixture.source).queryForObject("SELECT current_setting('app.tenant_id',true)",String.class));
                assertEquals("tenant-a",fixture.mapper.bound(),"带注释RESET后下一条Mapper必须重绑原租户");
                assertEquals(5,fixture.source.binds.get());
                assertEquals(1,fixture.mapper.ordinaryUpdate()); assertEquals("tenant-a",fixture.mapper.bound());
                assertEquals(5,fixture.source.binds.get(),"普通UPDATE SET不能使绑定证明失效");
            });
            fixture.assertPooledIdentityEmpty();
        }
    }

    private static void assertSqlState(Throwable failure,String expected) {
        Throwable cause=failure; while(cause!=null && !(cause instanceof SQLException))cause=cause.getCause();
        assertEquals(expected,assertInstanceOf(SQLException.class,cause).getSQLState());
    }

    private static void assertScopeMismatch(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof IllegalStateException && "tenant_transaction_scope_mismatch".equals(cause.getMessage()))return;
        fail("必须由事务租户不一致守卫拒绝",failure);
    }

    public interface ProbeMapper {
        @Select("SELECT count(*) FROM tenant_rls_scope_probe") long visible();
        @Select("SELECT current_setting('app.tenant_id',true)") String bound();
        @Select("SELECT pg_backend_pid()+0*#{marker}") int backendPid(@Param("marker") int marker);
        @Insert("INSERT INTO tenant_rls_scope_probe(tenant_id,row_key) VALUES(#{tenant},#{key})")
        int insert(@Param("tenant") String tenant,@Param("key") String key);
        @Select("SELECT 1/0") int divideByZero();
        @Select("SELECT set_config('app.tenant_id',#{tenant},true)")
        @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE) String explicitBind(@Param("tenant") String tenant);
        @Update("/* outer /* nested */ binder */ SET LOCAL app.tenant_id='tenant-b'") void commentedSet();
        @Update("-- binder\nRESET app.tenant_id") void commentedReset();
        @Update("/* ordinary update */ UPDATE tenant_rls_scope_probe SET row_key=row_key WHERE row_key='a'") int ordinaryUpdate();
    }

    private static final class Fixture implements AutoCloseable {
        final CountingSource source;
        final DataSourceTransactionManager transactions;
        final TransactionTemplate required;
        final ProbeMapper mapper;
        Fixture(int size) throws Exception {
            var config=new HikariConfig(); config.setJdbcUrl(POSTGRES.getJdbcUrl()); config.setUsername("tenant_rls_probe");
            config.setPassword("probe_password"); config.setMaximumPoolSize(size); config.setMinimumIdle(0);
            source=new CountingSource(new HikariDataSource(config)); transactions=new DataSourceTransactionManager(source);
            required=new TransactionTemplate(transactions);
            mapper=newMapper();
            try(var connection=source.getConnection();var statement=connection.createStatement()) {
                try(var rows=statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) { assertTrue(rows.next());assertTrue(rows.getBoolean(1)); }
                try(var rows=statement.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='tenant_rls_scope_probe'::regclass")) { assertTrue(rows.next());assertTrue(rows.getBoolean(1)); }
            }
        }
        ProbeMapper newMapper() throws Exception {
            var configuration=new org.apache.ibatis.session.Configuration(); configuration.addInterceptor(new TenantRlsInterceptor());
            configuration.addMapper(ProbeMapper.class);
            var factory=new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration); factory.afterPropertiesSet();
            return new SqlSessionTemplate(factory.getObject()).getMapper(ProbeMapper.class);
        }
        void assertPooledIdentityEmpty() throws Exception {
            try(var connection=source.getConnection();var statement=connection.createStatement();
                var rows=statement.executeQuery("SELECT COALESCE(current_setting('app.tenant_id',true),'')")) {
                assertTrue(rows.next()); assertEquals("",rows.getString(1));
            }
        }
        @Override public void close() { source.pool.close(); }
    }

    /** 只计真实绑定execute；不替代驱动、连接池、事务或SQL结果。 */
    private static final class CountingSource extends AbstractDataSource {
        final HikariDataSource pool; final AtomicInteger binds=new AtomicInteger();
        CountingSource(HikariDataSource pool) { this.pool=pool; }
        @Override public Connection getConnection() throws SQLException { return observed(pool.getConnection()); }
        @Override public Connection getConnection(String username,String password) throws SQLException { return observed(pool.getConnection(username,password)); }
        private Connection observed(Connection target) {
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args) -> {
                Object result=invoke(method,target,args);
                if(method.getName().equals("prepareStatement") && args[0].equals("SELECT set_config('app.tenant_id', ?, ?)")) {
                    var statement=(PreparedStatement)result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(ignored,operation,parameters) -> {
                        if(operation.getName().startsWith("execute"))binds.incrementAndGet();
                        return invoke(operation,statement,parameters);
                    });
                }
                return result;
            });
        }
        private static Object invoke(Method method,Object target,Object[] arguments) throws Throwable {
            try { return method.invoke(target,arguments); } catch(InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
