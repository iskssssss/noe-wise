package com.skillforge.institution.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.application.EndUserService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 PostgreSQL 验证映射并发唯一性、事务回滚、跨租户外键与 RLS。 */
@Testcontainers(disabledWithoutDocker = true)
class ChatClientProfilePersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ChatClientProfileService service;
    private ChatClientProfileMapper mapper;
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach void setup() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS nw_chat_client_profiles,end_users,channels,tenants CASCADE");
        jdbc.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY)");
        jdbc.execute("CREATE TABLE nw_channels(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,status text)");
        jdbc.execute("CREATE TABLE nw_end_users(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,display_name text,status text)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V160__chat_client_profiles.sql")).execute(dataSource);
        jdbc.update("INSERT INTO nw_tenants VALUES(7),(17)");
        jdbc.update("INSERT INTO nw_channels VALUES(8,7,'active'),(18,17,'active'),(9,7,'active')");
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/ChatClientProfileMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ChatClientProfileMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var users = mock(EndUserService.class);
        when(users.create(anyString(),anyString())).thenAnswer(invocation -> {
            long id=ids.incrementAndGet();
            String tenant=invocation.getArgument(0), name=invocation.getArgument(1);
            jdbc.update("INSERT INTO nw_end_users VALUES(?,?,?,'active')",id,Long.parseLong(tenant),name);
            return user(id,name);
        });
        when(users.require(anyString(),anyLong())).thenAnswer(invocation -> jdbc.queryForObject(
                "SELECT id,display_name FROM nw_end_users WHERE tenant_id=? AND id=?",
                (rs,row)->user(rs.getLong(1),rs.getString(2)),Long.parseLong(invocation.getArgument(0)),invocation.getArgument(1)));
        service = new ChatClientProfileService(mapper,users,new ChatEndUserTokens(new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.skillforge.core.shared.config.SkillForgeProperties(),mapper,users));
    }
    private EndUserDO user(long id,String name) {
        var user=new EndUserDO(); user.setId(id); user.setDisplayName(name); user.setStatus("active"); return user;
    }
    private ChatClientProfileService.Profile register(String ref,String channel) {
        TenantContext.setTenantId("7"); ChannelContext.setChannelId(channel);
        try { return tx.execute(status -> service.register(ref,"昵称",null)); }
        finally { TenantContext.clear(); ChannelContext.clear(); }
    }
    @Test void concurrentRegistrationCreatesOneUserAndChannelIsolation() throws Exception {
        String ref=UUID.randomUUID().toString(); var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(6)) {
            var futures=java.util.stream.IntStream.range(0,6).mapToObj(index->executor.submit(()-> {
                assertTrue(start.await(10,TimeUnit.SECONDS));
                try { return register(ref,"8").endUserId(); }
                catch (com.skillforge.core.shared.exception.ApiException denied) {
                    assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED,denied.getStatus());
                    return null;
                }
            })).toList();
            start.countDown();
            var results=new java.util.HashSet<String>();
            int registered=0;
            for(var future:futures) { String id=future.get(20,TimeUnit.SECONDS); if(id!=null){results.add(id);registered++;} }
            assertEquals(1,registered);
            assertEquals(1,results.size());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM nw_end_users",Integer.class));
            assertNotEquals(results.iterator().next(),register(ref,"9").endUserId());
        }
        jdbc.update("DELETE FROM nw_channels WHERE id=8");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM nw_chat_client_profiles",Integer.class));
        jdbc.update("DELETE FROM nw_end_users");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM nw_chat_client_profiles",Integer.class));
    }
    @Test void failedMappingRollsBackNewUser() {
        jdbc.execute("ALTER TABLE nw_chat_client_profiles ADD CONSTRAINT reject_fixture CHECK (false)");
        assertThrows(RuntimeException.class,()->register(UUID.randomUUID().toString(),"8"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM nw_end_users",Integer.class));
    }
    @Test void foreignTenantReferencesAreRejectedAndRlsHidesRows() {
        register(UUID.randomUUID().toString(),"8");
        assertThrows(RuntimeException.class,()->jdbc.update(
                "INSERT INTO nw_chat_client_profiles VALUES(17,18,?::uuid,101,now())",UUID.randomUUID().toString()));
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='profile_reader') THEN CREATE ROLE profile_reader; END IF; END $$");
        jdbc.execute("GRANT SELECT ON nw_chat_client_profiles TO profile_reader");
        tx.executeWithoutResult(status->{
            jdbc.execute("SET LOCAL ROLE profile_reader");
            jdbc.queryForObject("SELECT set_config('app.tenant_id','17',true)",String.class);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM nw_chat_client_profiles",Integer.class));
            jdbc.queryForObject("SELECT set_config('app.tenant_id','7',true)",String.class);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM nw_chat_client_profiles",Integer.class));
        });
    }
}
