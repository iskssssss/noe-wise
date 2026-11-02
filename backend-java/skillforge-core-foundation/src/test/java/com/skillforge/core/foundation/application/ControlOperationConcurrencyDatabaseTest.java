package com.skillforge.core.foundation.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.foundation.persistence.ControlOperationMapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式PostgreSQL、受限角色、Mapper和Spring事务验证并发幂等受理，不调用任何外部执行器。 */
@Testcontainers(disabledWithoutDocker=false)
class ControlOperationConcurrencyDatabaseTest {
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate owner;
    private static TransactionTemplate transactions;
    private static ControlOperationService service;
    private static volatile CyclicBarrier beforeInsert;

    @BeforeAll static void database() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        var admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        owner=new JdbcTemplate(admin);
        try(var connection=admin.getConnection()){CurrentHostFixtures.tenant(connection,7);CurrentHostFixtures.tenant(connection,8);}
        owner.update("INSERT INTO nw_institution_integrations(tenant_id,integration_id) VALUES(7,'integration-test')");
        owner.execute("CREATE ROLE control_receipt_test LOGIN PASSWORD 'synthetic-control-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO control_receipt_test");
        owner.execute("GRANT SELECT,INSERT,UPDATE ON nw_control_operations TO control_receipt_test");
        var source=new DriverManagerDataSource(DATABASE.getJdbcUrl(),"control_receipt_test","synthetic-control-password");
        var configuration=new MybatisConfiguration(new Environment("control-receipt",new SpringManagedTransactionFactory(),source));
        configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.addInterceptor(new ConcurrentInsert());
        String path="mapper/ControlOperationMapper.xml";
        try(var xml=new ClassPathResource(path).getInputStream()){
            new XMLMapperBuilder(xml,configuration,path,configuration.getSqlFragments()).parse();
        }
        var sessions=new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        service=new ControlOperationService(sessions.getMapper(ControlOperationMapper.class),mock(InstitutionIntegrationService.class));
    }

    @Test void sameFingerprintConcurrentFirstRequestsReturnOneCommittedReceipt() throws Exception {
        beforeInsert=new CyclicBarrier(2);
        try(var workers=Executors.newFixedThreadPool(2)){
            var first=workers.submit(()->accept("same-command","operation-a","fingerprint-a"));
            var second=workers.submit(()->accept("same-command","operation-b","fingerprint-a"));
            var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
            assertNotEquals(a.created(),b.created());
            assertEquals(a.operation().operationId(),b.operation().operationId());
            assertEquals(1L,owner.queryForObject("SELECT count(*) FROM nw_control_operations WHERE tenant_id=7 AND idempotency_key='same-command'",Long.class));
        } finally {beforeInsert=null;}
        assertFalse(accept("same-command","ignored-operation","fingerprint-a").created());
    }

    @Test void differentFingerprintsConcurrentFirstRequestsConflictWithoutAbortingTheReplayQuery() throws Exception {
        beforeInsert=new CyclicBarrier(2);
        try(var workers=Executors.newFixedThreadPool(2)){
            var first=workers.submit(()->outcome("different-command","different-a","fingerprint-a"));
            var second=workers.submit(()->outcome("different-command","different-b","fingerprint-b"));
            assertEquals(Set.of("accepted","fingerprint_conflict"),Set.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)));
            assertEquals(1L,owner.queryForObject("SELECT count(*) FROM nw_control_operations WHERE tenant_id=7 AND idempotency_key='different-command'",Long.class));
        } finally {beforeInsert=null;}
    }

    private static String outcome(String key,String operation,String fingerprint){
        try{accept(key,operation,fingerprint);return "accepted";}
        catch(IllegalArgumentException conflict){assertTrue(conflict.getMessage().contains("different request"));return "fingerprint_conflict";}
    }
    private static ControlOperationService.Acceptance accept(String key,String operation,String fingerprint){
        TenantContext.setTenantId("7");
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("fixture-principal","7","integration-test",1,Set.of("resources.write")));
        try{return transactions.execute(status->service.accept("7","integration-test",operation,null,"publish",key,fingerprint,List.of()));}
        finally{TenantContext.clear();ServicePrincipalContext.clear();}
    }
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    public static final class ConcurrentInsert implements Interceptor {
        @Override public Object intercept(Invocation invocation)throws Throwable{
            var statement=(MappedStatement)invocation.getArgs()[0];var barrier=beforeInsert;
            if(barrier!=null&&statement.getId().equals(ControlOperationMapper.class.getName()+".insertAccepted"))barrier.await(10,TimeUnit.SECONDS);
            return invocation.proceed();
        }
    }
}
