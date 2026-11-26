package com.skillforge.platform.operationoptions;

import com.skillforge.platform.controller.PlatformOperationOptionMapper;
import java.util.Set;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** 使用已迁移的隔离库验证正式候选 SQL；样本持久保留，不清库或删除容器。 */
@EnabledIfEnvironmentVariable(named="OPERATION_OPTIONS_JDBC_URL", matches=".+")
class InstitutionOperationOptionsDatabaseTest {
    @Test void maintenanceCandidatesExcludeInternalSpaceButRetainOrdinaryStatusesAndAuthorization() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("OPERATION_OPTIONS_JDBC_URL"),
                System.getenv("OPERATION_OPTIONS_DB_USER"), System.getenv("OPERATION_OPTIONS_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        long first = System.currentTimeMillis() * 1000;
        long internal = 900000000000000001L;
        String[] statuses = {"active", "suspended", "warning", "terminated", "active"};
        for (int i = 0; i < statuses.length; i++) {
            jdbc.update("""
                    INSERT INTO nw_tenants(id,name,slug,plan_id,status)
                    SELECT ?,?,?,id,? FROM nw_billing_plans WHERE plan_code='free'
                    """, first + i, "机构工作台候选验收-" + i, "workspace-options-" + (first + i), statuses[i]);
        }
        var config = new Configuration(new Environment("workspace-options-retained", new JdbcTransactionFactory(), source));
        String resource = "mapper/PlatformOperationOptionMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var mapper = session.getMapper(PlatformOperationOptionMapper.class);
            var allowed = Set.of(first, first + 1, first + 2, first + 3, internal);
            assertEquals(4, mapper.tenantCount(allowed, "", null));
            assertEquals(4, mapper.tenants(allowed, "候选验收", null, 20, 0).size());
            assertEquals(1, mapper.tenants(allowed, "", null, 3, 3).size());
            assertEquals(Long.toString(first + 1), mapper.tenants(allowed, "", first + 1, 20, 0).getFirst().id());
            assertEquals(0, mapper.tenantCount(allowed, "", internal));
            assertTrue(mapper.tenants(allowed, "platform-system-agents", null, 20, 0).isEmpty());
            assertTrue(mapper.tenants(allowed, "", internal, 20, 0).isEmpty());
            assertTrue(mapper.tenants(allowed, "", first + 4, 20, 0).isEmpty());
            assertEquals(0, mapper.tenantCount(Set.of(), "", null));
        }
        assertEquals("internal", jdbc.queryForObject("SELECT status FROM nw_tenants WHERE id=?", String.class, internal));
        System.out.println("workspace-options retained tenant range=" + first + ".." + (first + 4));
    }
}
