package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skillforge.core.shared.config.MybatisPlusConfig;
import com.skillforge.core.workflow.dto.query.MarketplaceSearchQuery;
import com.skillforge.core.workflow.dto.response.MarketplaceWorkflowItemResponse;
import com.skillforge.core.workflow.mapper.WorkflowTemplateMapper;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 最小市场表夹具、正式MP Mapper及生产分页插件；不替代完整迁移或HTTP验收。 */
@Testcontainers(disabledWithoutDocker = false)
class MarketplaceProjectionPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static SqlSessionFactory factory;
    private static final QueryCapture CAPTURE = new QueryCapture();

    @BeforeAll static void database() throws Exception {
        try (var connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                var sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE nw_workflow_templates(id bigint PRIMARY KEY,workflow_id varchar(128),tenant_id bigint,
                    name varchar(128),version varchar(32),type varchar(32),category varchar(64),tags jsonb,
                    description text,config_schema jsonb,required_permissions jsonb,connector_requirements jsonb,tools jsonb,
                    author varchar(128),author_type varchar(32),price_type varchar(32),price_cents integer,
                    install_count integer,status varchar(32),published_at timestamptz,created_at timestamptz,updated_at timestamptz)
                    """);
            sql.execute("""
                    INSERT INTO nw_workflow_templates
                    SELECT n,'flow-'||n,CASE WHEN n=5 THEN 7 ELSE NULL END,'业务模板'||n,'1.0','rag','test','["tag"]',
                    '业务说明',jsonb_build_array(jsonb_build_object('name','setting','description',repeat('s',65536))),
                    '["tool.execute"]',jsonb_build_array(jsonb_build_object('capability',repeat('c',65536))),
                    jsonb_build_array(jsonb_build_object('tool',repeat('t',65536))),
                    '平台作者','platform','free',0,100-n,CASE WHEN n=4 THEN 'draft' ELSE 'published' END,
                    '2026-10-30T12:00:00Z'::timestamptz+n*interval '1 minute',now(),now()
                    FROM generate_series(1,5) n
                    """);
            sql.execute("CREATE ROLE market_reader LOGIN PASSWORD 'isolated-test-only' NOSUPERUSER NOBYPASSRLS");
            sql.execute("GRANT USAGE ON SCHEMA public TO market_reader; GRANT SELECT ON nw_workflow_templates TO market_reader");
        }
        var source = new PGSimpleDataSource(); source.setURL(PG.getJdbcUrl());
        source.setUser("market_reader"); source.setPassword("isolated-test-only");
        var configuration = new MybatisConfiguration(new Environment("marketplace-projection", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        configuration.addInterceptor(CAPTURE);
        configuration.addMapper(WorkflowTemplateMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test void tenColumnsMatchFullRowsAndKeepFiltersCountPaginationSortAndCompleteDetail() throws Exception {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(WorkflowTemplateMapper.class);
            var service = new WorkflowTemplateService(mapper);
            var query = new MarketplaceSearchQuery("业务", "rag", "test", null, 2, 1);
            var full = mapper.selectPage(new Page<WorkflowTemplateDO>(2, 1),
                    new LambdaQueryWrapper<WorkflowTemplateDO>().eq(WorkflowTemplateDO::getStatus,"published")
                    .isNull(WorkflowTemplateDO::getTenantId).and(w -> w.like(WorkflowTemplateDO::getName,"业务").or().like(WorkflowTemplateDO::getDescription,"业务"))
                    .eq(WorkflowTemplateDO::getType,"rag").eq(WorkflowTemplateDO::getCategory,"test")
                    .orderByDesc(WorkflowTemplateDO::getInstallCount));
            var baselineSql = CAPTURE.rows;
            assertEquals(3, full.getTotal());
            assertEquals(65536, full.getRecords().getFirst().getConfigSchema().getFirst().get("description").toString().length());
            var result = service.search(query);
            var summarySql = CAPTURE.rows;
            assertEquals(full.getTotal(), result.total()); assertEquals(2, result.page()); assertEquals(1, result.pageSize());
            assertEquals(full.getRecords().stream().map(MarketplaceProjectionPostgresTest::item).toList(), result.items());
            assertEquals("flow-2", result.items().getFirst().workflowId());
            long oldBytes = bytes(session, baselineSql, false);
            long newBytes = bytes(session, summarySql, true);
            assertTrue(oldBytes > newBytes + 196608);
            System.out.println("MARKETPLACE_READ_BYTES rows=1 baseline="+oldBytes+" candidate="+newBytes
                    +" boundary=row-json-only-not-latency-or-physical-disk");
            assertEquals("flow-3", service.search(new MarketplaceSearchQuery(null,null,null,"published_at",1,1)).items().getFirst().workflowId());
            assertTrue(service.search(new MarketplaceSearchQuery("absent",null,null,null,1,20)).items().isEmpty());
            assertTrue(service.search(new MarketplaceSearchQuery(null,"different",null,null,1,20)).items().isEmpty());
            assertTrue(service.search(new MarketplaceSearchQuery(null,null,"different",null,1,20)).items().isEmpty());
            var detail = service.detail("flow-2");
            assertEquals(65536, detail.configSchema().getFirst().get("description").toString().length());
            assertEquals(List.of("tool.execute"), detail.requiredPermissions());
            assertNotNull(mapper.selectOne(new LambdaQueryWrapper<WorkflowTemplateDO>().eq(WorkflowTemplateDO::getWorkflowId,"flow-2")).getTools());
            try (var sql = session.getConnection().createStatement(); var roles = sql.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(roles.next()); assertTrue(roles.getBoolean(1));
            }
        }
    }

    private static MarketplaceWorkflowItemResponse item(WorkflowTemplateDO row) {
        return new MarketplaceWorkflowItemResponse(row.getWorkflowId(),row.getName(),row.getVersion(),row.getType(),
                row.getDescription(),row.getAuthor(),row.getCategory(),row.getTags(),row.getInstallCount(),row.getPriceType());
    }

    private static long bytes(SqlSession session, BoundSql bound, boolean summary) throws Exception {
        assertNotNull(bound);
        var mapped = factory.getConfiguration().getMappedStatement(WorkflowTemplateMapper.class.getName()+".selectList");
        try (var statement = session.getConnection().prepareStatement(bound.getSql())) {
            new DefaultParameterHandler(mapped,bound.getParameterObject(),bound).setParameters(statement);
            try (var rows = statement.executeQuery()) {
                var columns = rows.getMetaData();
                if (summary) {
                    assertEquals(10,columns.getColumnCount());
                    var names = new java.util.HashSet<String>();
                    for (int column=1;column<=columns.getColumnCount();column++) names.add(columns.getColumnLabel(column));
                    assertEquals(Set.of("workflow_id","name","version","type","description","author","category","tags","install_count","price_type"),names);
                } else assertEquals(22,columns.getColumnCount());
            }
        }
        try (var statement = session.getConnection().prepareStatement("SELECT sum(octet_length(row_to_json(r)::text)) FROM ("+bound.getSql()+") r")) {
            new DefaultParameterHandler(mapped,bound.getParameterObject(),bound).setParameters(statement);
            try(var result=statement.executeQuery()){assertTrue(result.next());return result.getLong(1);}
        }
    }

    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    public static final class QueryCapture implements Interceptor {
        private BoundSql rows;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var bound=((StatementHandler)invocation.getTarget()).getBoundSql();
            if(bound.getSql().contains("nw_workflow_templates") && !bound.getSql().toLowerCase().contains("count(")) rows=bound;
            return invocation.proceed();
        }
    }
}
