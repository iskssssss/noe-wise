package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper.InstallationIdentity;
import com.skillforge.core.workflow.mapper.WorkflowInstanceMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式安装修订约束、真实 MyBatis/JSONB 与受限列权限验证管理批读；不宣称完整迁移验收。 */
@Testcontainers(disabledWithoutDocker = false)
class WorkflowInstanceListIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate admin;
    private static WorkflowInstanceMapper instances;
    private static WorkflowInstallationMapper installations;
    private static WorkflowDefinitionVersionMapper versions;
    private static WorkflowContainerMapper containers;
    private WorkflowInstanceService service;

    @BeforeAll
    static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        admin.execute(migration("V65__workflow_builder_lifecycle.sql"));
        String baseline = migration("V1__baseline.sql");
        int table = baseline.indexOf("CREATE TABLE public.nw_workflow_instances (");
        assertTrue(table >= 0);
        admin.execute(baseline.substring(table, baseline.indexOf(";", table) + 1));
        admin.execute("ALTER TABLE nw_workflow_instances ADD PRIMARY KEY(id), ADD UNIQUE(tenant_id,id)");
        admin.execute("ALTER TABLE nw_workflow_definition_versions ADD COLUMN capability_version_id bigint");
        admin.execute(migration("V232__workflow_installation_revisions.sql"));
        admin.execute("ALTER TABLE nw_workflow_instances ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE nw_workflow_instances FORCE ROW LEVEL SECURITY");
        int policy = baseline.indexOf("CREATE POLICY skillforge_workflow_instances_tenant_isolation ");
        assertTrue(policy >= 0);
        admin.execute(baseline.substring(policy, baseline.indexOf(";", policy) + 1));
        // 当前普通实例列表依赖 V332 测试归属；只安装该查询读取的两列投影，不冒充完整测试资源包迁移。
        admin.execute("CREATE TABLE nw_workflow_test_sessions(tenant_id bigint NOT NULL,instance_id bigint NOT NULL,"
                + "PRIMARY KEY(tenant_id,instance_id),FOREIGN KEY(tenant_id,instance_id) REFERENCES nw_workflow_instances(tenant_id,id))");
        admin.execute("ALTER TABLE nw_workflow_test_sessions ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE nw_workflow_test_sessions FORCE ROW LEVEL SECURITY");
        admin.execute("CREATE POLICY instance_list_fixture_tenant ON nw_workflow_test_sessions USING(tenant_id::text=current_setting('app.tenant_id',true))");
        admin.execute("CREATE ROLE workflow_instance_list_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT SELECT ON nw_workflow_test_sessions TO workflow_instance_list_test");
        admin.execute("GRANT SELECT ON nw_workflow_instances TO workflow_instance_list_test");
        admin.execute("GRANT SELECT(tenant_id,instance_id,installation_revision,definition_version_id,config,custom_prompt)"
                + " ON nw_workflow_installation_revisions TO workflow_instance_list_test");
        admin.execute("GRANT SELECT(id,tenant_id,container_id,capability_version_id,definition)"
                + " ON nw_workflow_definition_versions TO workflow_instance_list_test");
        admin.execute("GRANT SELECT(id,tenant_id,workflow_id,active_definition_version_id) ON nw_workflow_containers TO workflow_instance_list_test");
        var application = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                "workflow_instance_list_test", "test-only-password");
        assertEquals(false, new JdbcTemplate(application).queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("workflow-instance-list-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), application));
        configuration.addInterceptor(new TenantRlsInterceptor());
        for (var mapper : List.of(WorkflowInstanceMapper.class, WorkflowInstallationMapper.class,
                WorkflowDefinitionVersionMapper.class, WorkflowContainerMapper.class)) configuration.addMapper(mapper);
        try (var input = WorkflowInstanceListIntegrationTest.class.getClassLoader()
                .getResourceAsStream("mapper/WorkflowInstallationMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, "mapper/WorkflowInstallationMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        instances = sessions.getMapper(WorkflowInstanceMapper.class);
        installations = sessions.getMapper(WorkflowInstallationMapper.class);
        versions = sessions.getMapper(WorkflowDefinitionVersionMapper.class);
        containers = sessions.getMapper(WorkflowContainerMapper.class);
    }

    @BeforeEach
    void seed() {
        admin.execute("TRUNCATE nw_workflow_instances,nw_workflow_containers CASCADE");
        admin.update("INSERT INTO nw_workflow_containers(id,tenant_id,workflow_id,status,current_draft_revision)"
                + " VALUES(41,7,'first-flow','draft',3),(42,7,'second-flow','archived',4),"
                + "(81,8,'foreign-flow','draft',1)");
        admin.update("INSERT INTO nw_workflow_draft_revisions(id,tenant_id,container_id,revision,definition)"
                + " VALUES(100,7,41,1,'{}'),(101,7,41,2,'{}'),(102,7,42,1,'{}'),(181,8,81,1,'{}')");
        admin.update("INSERT INTO nw_workflow_definition_versions(id,tenant_id,container_id,source_draft_id,definition_version,definition,capability_version_id)"
                + " VALUES(90,7,41,100,1,'{}',500),(91,7,41,101,2,'{}',501),"
                + "(92,7,42,102,1,'{}',502),(191,8,81,181,1,'{}',1501)");
        admin.update("UPDATE nw_workflow_containers SET active_definition_version_id=91 WHERE id=41");
        admin.update("UPDATE nw_workflow_containers SET active_definition_version_id=92 WHERE id=42");
        admin.update("UPDATE nw_workflow_containers SET active_definition_version_id=191 WHERE id=81");
        admin.update("INSERT INTO nw_workflow_instances(id,instance_id,tenant_id,definition_version_id,status,created_at)"
                + " VALUES(11,'first-flow_7',7,91,'draft','2026-01-02T00:00:00Z'),"
                + "(12,'second-flow_7',7,92,'draft','2026-01-01T00:00:00Z'),"
                + "(81,'foreign-flow_8',8,191,'draft',now())");
        insertRevision(7, 11, 1, 90, "{\"name\":\"old revision\"}", "old prompt");
        insertRevision(7, 11, 2, 91, "{\"locale\":\"zh-CN\",\"options\":{\"flag\":true},\"steps\":[1,2]}", "current prompt");
        insertRevision(7, 11, 3, 91, "{\"name\":\"future revision\"}", "future prompt");
        insertRevision(7, 12, 4, 92, "{}", "");
        insertRevision(8, 81, 2, 191, "{\"locale\":\"en-US\"}", "foreign prompt");
        admin.update("UPDATE nw_workflow_instances SET installation_revision=2,status='disabled' WHERE id=11");
        admin.update("UPDATE nw_workflow_instances SET installation_revision=4,status='published' WHERE id=12");
        admin.update("UPDATE nw_workflow_instances SET installation_revision=2,status='published' WHERE id=81");
        service = service(instances);
        TenantContext.setTenantId("7");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        admin.execute("DROP POLICY IF EXISTS hide_read ON nw_workflow_definition_versions");
        admin.execute("DROP POLICY IF EXISTS hide_read ON nw_workflow_containers");
        admin.execute("DROP POLICY IF EXISTS hide_read ON nw_workflow_installation_revisions");
    }

    @Test
    void exactBatchesMapJsonAndOmitUnusedColumnsWithoutAddingPublicationFilters() {
        assertThrows(RuntimeException.class, () -> installations.find(7, 11, 2));
        assertThrows(RuntimeException.class, () -> versions.selectList(null));
        assertThrows(RuntimeException.class, () -> containers.selectList(null));
        assertEquals(List.of(), installations.findBuilderReads(7, List.of()));
        assertEquals(List.of(), installations.findBuilderReads(7, List.of(new InstallationIdentity(11L, 4L))));

        var result = service.listBuilderInstances(7);

        assertEquals(List.of(11L, 12L), result.stream().map(row -> row.id()).toList());
        var first = result.getFirst();
        assertEquals(2L, first.installationRevision());
        assertEquals(91L, first.definitionVersionId());
        assertEquals(501L, first.capabilityVersionId());
        assertEquals("current prompt", first.customPrompt());
        assertEquals(Map.of("locale", "zh-CN", "options", Map.of("flag", true), "steps", List.of(1, 2)), first.config());
        assertEquals("disabled", first.status());
        assertEquals("first-flow", first.workflowId());
        assertEquals(Map.of(), result.get(1).config());
        assertThrows(UnsupportedOperationException.class, () -> first.config().put("new", "value"));
    }

    @Test
    void testOwnedInstanceIsExcludedBeforeItsInstallationIsLoaded() {
        admin.update("INSERT INTO nw_workflow_test_sessions VALUES(7,12)");
        admin.execute("CREATE POLICY hide_read ON nw_workflow_installation_revisions AS RESTRICTIVE USING(instance_id<>12)");
        assertEquals(List.of(11L),service.listBuilderInstances(7).stream().map(row->row.id()).toList());
    }

    @Test
    void committedPointerAdvanceAfterTheInitialQueryDoesNotReplaceTheReadSnapshot() {
        WorkflowInstanceMapper observed = mock(WorkflowInstanceMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(instances));
        doAnswer(invocation -> {
            var captured = instances.selectList(invocation.getArgument(0));
            admin.update("UPDATE nw_workflow_instances SET installation_revision=3 WHERE id=11");
            return captured;
        }).when(observed).selectList(any());

        var result = service(observed).listBuilderInstances(7);

        assertEquals(2L, result.getFirst().installationRevision());
        assertEquals("current prompt", result.getFirst().customPrompt());
        assertEquals(3L, admin.queryForObject("SELECT installation_revision FROM nw_workflow_instances WHERE id=11", Long.class));
        verify(observed).selectList(any());
        verify(observed, never()).selectOne(any());
    }

    @Test
    void tenantFiltersAndRlsRejectCrossTenantOrMissingContext() {
        assertEquals(List.of(), service.listBuilderInstances(8));
        assertEquals(List.of(), installations.findBuilderReads(8, List.of(new InstallationIdentity(81L, 2L))));
        TenantContext.setTenantId("8");
        assertEquals(List.of(), service.listBuilderInstances(7));
        assertEquals(List.of(81L), service.listBuilderInstances(8).stream().map(row -> row.id()).toList());
        TenantContext.clear();
        assertEquals(List.of(), service.listBuilderInstances(7));
    }

    @Test
    void leftJoinsKeepMissingVersionOrContainerAndJsonNullAsTheOriginalErrors() {
        admin.execute("CREATE POLICY hide_read ON nw_workflow_definition_versions AS RESTRICTIVE USING(id<>91)");
        var versionHidden = installations.findBuilderReads(7, List.of(new InstallationIdentity(11L, 2L))).getFirst();
        assertNull(versionHidden.boundDefinitionVersionId());
        assertNull(versionHidden.capabilityVersionId());
        assertNull(versionHidden.containerId());
        assertFalse(versionHidden.definitionPresent());
        assertError("Workflow 实例绑定的 DefinitionVersion 不可用，请重新安装或升级: 11");
        admin.execute("DROP POLICY hide_read ON nw_workflow_definition_versions");

        admin.execute("CREATE POLICY hide_read ON nw_workflow_containers AS RESTRICTIVE USING(id<>41)");
        var containerHidden = installations.findBuilderReads(7, List.of(new InstallationIdentity(11L, 2L))).getFirst();
        assertNull(containerHidden.boundDefinitionVersionId());
        assertNull(containerHidden.containerId());
        assertNull(containerHidden.workflowId());
        assertFalse(containerHidden.definitionPresent());
        assertError("Workflow 实例绑定的 DefinitionVersion 不可用，请重新安装或升级: 11");
        admin.execute("DROP POLICY hide_read ON nw_workflow_containers");

        admin.update("UPDATE nw_workflow_definition_versions SET definition='null'::jsonb WHERE id=91");
        assertFalse(installations.findBuilderReads(7, List.of(new InstallationIdentity(11L, 2L)))
                .getFirst().definitionPresent());
        assertError("Workflow 实例绑定的 DefinitionVersion 不可用，请重新安装或升级: 11");
    }

    @Test
    void anEarlierVersionFailurePrecedesALaterInstallationHiddenByRls() {
        admin.execute("CREATE POLICY hide_read ON nw_workflow_definition_versions AS RESTRICTIVE USING(id<>91)");
        admin.execute("CREATE POLICY hide_read ON nw_workflow_installation_revisions AS RESTRICTIVE USING(instance_id<>12)");
        assertError("Workflow 实例绑定的 DefinitionVersion 不可用，请重新安装或升级: 11");
    }

    private void assertError(String message) {
        var error = assertThrows(ApiException.class, () -> service.listBuilderInstances(7));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(message, error.getMessage());
    }

    private static WorkflowInstanceService service(WorkflowInstanceMapper mapper) {
        return new WorkflowInstanceService(mapper, installations, new WorkflowInstallationDefinitionService(
                mock(WorkflowContainerMapper.class), mock(WorkflowDefinitionVersionMapper.class)));
    }

    private static void insertRevision(long tenant, long instance, long revision, long definition, String config, String prompt) {
        admin.update("INSERT INTO nw_workflow_installation_revisions(tenant_id,instance_id,installation_revision,definition_version_id,config,custom_prompt,bindings,created_at)"
                + " VALUES(?,?,?,?,?::jsonb,?,'{}',now())", tenant, instance, revision, definition, config, prompt);
    }

    private static String migration(String name) throws Exception {
        try (var input = WorkflowInstanceListIntegrationTest.class.getClassLoader().getResourceAsStream("db/migration/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
