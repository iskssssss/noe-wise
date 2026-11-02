package com.skillforge.platform.operationoptions;

import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.ManagementMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import java.io.InputStream;
import java.sql.DriverManager;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 正式套餐 SQL 对候选与计数同时限制启用状态，不能选到已停用套餐。 */
@Testcontainers(disabledWithoutDocker=false)
class ProvisioningPlanMapperIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    private static SqlSessionFactory sessions;
    @BeforeAll static void schema() throws Exception {
        try(var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE nw_billing_plans(id bigint PRIMARY KEY,plan_code text,display_name text,is_active boolean)");
            sql.execute("CREATE TABLE nw_platform_management_namespace_grants(namespace text,action text,grantee_type text,grantee_id bigint)");
            sql.execute("CREATE TABLE nw_foundation_member_profiles(scope_key text,member_id bigint,department_id bigint)");
            sql.execute("CREATE TABLE nw_foundation_departments(scope_key text,id bigint,enabled boolean)");
            sql.execute("CREATE TABLE nw_foundation_member_roles(scope_key text,member_id bigint,role_id bigint)");
            sql.execute("CREATE TABLE nw_foundation_roles(scope_key text,id bigint,enabled boolean)");
            sql.execute("INSERT INTO nw_platform_management_namespace_grants VALUES('own','platform.institutions.provision','member',401),('foreign','platform.institutions.provision','member',402),('other-action','platform.institutions.associate','member',401)");
            sql.execute("INSERT INTO nw_billing_plans VALUES(1,'active','启用套餐',true),(2,'disabled','停用套餐',false)");
        }
        var source=new PGSimpleDataSource();source.setURL(POSTGRES.getJdbcUrl());source.setUser(POSTGRES.getUsername());source.setPassword(POSTGRES.getPassword());
        var configuration=new Configuration(new Environment("plans",new JdbcTransactionFactory(),source));
        String resource="mapper/InstitutionIntegrationMapper.xml";
        try(InputStream input=InstitutionIntegrationMapper.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
        }
        String managementResource="mapper/ManagementMapper.xml";
        try(InputStream input=ManagementMapper.class.getClassLoader().getResourceAsStream(managementResource)) {
            assertNotNull(input);new XMLMapperBuilder(input,configuration,managementResource,configuration.getSqlFragments()).parse();
        }
        sessions=new SqlSessionFactoryBuilder().build(configuration);
    }
    @Test void disabledPlanCannotAppearInSearchExactResolveOrCount() {
        try(var session=sessions.openSession()) {
            var mapper=session.getMapper(InstitutionIntegrationMapper.class);
            assertEquals(1,mapper.activePlanCount("",null));assertEquals("active",mapper.activePlans("",null,20,0).getFirst().id());
            assertTrue(mapper.activePlans("","disabled",20,0).isEmpty());assertEquals(0,mapper.activePlanCount("停用",null));
        }
    }
    @Test void namespaceCandidatesAndAdmissionUseSameExactGranteeAndAction() {
        try(var session=sessions.openSession()) {
            var mapper=session.getMapper(ManagementMapper.class);
            var filter=new FoundationAccess.DataScope(false,"401",java.util.Set.of());
            assertEquals(java.util.List.of("own"),mapper.allowedNamespaces("platform.institutions.provision",filter));
            assertTrue(mapper.namespaceAllowed("own","platform.institutions.provision",filter));
            assertFalse(mapper.namespaceAllowed("foreign","platform.institutions.provision",filter));
            assertEquals(java.util.List.of("other-action"),mapper.allowedNamespaces("platform.institutions.associate",filter));
        }
    }
}
