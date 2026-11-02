package com.skillforge.core.chat.profile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.persistence.ChatMessageDO;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.chat.access.SessionAccessPolicy;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.application.TenantDirectoryService;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.model.invocation.LlmService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式宿主迁移、真实MP Wrapper与受限RLS验证定时扫描推进；LLM为确定性受控替身。 */
@Testcontainers(disabledWithoutDocker=false)
class UserProfileIdleScanPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static final Instant OLD=Instant.parse("2026-01-01T00:00:00.123456Z");
    private static SessionMapper sessions;
    private static ChatMessageMapper messages;
    private UserProfileSummarizer summarizer;
    private LlmService llm;
    private UserProfileService profiles;
    private long messageId;

    @BeforeAll static void database() throws Exception {
        CurrentHostDatabase.migrate(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        try(var connection=admin();var statement=connection.createStatement()) {
            CurrentHostFixtures.agent(connection,7001,7301);CurrentHostFixtures.endUser(connection,7001,7101);
            CurrentHostFixtures.agent(connection,7002,7401);CurrentHostFixtures.endUser(connection,7002,7201);
            statement.execute("""
                    CREATE ROLE profile_idle_reader LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS;
                    GRANT USAGE ON SCHEMA public TO profile_idle_reader;
                    GRANT SELECT,UPDATE ON public.nw_chat_sessions TO profile_idle_reader;
                    GRANT SELECT,INSERT,UPDATE ON public.nw_chat_messages TO profile_idle_reader;
                    """);
            try(var rows=statement.executeQuery("SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN ('public.nw_chat_sessions'::regclass,'public.nw_chat_messages'::regclass)")) {
                assertTrue(rows.next());assertTrue(rows.getBoolean(1));
            }
        }
        var source=new PGSimpleDataSource();source.setURL(POSTGRES.getJdbcUrl());source.setUser("profile_idle_reader");source.setPassword("test-only");
        try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertTrue(rows.next());assertFalse(rows.getBoolean(1));
        }
        var configuration=new MybatisConfiguration(new Environment("profile-idle-scan",new SpringManagedTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.addMapper(SessionMapper.class);configuration.addMapper(ChatMessageMapper.class);
        var sql=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        sessions=sql.getMapper(SessionMapper.class);messages=sql.getMapper(ChatMessageMapper.class);
    }

    @BeforeEach void service() throws Exception {
        TenantContext.clear();
        try(var connection=admin();var statement=connection.createStatement()) {
            statement.execute("DELETE FROM nw_chat_messages WHERE tenant_id IN (7001,7002)");
            statement.execute("DELETE FROM nw_chat_sessions WHERE tenant_id IN (7001,7002)");
        }
        messageId=9000;
        var directory=mock(TenantDirectoryService.class);when(directory.tenantIds()).thenReturn(List.of("7001","7002"));
        var settings=mock(OperationalSettingsService.class);
        when(settings.read("memory")).thenReturn(new OperationalSettingsService.Snapshot(1,Map.of("profile_summary_idle_minutes",60L)));
        when(settings.read("prompts")).thenReturn(new OperationalSettingsService.Snapshot(1,Map.of("chat_summary_prompt","fixture profile prompt")));
        profiles=mock(UserProfileService.class);llm=mock(LlmService.class);
        when(llm.generate(anyString(),any(),any())).thenReturn(new LlmResult("{\"profile\":\"有效画像\"}","fixture",null));
        var sessionService=new SessionService(sessions,settings,mock(SessionAccessPolicy.class));
        summarizer=new UserProfileSummarizer(directory,sessions,messages,profiles,sessionService,llm,new ObjectMapper(),settings,mock(StringRedisTemplate.class));
    }
    @AfterEach void close() { summarizer.close();TenantContext.clear(); }

    @Test void skippedHeadCannotStarveTheTwentyFirstSessionAndTouchedSkippedSessionReturnsAfterWrap() throws Exception {
        for(int index=0;index<21;index++)session(7001,index,OLD);
        // 第一个会话只有工作流消息，其他头部会话为空；均须保留原guard的跳过结果。
        message(7001,0,"user","工作流输出不进入闲聊画像","{\"execution_id\":\"workflow\"}");
        meaningful(7001,20);
        session(7002,0,OLD);meaningful(7002,0);
        summarizer.summarizeIdleSessions();
        verify(llm,times(1)).generate(anyString(),any(),any()); // 仅另一租户的有效会话。
        assertNull(summary(7001,20));assertEquals("有效画像",summary(7002,0));
        summarizer.summarizeIdleSessions();
        assertEquals("有效画像",summary(7001,20));verify(profiles).appendSummary("7001",7101L,"有效画像");
        for(int index=0;index<20;index++) { assertNull(summary(7001,index));assertEquals(OLD,updated(7001,index)); }
        summarizer.summarizeIdleSessions(); // 尾部空查询只回绕一次，仍不为20个空转候选调用模型。
        verify(llm,times(2)).generate(anyString(),any(),any());
        Instant touched=OLD.plusSeconds(3600).plusNanos(1000);
        try(var connection=admin();var statement=connection.prepareStatement("UPDATE nw_chat_sessions SET updated_at=? WHERE tenant_id=7001 AND session_id=?")) {
            statement.setObject(1,OffsetDateTime.ofInstant(touched,ZoneOffset.UTC));statement.setString(2,id(7001,0));statement.executeUpdate();
        }
        meaningful(7001,0);summarizer.summarizeIdleSessions();
        assertEquals("有效画像",summary(7001,0));verify(llm,times(3)).generate(anyString(),any(),any());
        assertNull(TenantContext.getTenantId());
    }

    @Test void tenantCursorsProgressIndependentlyAndFormalRlsRejectsForeignTenantQueries() throws Exception {
        for(long tenant:List.of(7001L,7002L)) {
            for(int index=0;index<21;index++)session(tenant,index,OLD);
            meaningful(tenant,20);
        }
        var hidden=TenantContext.callWithTenant("7001",()->sessions.selectList(new LambdaQueryWrapper<SessionDO>().eq(SessionDO::getTenantId,7002L)));
        assertTrue(hidden.isEmpty());
        summarizer.summarizeIdleSessions();verifyNoInteractions(llm);
        summarizer.summarizeIdleSessions();verify(llm,times(2)).generate(anyString(),any(),any());
        assertEquals("有效画像",summary(7001,20));assertEquals("有效画像",summary(7002,20));
        verify(profiles).appendSummary("7001",7101L,"有效画像");verify(profiles).appendSummary("7002",7201L,"有效画像");
        assertNull(TenantContext.getTenantId());
    }

    @Test void chatMessagePolicyIsEnabledByTheFormalMigrationAndProtectsUnscopedMapperReadsAndWrites() throws Exception {
        session(7001,0,OLD);meaningful(7001,0);session(7002,0,OLD);meaningful(7002,0);
        var own=TenantContext.callWithTenant("7001",()->messages.selectList(null));
        var foreign=TenantContext.callWithTenant("7002",()->messages.selectList(null));
        assertEquals(2,own.size());assertTrue(own.stream().allMatch(row->row.getTenantId()==7001L));
        assertEquals(2,foreign.size());assertTrue(foreign.stream().allMatch(row->row.getTenantId()==7002L));
        int hidden=TenantContext.callWithTenant("7001",()->messages.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getId,foreign.getFirst().getId()).set(ChatMessageDO::getContent,"not-authorized")));
        assertEquals(0,hidden);
        int ownUpdate=TenantContext.callWithTenant("7001",()->messages.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getId,own.getFirst().getId()).set(ChatMessageDO::getContent,"own-authorized-update")));
        assertEquals(1,ownUpdate);
        var refused=new ChatMessageDO();refused.setId(999_999L);refused.setTenantId(7002L);refused.setSessionId(id(7002,0));
        refused.setEndUserId(7201L);refused.setMessageId("forbidden-cross-tenant-message");refused.setRole("user");
        refused.setContent("must not be stored");refused.setMetadata(Map.of());refused.setCreatedAt(OLD);
        var denied=assertThrows(RuntimeException.class,()->TenantContext.runWithTenant("7001",()->messages.insert(refused)));
        Throwable cause=denied;while(!(cause instanceof java.sql.SQLException)&&cause.getCause()!=null)cause=cause.getCause();
        var sql=assertInstanceOf(java.sql.SQLException.class,cause);
        assertEquals("42501",sql.getSQLState());assertTrue(sql.getMessage().contains("row-level security"));
        var unchanged=TenantContext.callWithTenant("7002",()->messages.selectList(null));
        assertEquals(2,unchanged.size());assertTrue(unchanged.stream().noneMatch(row->"not-authorized".equals(row.getContent())));
        assertNull(TenantContext.getTenantId());verifyNoInteractions(llm,profiles);
    }

    private void session(long tenant,int index,Instant updated) throws Exception {
        try(var connection=admin();var statement=connection.prepareStatement("""
                INSERT INTO nw_chat_sessions(id,tenant_id,session_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref,updated_at)
                VALUES (?,?,?,?,?,'native','fixture-actor','fixture-responsibility',?)
                """)) {
            statement.setLong(1,tenant*100+index);statement.setLong(2,tenant);statement.setString(3,id(tenant,index));
            statement.setLong(4,tenant==7001?7101:7201);statement.setLong(5,tenant==7001?7301:7401);
            statement.setObject(6,OffsetDateTime.ofInstant(updated,ZoneOffset.UTC));statement.executeUpdate();
        }
    }
    private void meaningful(long tenant,int index) throws Exception {
        message(tenant,index,"user","我喜欢摄影和晨跑", "{}");message(tenant,index,"assistant","已记录你的有效偏好", "{}");
    }
    private void message(long tenant,int index,String role,String content,String metadata) throws Exception {
        long key=++messageId;
        try(var connection=admin();var statement=connection.prepareStatement("""
                INSERT INTO nw_chat_messages(id,tenant_id,session_id,end_user_id,message_id,role,content,metadata)
                VALUES (?,?,?,?,?,?,?,?::jsonb)
                """)) {
            statement.setLong(1,key);statement.setLong(2,tenant);statement.setString(3,id(tenant,index));
            statement.setLong(4,tenant==7001?7101:7201);statement.setString(5,"profile-idle-message-"+key);
            statement.setString(6,role);statement.setString(7,content);statement.setString(8,metadata);statement.executeUpdate();
        }
    }
    private static String id(long tenant,int index) { return "profile-idle-"+tenant+"-%02d".formatted(index); }
    private static String summary(long tenant,int index) throws Exception {
        try(var connection=admin();var statement=connection.prepareStatement("SELECT summary_text FROM nw_chat_sessions WHERE tenant_id=? AND session_id=?")) {
            statement.setLong(1,tenant);statement.setString(2,id(tenant,index));try(var rows=statement.executeQuery()){assertTrue(rows.next());return rows.getString(1);}
        }
    }
    private static Instant updated(long tenant,int index) throws Exception {
        try(var connection=admin();var statement=connection.prepareStatement("SELECT updated_at FROM nw_chat_sessions WHERE tenant_id=? AND session_id=?")) {
            statement.setLong(1,tenant);statement.setString(2,id(tenant,index));try(var rows=statement.executeQuery()){assertTrue(rows.next());return rows.getObject(1,OffsetDateTime.class).toInstant();}
        }
    }
    private static Connection admin() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
}
