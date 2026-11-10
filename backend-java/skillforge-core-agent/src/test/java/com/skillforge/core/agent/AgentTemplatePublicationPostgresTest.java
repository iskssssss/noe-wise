package com.skillforge.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.agent.mapper.AgentTemplateMapper;
import com.skillforge.core.shared.exception.ApiException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 私有发布历史最小表、正式XML与受限只读角色；不替代全迁移或生命周期事务验收。 */
@Testcontainers(disabledWithoutDocker=false)
class AgentTemplatePublicationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static SqlSessionFactory factory;
    private static final Reads READS=new Reads();
    @BeforeAll static void database() throws Exception {
        try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE nw_agent_template_publications(template_id bigint NOT NULL,version varchar(50) NOT NULL,snapshot jsonb NOT NULL,published_at timestamptz NOT NULL,PRIMARY KEY(template_id,version))");
            sql.execute("""
                    INSERT INTO nw_agent_template_publications
                    SELECT 31,'1.'||n||'.0',jsonb_build_object('id',31,'name','历史'||n,'version','1.'||n||'.0',
                    'definition',jsonb_build_object('system_prompt',repeat('x',65536),'role_description','历史角色')),
                    '2026-10-30T12:00:00Z'::timestamptz+n*interval '1 minute' FROM generate_series(1,3) n
                    """);
            sql.execute("""
                    INSERT INTO nw_agent_template_publications VALUES
                    (41,'good',jsonb_build_object('id',41,'name','有效目标','version','good'),'2026-10-30T12:00:00Z'),
                    (41,'bad',jsonb_build_object('definition',42),'2026-10-30T12:01:00Z')
                    """);
            sql.execute("CREATE ROLE publication_reader LOGIN PASSWORD 'isolated-only' NOSUPERUSER NOBYPASSRLS");
            sql.execute("GRANT USAGE ON SCHEMA public TO publication_reader; GRANT SELECT ON nw_agent_template_publications TO publication_reader");
        }
        var source=new PGSimpleDataSource();source.setURL(PG.getJdbcUrl());source.setUser("publication_reader");source.setPassword("isolated-only");
        var configuration=new MybatisConfiguration(new Environment("exact-publication",new JdbcTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(READS);
        try(var xml=AgentTemplatePublicationPostgresTest.class.getClassLoader().getResourceAsStream("mapper/AgentTemplateMapper.xml")) {
            assertNotNull(xml);new XMLMapperBuilder(xml,configuration,"mapper/AgentTemplateMapper.xml",configuration.getSqlFragments()).parse();
        }
        factory=new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
    @Test void exactIdentityReadsOneSnapshotWhileFullHistoryRetainsOrderAndOtherVersions() throws Exception {
        try(var session=factory.openSession()) {
            var mapper=session.getMapper(AgentTemplateMapper.class);
            var json=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            var service=new AgentTemplateService(null,null,null,json,null);ReflectionTestUtils.setField(service,"baseMapper",mapper);
            READS.queries.clear();var history=service.publications(31L);
            assertEquals(List.of("1.3.0","1.2.0","1.1.0"),history.stream().map(row->row.getVersion()).toList());
            assertEquals(1,READS.queries.size());var full=READS.queries.getFirst();READS.queries.clear();
            var exact=service.requirePublication(31L,"1.2.0");assertEquals(1,READS.queries.size());var target=READS.queries.getFirst();
            assertEquals(json.valueToTree(history.get(1)),json.valueToTree(exact));
            var before=material(session,full,"publications");var after=material(session,target,"publication");
            assertEquals(3,before.rows());assertEquals(1,after.rows());assertTrue(before.bytes()>after.bytes()+131072);
            System.out.println("AGENT_RESTORE_READ full_selects=1 exact_selects=1 baseline_rows="+before.rows()+" exact_rows="+after.rows()
                    +" baseline_bytes="+before.bytes()+" exact_bytes="+after.bytes()+" boundary=row-json-only-not-disk-or-latency");
            assertEquals("有效目标",service.requirePublication(41L,"good").getName());
            assertThrows(IllegalStateException.class,()->service.publications(41L),"full history contract still decodes all history");
            assertThrows(IllegalStateException.class,()->service.requirePublication(41L,"bad"));
            assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->service.requirePublication(31L,"good")).getStatus());
            assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->service.requirePublication(999L,"1.2.0")).getStatus());
            assertEquals(HttpStatus.NOT_FOUND,assertThrows(ApiException.class,()->service.requirePublication(31L,"missing")).getStatus());
            try(var sql=session.getConnection().createStatement();var role=sql.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(role.next());assertTrue(role.getBoolean(1));
            }
        }
    }
    private record Material(long rows,long bytes) { }
    private static Material material(SqlSession session,BoundSql bound,String method) throws Exception {
        var mapped=factory.getConfiguration().getMappedStatement(AgentTemplateMapper.class.getName()+"."+method);
        try(var query=session.getConnection().prepareStatement("SELECT count(*),sum(octet_length(row_to_json(r)::text)) FROM ("+bound.getSql()+") r")) {
            new DefaultParameterHandler(mapped,bound.getParameterObject(),bound).setParameters(query);
            try(var result=query.executeQuery()){assertTrue(result.next());return new Material(result.getLong(1),result.getLong(2));}
        }
    }
    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    public static final class Reads implements Interceptor {
        private final List<BoundSql> queries=new ArrayList<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var bound=((StatementHandler)invocation.getTarget()).getBoundSql();
            if(bound.getSql().startsWith("SELECT")&&bound.getSql().contains("nw_agent_template_publications"))queries.add(bound);
            return invocation.proceed();
        }
    }
}
