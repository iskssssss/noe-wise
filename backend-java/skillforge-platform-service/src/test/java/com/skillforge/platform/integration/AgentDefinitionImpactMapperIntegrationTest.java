package com.skillforge.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.deployment.AgentBindingDeclarations.BindingShape;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.AgentTemplateService;
import com.skillforge.core.agent.model.AgentTemplateDO;
import com.skillforge.core.agent.model.AgentTemplateDefinition;
import com.skillforge.core.capability.CapabilityCatalogContextMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.platform.service.AgentDefinitionImpactService;
import com.skillforge.common.context.TenantContext;
import java.io.InputStream;
import java.sql.DriverManager;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 执行正式 MyBatis impact SQL，证明受限角色/RLS和数据最小化，绝不连接业务数据库。 */
@Testcontainers(disabledWithoutDocker=false)
class AgentDefinitionImpactMapperIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    private static SqlSessionFactory sessions;
    private static PGSimpleDataSource dataSource;
    private static SqlSessionTemplate springSessions;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll static void schema() throws Exception {
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                var sql=connection.createStatement()) {
            sql.execute("CREATE ROLE impact_reader LOGIN PASSWORD 'isolated-test-only'");
            sql.execute("CREATE TABLE nw_agent_deployments(deployment_id bigint PRIMARY KEY,tenant_id bigint NOT NULL,agent_id bigint NOT NULL,definition_id bigint NOT NULL,revision bigint NOT NULL,bindings jsonb NOT NULL)");
            sql.execute("ALTER TABLE nw_agent_deployments ENABLE ROW LEVEL SECURITY");
            sql.execute("ALTER TABLE nw_agent_deployments FORCE ROW LEVEL SECURITY");
            sql.execute("CREATE POLICY impact_tenant ON nw_agent_deployments USING(tenant_id::text=current_setting('app.tenant_id',true))");
            sql.execute("GRANT SELECT ON nw_agent_deployments TO impact_reader");
            String bindings="""
                    {"resource_bindings":[{"slot_key":"knowledge","mount":{"capability_type":"KNOWLEDGE_SOURCE",
                    "capability_definition_id":"private-resource","knowledge_config":{"credential":"private-secret"}}}],
                    "delegation_bindings":[{"slot_key":"assistant","target_agent_id":"private-agent"}],
                    "parameters":{"store":"private-body"}}
                    """;
            try(var insert=connection.prepareStatement("INSERT INTO nw_agent_deployments VALUES(?,?,?,?,?,?::jsonb)")) {
                long[][] rows={{101,7,11,31,2},{102,8,12,31,3},{103,7,13,32,4},{104,7,14,33,5}};
                for(var row:rows) {
                    for(int index=0;index<5;index++) insert.setLong(index+1,row[index]);
                    insert.setString(6,row[3]==33?"{}":bindings);insert.executeUpdate();
                }
            }
        }
        dataSource=new PGSimpleDataSource();dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser("impact_reader");dataSource.setPassword("isolated-test-only");
        var configuration=new Configuration(new Environment("impact",new JdbcTransactionFactory(),dataSource));
        configuration.setMapUnderscoreToCamelCase(true);configuration.setArgNameBasedConstructorAutoMapping(true);
        String resource="mapper/AgentDeploymentMapper.xml";
        try(InputStream input=AgentDeploymentMapper.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
        }
        sessions=new SqlSessionFactoryBuilder().build(configuration);
        var springConfiguration=new Configuration();springConfiguration.setMapUnderscoreToCamelCase(true);
        springConfiguration.setArgNameBasedConstructorAutoMapping(true);springConfiguration.addInterceptor(new TenantRlsInterceptor(java.util.List.of(
                new com.skillforge.core.foundation.access.FoundationAuthorizedSqlRule(),
                new com.skillforge.core.agent.AgentAuthorizedSqlRule(),
                new com.skillforge.core.capability.CapabilityAuthorizedSqlRule())));
        var springFactory=new SqlSessionFactoryBean();springFactory.setDataSource(dataSource);springFactory.setConfiguration(springConfiguration);
        springFactory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/AgentDeploymentMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mapper/CapabilityCatalogContextMapper.xml"));
        springFactory.afterPropertiesSet();springSessions=new SqlSessionTemplate(springFactory.getObject());
        transactionManager=new DataSourceTransactionManager(dataSource);
    }

    @AfterEach void clearTenant(){TenantContext.clear();}

    @Test void exactTenantAndDefinitionSqlNeverReturnsPrivateBindingValues() throws Exception {
        try(var session=sessions.openSession()) {
            var mapper=session.getMapper(AgentDeploymentMapper.class);
            mapper.restoreDatabaseContext("7","false");
            var rows=mapper.impact(7,31);assertEquals(1,rows.size());
            var row=rows.getFirst();assertEquals(101L,row.deploymentId());assertEquals(2L,row.revision());
            String shape=row.bindingShape();
            assertFalse(shape.contains("private-resource"));assertFalse(shape.contains("private-agent"));
            assertFalse(shape.contains("private-secret"));assertFalse(shape.contains("private-body"));
            var parsed=new ObjectMapper().readValue(shape,BindingShape.class);
            assertEquals("knowledge",parsed.resources().getFirst().slotKey());
            assertEquals(java.util.Set.of("store"),parsed.parameterKeys());
            assertTrue(mapper.impact(7,999).isEmpty());
        }
    }

    @Test void platformFlagDoesNotBypassTenantRlsAndContextCanBeRestored() {
        try(var session=sessions.openSession()) {
            var mapper=session.getMapper(AgentDeploymentMapper.class);
            mapper.restoreDatabaseContext("7","true");
            assertTrue(mapper.impact(8,31).isEmpty());
            mapper.restoreDatabaseContext("8","false");
            assertEquals(102L,mapper.impact(8,31).getFirst().deploymentId());
            mapper.restoreDatabaseContext("7","true");
            assertEquals("7",mapper.databaseContext().tenant());assertEquals("true",mapper.databaseContext().platformAdmin());
        }
    }

    @Test void malformedStoredStructureCannotTurnIntoAnEmptySatisfiedProjection() {
        try(var session=sessions.openSession()) {
            var mapper=session.getMapper(AgentDeploymentMapper.class);mapper.restoreDatabaseContext("7","false");
            assertNull(mapper.impact(7,33).getFirst().bindingShape());
        }
    }

    @Test void repeatableReadKeepsTheComparedBindingRevisionWhenAnotherTransactionEditsIt() throws Exception {
        try(var session=sessions.openSession()) {
            session.getConnection().setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            var mapper=session.getMapper(AgentDeploymentMapper.class);mapper.restoreDatabaseContext("7","false");
            long previous=mapper.impact(7,32).getFirst().revision();
            try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                    var sql=connection.createStatement()) {
                sql.execute("UPDATE nw_agent_deployments SET revision=revision+1,bindings=jsonb_set(bindings,'{parameters}', '{\"other\":\"concurrent-private-value\"}') WHERE deployment_id=103");
            }
            var sameSnapshot=mapper.impact(7,32).getFirst();
            assertEquals(previous,sameSnapshot.revision());assertFalse(sameSnapshot.bindingShape().contains("other"));
            session.commit(true);mapper.restoreDatabaseContext("7","false");
            assertEquals(previous+1,mapper.impact(7,32).getFirst().revision());
        }
    }

    @Test void actualSpringImpactReadsBothAuthorizedTenantsOnOneConnectionAndSnapshot() throws Exception {
        var real=springSessions.getMapper(AgentDeploymentMapper.class);var observed=spy(real);
        long previous;
        try(var owner=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var query=owner.createStatement();
                var row=query.executeQuery("SELECT revision FROM nw_agent_deployments WHERE deployment_id=102")) {assertTrue(row.next());previous=row.getLong(1);}
        doAnswer(invocation->{
            try(var owner=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var query=owner.createStatement()) {
                query.execute("UPDATE nw_agent_deployments SET revision=revision+1 WHERE deployment_id=102");
            }
            return real.impact(8,31);
        }).when(observed).impact(8,31);
        var service=impactService(observed,java.util.Set.of(7L,8L));
        var read=new TransactionTemplate(transactionManager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var jdbc=new JdbcTemplate(dataSource);
        read.executeWithoutResult(status->{
            int backend=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
            assertEquals("on",jdbc.queryForObject("SHOW transaction_read_only",String.class));
            var result=service.impact(31L,1);
            assertEquals(2,result.visibleDeploymentCount());
            assertEquals(java.util.List.of(7L,8L),result.deployments().stream().map(item->item.tenantId()).toList());
            assertEquals(previous,result.deployments().stream().filter(item->item.tenantId()==8L).findFirst().orElseThrow().deploymentRevision());
            assertEquals(backend,jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
            assertNull(TenantContext.getTenantId());
            assertEquals("",jdbc.queryForObject("SELECT COALESCE(current_setting('app.tenant_id',true),'')",String.class));
            assertTrue(jdbc.queryForObject("SELECT COALESCE(current_setting('app.platform_admin',true),'false')",String.class).equals("false"));
        });
        assertNull(TenantContext.getTenantId());
    }

    @Test void formalInterceptorStillRejectsOrdinaryTenantSwitchAndPlatformWindowFromWritableTransaction() {
        var mapper=springSessions.getMapper(AgentDeploymentMapper.class);
        var ordinary=new TransactionTemplate(transactionManager);
        ordinary.executeWithoutResult(status->{
            TenantContext.setTenantId("7");assertEquals(1,mapper.impact(7,31).size());TenantContext.setTenantId("8");
            assertRootMessage(assertThrows(RuntimeException.class,()->mapper.impact(8,31)),"tenant_transaction_scope_mismatch");
        });
        TenantContext.clear();
        assertRootMessage(assertThrows(RuntimeException.class,()->ordinary.executeWithoutResult(status->impactService(mapper,java.util.Set.of(7L,8L)).impact(31L,1))),
                "definition_impact_requires_platform_read_only_transaction");
    }

    private AgentDefinitionImpactService impactService(AgentDeploymentMapper mapper,java.util.Set<Long> tenants) {
        var definitions=mock(AgentTemplateService.class);var management=mock(PlatformManagementService.class);
        var draft=new AgentTemplateDO();draft.setId(31L);draft.setOwnerKind("platform");draft.setDraftRevision(1L);draft.setDefinition(AgentTemplateDefinition.empty());
        when(definitions.getById(31L)).thenReturn(draft);
        when(management.authorizedDefinitionReadScope(31L)).thenReturn(new PlatformManagementService.TenantScope("test-authorized",1,tenants));
        return new AgentDefinitionImpactService(definitions,mapper,management,
                new CapabilityCatalogDatabaseContext(springSessions.getMapper(CapabilityCatalogContextMapper.class)),new ObjectMapper(),
                mock(com.skillforge.core.agent.AgentDefinitionLifecycleService.class));
    }

    private static void assertRootMessage(Throwable failure,String message) {
        for(Throwable current=failure;current!=null;current=current.getCause())if(message.equals(current.getMessage()))return;
        fail("缺少拒绝原因 "+message,failure);
    }
}
