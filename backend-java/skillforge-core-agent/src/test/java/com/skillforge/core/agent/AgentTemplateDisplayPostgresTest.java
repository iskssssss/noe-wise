package com.skillforge.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.mapper.AgentTemplateMapper;
import com.skillforge.core.agent.model.AgentTemplateDisplay;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 私有最小模板夹具与正式Mapper；不代替完整迁移或授权链集成。 */
@Testcontainers(disabledWithoutDocker=false)
class AgentTemplateDisplayPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static SqlSessionFactory factory;
    private static final Reads READS=new Reads();
    @BeforeAll static void database() throws Exception {
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var sql=connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE nw_agent_templates(id bigint PRIMARY KEY,template_key text,name text,version text,category text,
                    tags jsonb,description text,icon_url text,author text,author_type text,definition jsonb,price_type text,
                    price_cents integer,install_count integer,status text,distribution_policy text,owner_kind text,owner_tenant_id bigint,
                    draft_revision bigint,published_at timestamptz,has_unpublished_changes boolean,created_at timestamptz,updated_at timestamptz)
                    """);
            sql.execute("""
                    INSERT INTO nw_agent_templates SELECT n,'template-'||n,'名称'||n,'1.'||n||'.0','test','[]','description',NULL,
                    'platform','platform',jsonb_build_object('system_prompt',repeat('x',65536),'role_description','role'),
                    'free',0,0,'published','AUTHORIZED_DEPLOY','platform',NULL,1,now(),false,now(),now() FROM generate_series(1,3) n
                    """);
            // 窄投影必须不依赖完整定义可解码；不改变完整读取的错误契约。
            sql.execute("INSERT INTO nw_agent_templates(id,name,version,definition) VALUES(4,'未解码展示','2.0',jsonb_build_object('system_prompt','{}'::jsonb))");
            sql.execute("CREATE ROLE grant_display_reader LOGIN PASSWORD 'isolated-only' NOSUPERUSER NOBYPASSRLS");
            sql.execute("GRANT USAGE ON SCHEMA public TO grant_display_reader; GRANT SELECT ON nw_agent_templates TO grant_display_reader");
        }
        var source=new PGSimpleDataSource();source.setURL(PG.getJdbcUrl());source.setUser("grant_display_reader");source.setPassword("isolated-only");
        var configuration=new MybatisConfiguration(new Environment("grant-display",new JdbcTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(READS);
        try(var xml=AgentTemplateDisplayPostgresTest.class.getClassLoader().getResourceAsStream("mapper/AgentTemplateMapper.xml")) {
            assertNotNull(xml);new XMLMapperBuilder(xml,configuration,"mapper/AgentTemplateMapper.xml",configuration.getSqlFragments()).parse();
        }
        factory=new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
    @Test void distinctDefinitionReadsBecomeOneThreeColumnQueryWithoutHydratingDefinition() throws Exception {
        try(var session=factory.openSession()) {
            var mapper=session.getMapper(AgentTemplateMapper.class);
            var service=new AgentTemplateService(null,null,null,new ObjectMapper(),null);ReflectionTestUtils.setField(service,"baseMapper",mapper);
            READS.rows.clear();
            assertTrue(service.displays(List.of()).isEmpty());assertTrue(READS.rows.isEmpty());
            var baseline=new java.util.HashMap<Long,AgentTemplateDisplay>();
            for(long id:List.of(1L,2L,3L)) {
                var full=mapper.selectById(id);assertEquals(65536,full.getDefinition().systemPrompt().length());
                baseline.put(id,new AgentTemplateDisplay(id,full.getName(),full.getVersion()));
            }
            assertEquals(3,READS.rows.size(),"three different definitions issue three actual full SELECTs");
            var oldReads=List.copyOf(READS.rows);READS.rows.clear();
            var summaries=service.displays(List.of(1L,2L,3L,1L,999L));
            assertEquals(baseline,summaries);assertEquals(1,READS.rows.size(),"one actual prepared summary SELECT");
            var summary=READS.rows.getFirst();
            long oldBytes=0;for(var read:oldReads)oldBytes+=rowBytes(session,read,false);
            long newBytes=rowBytes(session,summary,true);
            assertTrue(oldBytes>newBytes+196608);
            System.out.println("AGENT_GRANT_DISPLAY_READ full_selects=3 summary_selects=1 rows=3 baseline="+oldBytes
                    +" candidate="+newBytes+" boundary=prepared-business-selects-and-row-json-not-disk-or-latency");
            assertEquals("未解码展示",service.displays(List.of(4L)).get(4L).name());
            assertEquals("1.1.0",mapper.selectById(1L).getVersion(),"single full template read stays available");
            try(var sql=session.getConnection().createStatement();var role=sql.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(role.next());assertTrue(role.getBoolean(1));
            }
        }
    }
    private static long rowBytes(SqlSession session,BoundSql bound,boolean summary) throws Exception {
        var mapped=factory.getConfiguration().getMappedStatement(AgentTemplateMapper.class.getName()+(summary?".selectDisplayByIds":".selectById"));
        try(var query=session.getConnection().prepareStatement(bound.getSql())) {
            new DefaultParameterHandler(mapped,bound.getParameterObject(),bound).setParameters(query);
            try(var rows=query.executeQuery()) {
                var meta=rows.getMetaData();assertEquals(summary?3:23,meta.getColumnCount());
                if(summary) {
                    var names=new java.util.HashSet<String>();for(int i=1;i<=3;i++)names.add(meta.getColumnLabel(i));
                    assertEquals(Set.of("id","name","version"),names);
                }
            }
        }
        try(var query=session.getConnection().prepareStatement("SELECT sum(octet_length(row_to_json(r)::text)) FROM ("+bound.getSql()+") r")) {
            new DefaultParameterHandler(mapped,bound.getParameterObject(),bound).setParameters(query);
            try(var value=query.executeQuery()){assertTrue(value.next());return value.getLong(1);}
        }
    }
    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    public static final class Reads implements Interceptor {
        private final List<BoundSql> rows=new ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var bound=((StatementHandler)invocation.getTarget()).getBoundSql();
            if(bound.getSql().startsWith("SELECT")&&bound.getSql().contains("nw_agent_templates"))rows.add(bound);
            return invocation.proceed();
        }
    }
}
