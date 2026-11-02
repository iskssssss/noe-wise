package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.AgentExecutionContextViews;
import java.sql.Connection;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 完整生产schema、受限角色和真实自动提交Mapper；压缩查询不读取完整Run或无关事件正文。 */
class AgentRuntimeCompactionMapperIntegrationTest extends AbstractIntegrationTest {
    @Autowired DataSource application;

    @Test void autoCommitReadsOnlyExactOwnerCompactionFactsAndStillRejectsMalformedOldMatches() throws Exception {
        DataSource reader=application;
        var admin=new JdbcTemplate(application);
        if(Boolean.TRUE.equals(admin.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class))) {
            // 普通Testcontainers由本类拥有数据库；中央外部lane已直接提供受限app角色。
            admin.execute("CREATE ROLE compaction_reader_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
            admin.execute("GRANT USAGE ON SCHEMA public TO compaction_reader_test");
            admin.execute("GRANT SELECT,INSERT ON public.agent_runtime_session_owners,public.agent_runtime_execution_sessions,public.agent_runtime_execution_materials,public.agent_runtime_execution_runs,public.agent_runtime_execution_events TO compaction_reader_test");
            try(var connection=application.getConnection()) {
                reader=new DriverManagerDataSource(connection.getMetaData().getURL(),"compaction_reader_test","test-only-password");
            }
        }
        assertFalse(Boolean.TRUE.equals(new JdbcTemplate(reader).queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)));
        var configuration=new org.apache.ibatis.session.Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(reader); factory.setConfiguration(configuration);
        factory.setPlugins(new TenantRlsInterceptor());
        factory.setMapperLocations(new ClassPathResource("mapper/AgentRuntimeCompactionMapper.xml")); factory.afterPropertiesSet();
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AgentRuntimeCompactionMapper.class);
        var tenant=new TenantId(Long.toString(com.skillforge.core.shared.config.SnowflakeIds.nextId()));
        var owner=SessionOwner.user(new SessionRef(tenant,"compaction-"+UUID.randomUUID()),new AgentId("7"),new EndUserId(9));
        try(var f=new AgentRuntimeExecutionSourcesSummaryTest.Fixture(mapper,owner)) {
            String scope=f.scope(); var until=Instant.parse("2026-10-27T01:05:00Z");
            long sourceRows;
            try(var connection=reader.getConnection()) {
                connection.setAutoCommit(false); bind(connection,tenant.value());
                seedRun(connection,owner,"current"); seedRun(connection,owner,"previous");
                var other=SessionOwner.user(new SessionRef(tenant,owner.sessionRef().sessionId()+"-other"),new AgentId("8"),new EndUserId(9));
                seedRun(connection,other,"other-owner");
                for(int index=1;index<=250;index++) event(connection,owner,"current",index,"execution.unrelated","unrelated.v1","{}");
                for(int index=251;index<=351;index++) event(connection,owner,index%2==0?"current":"previous",index,
                        "agent.context-compaction-attempt","skillforge.runtime.execution.context-compaction-attempt.v1",
                        payload(scope,index==351?"2":"1",until.toString()));
                event(connection,owner,"current",352,"agent.context-compaction-attempt","skillforge.runtime.execution.context-compaction-attempt.v1",payload("other-scope","ignored","ignored"));
                event(connection,other,"other-owner",353,"agent.context-compaction-attempt","wrong-schema","{}");
                try(var statement=connection.prepareStatement("SELECT count(*) FROM public.agent_runtime_execution_events WHERE tenant_id=? AND session_id=?")) {
                    statement.setString(1,tenant.value());statement.setString(2,owner.sessionRef().sessionId());
                    try(var rows=statement.executeQuery()) {assertTrue(rows.next());sourceRows=rows.getLong(1);}
                }
                connection.commit();
            }
            TenantContext.setTenantId(tenant.value());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"必须验证生产无外层Spring事务的自动提交路径");
            var first=mapper.forOwner(owner,"agent.context-compaction-attempt",0,100);
            var second=mapper.forOwner(owner,"agent.context-compaction-attempt",first.getLast().sequence(),100);
            assertEquals(352,sourceRows); assertEquals(100,first.size()); assertEquals(2,second.size());
            assertEquals(251,first.getFirst().sequence()); assertEquals(352,second.getLast().sequence());
            assertEquals("other-scope",new com.fasterxml.jackson.databind.ObjectMapper().readTree(second.getLast().canonicalJson()).path("scope").asText(),"不能在SQL中过滤scope而跳过原校验边界");
            assertEquals(java.util.Optional.of(new AgentExecutionContextViews.Cooldown(2,until)),f.sources.contextCompactionCooldown(f.run,f.configuration));
            verifyNoInteractions(f.store);
            assertTrue(mapper.forOwner(SessionOwner.user(owner.sessionRef(),new AgentId("7"),new EndUserId(99)),"agent.context-compaction-attempt",0,100).isEmpty());
            assertTrue(mapper.forOwner(SessionOwner.user(owner.sessionRef(),new AgentId("8"),new EndUserId(9)),"agent.context-compaction-attempt",0,100).isEmpty());
            TenantContext.clear(); assertTrue(mapper.forOwner(owner,"agent.context-compaction-attempt",0,100).isEmpty());
            TenantContext.setTenantId("999999"); assertTrue(mapper.forOwner(owner,"agent.context-compaction-attempt",0,100).isEmpty());
            TenantContext.setTenantId(tenant.value());
            var groupRef=new GroupBindingRef(tenant,"compaction-group");
            var groupOwner=new SessionOwner(new SessionRef(tenant,owner.sessionRef().sessionId()+"-group"),SessionSubject.group(groupRef),SessionTarget.group(groupRef));
            try(var connection=reader.getConnection()) {
                connection.setAutoCommit(false);bind(connection,tenant.value());seedRun(connection,groupOwner,"group-run");
                event(connection,groupOwner,"group-run",1,"agent.context-compaction-attempt","skillforge.runtime.execution.context-compaction-attempt.v1",payload(scope,"1",until.toString()));
                connection.commit();
            }
            assertEquals(1,mapper.forOwner(groupOwner,"agent.context-compaction-attempt",0,100).size(),"群owner不补造EndUser，沿原群目录精确读取");
            // 晚到的正确新事实不能掩盖同owner旧非法事实；SQL必须把两者都交给来源校验。
            try(var connection=reader.getConnection()) {
                connection.setAutoCommit(false); bind(connection,tenant.value());
                event(connection,owner,"previous",354,"agent.context-compaction-attempt","skillforge.runtime.execution.context-compaction-attempt.v1",payload(scope,"01",until.toString()));
                event(connection,owner,"current",355,"agent.context-compaction-attempt","skillforge.runtime.execution.context-compaction-attempt.v1",payload(scope,"0",until.toString()));
                connection.commit();
            }
            assertEquals("execution_context_compaction_event_invalid",assertThrows(IllegalStateException.class,
                    ()->f.sources.contextCompactionCooldown(f.run,f.configuration)).getMessage());
            verifyNoInteractions(f.store);
            var rebuilt=new SqlSessionTemplate(factory.getObject()).getMapper(AgentRuntimeCompactionMapper.class);
            assertEquals(4,rebuilt.forOwner(owner,"agent.context-compaction-attempt",350,100).size(),"重建Mapper仍沿持久来源跨页读取");
            System.out.println("compaction JDBC source_rows_before_invalid="+sourceRows+" returned_before_invalid="+(first.size()+second.size())+" unrelated_returned=0 full_run_loads=0");
        } finally {TenantContext.clear();}
    }

    private static String payload(String scope,String failures,String until) {
        return "{\"scope\":\""+scope+"\",\"failures\":\""+failures+"\",\"until\":\""+until+"\"}";
    }
    private static void bind(Connection connection,String tenant) throws Exception {
        try(var statement=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {statement.setString(1,tenant);statement.execute();}
    }
    private static void seedRun(Connection connection,SessionOwner owner,String run) throws Exception {
        String tenant=owner.sessionRef().tenantId().value(), session=owner.sessionRef().sessionId(), target=owner.target().id(), kind=owner.target().kind().name();
        Long user=owner.subject() instanceof SessionSubject.User subject?subject.endUserId().value():null;
        try(var statement=connection.prepareStatement("INSERT INTO public.agent_runtime_session_owners(tenant_id,session_id,target_kind,target_id,agent_id,end_user_id) VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,target_kind,target_id,session_id) DO NOTHING")) {
            statement.setString(1,tenant);statement.setString(2,session);statement.setString(3,kind);statement.setString(4,target);
            statement.setString(5,kind.equals("AGENT")?target:null);statement.setObject(6,user,Types.BIGINT);statement.executeUpdate();
        }
        try(var statement=connection.prepareStatement("INSERT INTO public.agent_runtime_execution_sessions(tenant_id,session_id,target_kind,target_id,end_user_id) VALUES (?,?,?,?,?) ON CONFLICT (tenant_id,session_id) DO NOTHING")) {
            statement.setString(1,tenant);statement.setString(2,session);statement.setString(3,kind);statement.setString(4,target);statement.setObject(5,user,Types.BIGINT);statement.executeUpdate();
        }
        // 与生产sessionOwned查询一致，先核对唯一Session消息顺序根的完整生成主体，再插入Run。
        try(var statement=connection.prepareStatement("SELECT count(*) FROM public.agent_runtime_execution_sessions WHERE tenant_id=? AND session_id=? AND target_kind=? AND target_id=? AND subject_kind=? AND subject_id=?")) {
            statement.setString(1,tenant);statement.setString(2,session);statement.setString(3,kind);statement.setString(4,target);
            statement.setString(5,user==null?"GROUP":"USER");statement.setString(6,user==null?target:Long.toString(user));
            try(var rows=statement.executeQuery()) {assertTrue(rows.next());assertEquals(1,rows.getInt(1),"Run前必须存在准确owner的唯一Session根");}
        }
        try(var statement=connection.prepareStatement("INSERT INTO public.agent_runtime_execution_materials(tenant_id,reference,schema_id,canonical_json) VALUES (?,'compaction-fixture-material','fixture.v1','{}') ON CONFLICT DO NOTHING")) {
            statement.setString(1,tenant);statement.executeUpdate();
        }
        try(var statement=connection.prepareStatement("""
                INSERT INTO public.agent_runtime_execution_runs(tenant_id,run_id,session_id,target_kind,target_id,end_user_id,agent_id,input_id,
                trace_id,span_id,root_input_id,identity_ref,actor_verification_ref,configuration_ref,input_ref,initial_context_ref,admission_fingerprint,
                status,phase,revision,fence,context_ref,created_second,created_nano,updated_second,updated_nano,requester_kind,requester_end_user_id,requester_system_principal_id,origin_ref)
                VALUES (?,?,?,?,?,?,?,?,'trace','span',?,'identity','actor','configuration','compaction-fixture-material','compaction-fixture-material','fingerprint',
                'COMPLETED','FINISHED',1,0,'compaction-fixture-material',0,0,0,0,?,?,?,'fixture-origin')
                """)) {
            statement.setString(1,tenant);statement.setString(2,run);statement.setString(3,session);statement.setString(4,kind);statement.setString(5,target);
            statement.setObject(6,user,Types.BIGINT);statement.setString(7,kind.equals("AGENT")?target:"7");statement.setString(8,"input-"+run);statement.setString(9,"input-"+run);
            statement.setString(10,user==null?"SYSTEM":"END_USER");statement.setObject(11,user,Types.BIGINT);statement.setString(12,user==null?"fixture-worker":null);statement.executeUpdate();
        }
    }
    private static void event(Connection connection,SessionOwner owner,String run,long sequence,String type,String schema,String json) throws Exception {
        try(var statement=connection.prepareStatement("INSERT INTO public.agent_runtime_execution_events(tenant_id,session_id,run_id,event_sequence,revision,event_id,type,schema_id,canonical_json,occurred_second,occurred_nano) VALUES (?,?,?,?,1,?,?,?,?,0,0)")) {
            statement.setString(1,owner.sessionRef().tenantId().value());statement.setString(2,owner.sessionRef().sessionId());statement.setString(3,run);
            statement.setLong(4,sequence);statement.setString(5,"event-"+sequence);statement.setString(6,type);statement.setString(7,schema);statement.setString(8,json);statement.executeUpdate();
        }
    }
}
