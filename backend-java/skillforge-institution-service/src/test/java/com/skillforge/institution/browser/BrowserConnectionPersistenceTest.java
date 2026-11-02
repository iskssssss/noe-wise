package com.skillforge.institution.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 PostgreSQL、生产迁移与 MyBatis SQL 验证连接等待的持久性、CAS 及强制 RLS。 */
@Testcontainers(disabledWithoutDocker = true)
class BrowserConnectionPersistenceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void stableCreationCasRaceTenantIsolationAndStoreReconstruction() throws Exception {
        var admin=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        var setup=new JdbcTemplate(admin);
        setup.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY)");
        setup.execute("CREATE TABLE nw_end_users(id bigint PRIMARY KEY)");
        setup.execute("INSERT INTO nw_tenants VALUES(7),(8)");
        setup.execute("INSERT INTO nw_end_users VALUES(71),(81)");
        var migration=new ResourceDatabasePopulator(new ClassPathResource("db/migration/V290__browser_connection_requests.sql"));
        migration.setSeparator(ScriptUtils.EOF_STATEMENT_SEPARATOR);
        migration.execute(admin);
        setup.execute("CREATE ROLE browser_connection_app LOGIN PASSWORD 'test-only-browser' NOSUPERUSER NOBYPASSRLS");
        setup.execute("GRANT USAGE ON SCHEMA public TO browser_connection_app");
        setup.execute("GRANT SELECT,INSERT,UPDATE ON nw_browser_connection_requests TO browser_connection_app");
        var restricted=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"browser_connection_app","test-only-browser");
        assertFalse(new JdbcTemplate(restricted).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        var fixture=fixture(restricted);
        String id=UUID.randomUUID().toString();
        var created=fixture.store().create(row(7,id,"run-7","invocation-7"));
        assertEquals(1,created.getRevision());assertEquals(7,created.getTenantId());assertEquals(71,created.getEndUserId());
        assertEquals("session-7",created.getSessionId());assertEquals("invocation-7",created.getInvocationId());
        assertEquals("NO_SELECTION",created.getReason());assertEquals("WAITING",created.getStatus());
        assertNotNull(created.getCreatedAt());assertNotNull(created.getUpdatedAt());
        assertEquals(id,fixture.store().pending(7).getFirst().getRequestId());

        // 两个操作读取同一旧版本后才放行，独立连接上的数据库 CAS 只能允许一个胜者。
        var selection=fixture.store().find(7,id);var cancellation=fixture.store().find(7,id);
        selection.setStatus("READY");selection.setDeviceId("selected-device");selection.setBrowserId("selected-browser");selection.setMakeDefault(true);
        cancellation.setStatus("CANCELLING");
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var choose=executor.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return fixture.store().save(selection);});
            var cancel=executor.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return fixture.store().save(cancellation);});
            start.countDown();
            boolean selected=choose.get(15,TimeUnit.SECONDS);boolean cancelled=cancel.get(15,TimeUnit.SECONDS);
            assertNotEquals(selected,cancelled,"相同旧版本的选择与取消恰好一个成功");
            var winner=fixture.store().find(7,id);
            assertEquals(2,winner.getRevision());assertEquals(selected?"READY":"CANCELLING",winner.getStatus());
            assertEquals(selected?"selected-device":null,winner.getDeviceId());
            assertEquals(selected?"selected-browser":null,winner.getBrowserId());
            assertEquals(selected,winner.isMakeDefault());
        }
        // 冻结一个明确设备结果，重试创建不得恢复成 WAITING 或清除已保存字段。
        var frozen=fixture.store().find(7,id);frozen.setStatus("READY");frozen.setDeviceId("frozen-device");
        frozen.setBrowserId("frozen-browser");frozen.setMakeDefault(true);frozen.setPairDispatched(true);
        assertTrue(fixture.store().save(frozen));
        var stable=fixture.store().find(7,id);
        var duplicate=fixture.store().create(row(7,id,"run-7","invocation-7"));
        assertEquals(stable,duplicate);assertEquals(created.getCreatedAt(),duplicate.getCreatedAt());
        assertEquals(3,duplicate.getRevision());assertTrue(duplicate.isPairDispatched());
        assertFalse(fixture.store().save(selection),"迟到旧版本选择不能覆盖已冻结设备");

        // 显式 tenant 条件与 RLS 分别验证：错误机构读取、更新及插入均不能穿透。
        assertNull(fixture.store().find(8,id));assertTrue(fixture.store().pending(8).isEmpty());
        var other=fixture.store().create(row(8,id,"run-8","invocation-8"));
        assertEquals(8,other.getTenantId());assertEquals("WAITING",other.getStatus());
        TenantContext.callWithTenant("8",()->fixture.transactions().execute(status->{
            assertNull(fixture.mapper().find(7,id));
            assertEquals(0,fixture.mapper().update(stable,stable.getRevision()));
            return null;
        }));
        assertThrows(RuntimeException.class,()->TenantContext.callWithTenant("8",()->fixture.transactions().execute(status->
                fixture.mapper().insert(row(7,UUID.randomUUID().toString(),"forbidden","forbidden")))));
        assertEquals(stable,fixture.store().find(7,id));

        // 新建全部存储客户端，不能依赖旧 Java 实例中的缓存恢复等待。
        var rebuilt=fixture(restricted);
        var restored=rebuilt.store().find(7,id);
        assertEquals(stable,restored);assertEquals("frozen-device",restored.getDeviceId());
        assertEquals("frozen-browser",restored.getBrowserId());assertEquals("READY",restored.getStatus());
        assertEquals(stable.getRevision(),restored.getRevision());assertEquals(created.getCreatedAt(),restored.getCreatedAt());
        assertEquals(restored,rebuilt.store().pending(7).getFirst());
        var laterId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        rebuilt.store().create(row(7,laterId,"run-later","invocation-later"));
        assertEquals(laterId,rebuilt.store().pendingAfter(7,id).getFirst().getRequestId(),
                "下一批必须越过长期等待的旧请求，不能一直读取同一批");
        assertTrue(rebuilt.store().pendingAfter(7,laterId).isEmpty());
        assertNull(TenantContext.getTenantId(),"事务及后台调用结束后不能泄漏机构上下文");
    }

    /** 使用生产 XML、下划线映射和生产 RLS 插件，事务连接由 Spring 管理。 */
    private static Fixture fixture(DataSource source) throws Exception {
        var configuration=new Configuration();configuration.setMapUnderscoreToCamelCase(true);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);
        factory.setPlugins(new TenantRlsInterceptor());
        factory.setMapperLocations(new ClassPathResource("mapper/BrowserConnectionMapper.xml"));
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BrowserConnectionMapper.class);
        var manager=new DataSourceTransactionManager(source);
        return new Fixture(new BrowserConnectionStore(mapper,manager),mapper,new TransactionTemplate(manager));
    }

    /** 构造合法的新连接请求；创建时间与初始 revision 由真实 SQL 赋值。 */
    private static BrowserConnectionRequest row(long tenant,String id,String run,String invocation) {
        var row=new BrowserConnectionRequest();row.setTenantId(tenant);row.setEndUserId(tenant==7?71:81);
        row.setRequestId(id);row.setSessionId("session-"+tenant);row.setRunId(run);row.setInvocationId(invocation);
        row.setReason("NO_SELECTION");row.setStatus("WAITING");return row;
    }

    /** 数据库验收持有的真实存储、Mapper 与相同数据源事务入口。 */
    private record Fixture(BrowserConnectionStore store,BrowserConnectionMapper mapper,TransactionTemplate transactions){}
}
