package com.skillforge.platform.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.export.PlatformUsageContracts.FilterField;
import com.skillforge.core.billing.export.PlatformUsageMapper;
import com.skillforge.core.billing.export.PlatformUsageQuery;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** 借用已迁移的隔离数据库；随机所属样本持久保留，测试不清库、不删除容器。 */
@EnabledIfEnvironmentVariable(named="USAGE_OPTIONS_JDBC_URL", matches=".+")
class UsageFilterOptionsDatabaseTest {
    private static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis()*1000);
    private static JdbcTemplate jdbc;
    private static PlatformUsageMapper mapper;
    @BeforeAll static void initialize() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("USAGE_OPTIONS_JDBC_URL"),
                System.getenv("USAGE_OPTIONS_DB_USER"),System.getenv("USAGE_OPTIONS_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(new TenantRlsInterceptor());
        config.setEnvironment(new org.apache.ibatis.mapping.Environment("usage-options-retained",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),source));
        try(var input=new ClassPathResource("mapper/PlatformUsageMapper.xml").getInputStream()) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,config,"mapper/PlatformUsageMapper.xml",config.getSqlFragments()).parse();
        }
        mapper=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(PlatformUsageMapper.class);
    }
    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void historySurvivesMissingDirectoryNamesAndCannotExposeUnapprovedTenant() {
        long own=tenant(),other=tenant(),known=IDS.incrementAndGet(),historical=IDS.incrementAndGet();
        jdbc.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(?,?,?,'候选验收智能体')",known,own,"usage-options-"+known);
        event(own,known,"visible-run");event(own,historical,"retired-run");event(other,IDS.incrementAndGet(),"foreign-run");
        String session="usage-session-"+own;
        long user=IDS.incrementAndGet();
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id,display_name) VALUES(?,?,'用量候选隔离用户')",user,own);
        jdbc.update("INSERT INTO nw_chat_sessions(id,tenant_id,session_id,agent_id,end_user_id,summary_text,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'不属于用量权限的对话内容','system','call_point:usage-options',?)",IDS.incrementAndGet(),own,session,known,user,"tenant:"+own);
        jdbc.update("UPDATE nw_usage_events SET session_id=? WHERE tenant_id=? AND agent_id=?",session,own,known);
        TenantContext.setTenantId(Long.toString(own));
        var all=mapper.filterOptions(Set.of(own),null,PlatformUsageQuery.empty(),FilterField.AGENT_ID,null,null);
        assertEquals(Set.of(Long.toString(known),Long.toString(historical)),all.stream().map(v->v.id()).collect(java.util.stream.Collectors.toSet()));
        var named=mapper.filterOptions(Set.of(own),null,PlatformUsageQuery.empty(),FilterField.AGENT_ID,"验收",null);
        assertEquals(1,named.size());assertEquals("候选验收智能体",named.getFirst().label());
        var retired=mapper.filterOptions(Set.of(own),null,PlatformUsageQuery.empty(),FilterField.AGENT_ID,null,Long.toString(historical));
        assertEquals(Long.toString(historical),retired.getFirst().label());
        assertTrue(mapper.filterOptions(Set.of(),null,PlatformUsageQuery.empty(),FilterField.AGENT_ID,null,null).isEmpty());
        assertTrue(mapper.filterOptions(Set.of(own),null,PlatformUsageQuery.empty(),FilterField.RUN_ID,"foreign",null).isEmpty());
        assertTrue(mapper.filterOptions(Set.of(own),null,PlatformUsageQuery.empty(),FilterField.RUN_ID,"%",null).isEmpty());
        var sessions=mapper.filterOptions(Set.of(own),null,PlatformUsageQuery.empty(),FilterField.SESSION_ID,null,session);
        assertEquals(session,sessions.getFirst().label());
        assertTrue(mapper.filterOptions(Set.of(own),null,PlatformUsageQuery.empty(),FilterField.SESSION_ID,"对话内容",null).isEmpty());
        for(var field:FilterField.values())
            assertNotNull(mapper.filterOptions(Set.of(own),null,PlatformUsageQuery.empty(),field,null,null));
        System.out.println("usage-options retained tenants="+own+","+other+"; knownAgent="+known+"; historicalAgent="+historical);
    }
    private static long tenant() {
        long id=IDS.incrementAndGet();
        jdbc.update("INSERT INTO nw_tenants(id,name,slug,plan_id) SELECT ?,'用量候选隔离验收',?,id FROM nw_billing_plans WHERE plan_code='free'",id,"usage-options-"+id);
        return id;
    }
    private static void event(long tenant,long agent,String run) {
        long id=IDS.incrementAndGet();
        jdbc.update("""
                INSERT INTO nw_usage_events(id,tenant_id,event_id,event_type,usage_event_kind,usage_source,amount_status,
                    origin_kind,actor_ref,responsibility_ref,agent_id,run_id,occurred_at,ingested_at,created_at,quantity)
                VALUES(?,?,?,'request','measured','UNKNOWN','not_applicable','system','call_point:usage-options',?,?,?,now(),now(),now(),1)
                """,id,tenant,"usage-options-"+id,"tenant:"+tenant,agent,run);
    }
}
