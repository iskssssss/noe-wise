package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowDraftRevisionMapper;
import com.skillforge.core.workflow.model.WorkflowDraftRevisionDO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实 Builder 表、JSONB 映射与受限角色 RLS 验证精确游标批量读取。 */
@Testcontainers(disabledWithoutDocker = false)
class WorkflowDraftListIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate admin;
    private static WorkflowContainerMapper containers;
    private static WorkflowDraftRevisionMapper drafts;
    private WorkflowDraftLifecycleService service;

    @BeforeAll
    static void schema() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        try (var input = WorkflowDraftListIntegrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V65__workflow_builder_lifecycle.sql")) {
            assertNotNull(input);
            admin.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        // 当前普通列表读取 V332 测试归属；此套件只安装所需的归属投影，完整资源包由 foundation 集成套件验证。
        admin.execute("ALTER TABLE nw_workflow_containers ADD CONSTRAINT draft_list_fixture_owner UNIQUE(tenant_id,id)");
        admin.execute("CREATE TABLE nw_workflow_test_sessions(tenant_id bigint NOT NULL,container_id bigint NOT NULL,"
                + "PRIMARY KEY(tenant_id,container_id),FOREIGN KEY(tenant_id,container_id) REFERENCES nw_workflow_containers(tenant_id,id))");
        admin.execute("ALTER TABLE nw_workflow_test_sessions ENABLE ROW LEVEL SECURITY");
        admin.execute("ALTER TABLE nw_workflow_test_sessions FORCE ROW LEVEL SECURITY");
        admin.execute("CREATE POLICY draft_list_fixture_tenant ON nw_workflow_test_sessions USING(tenant_id::text=current_setting('app.tenant_id',true))");
        admin.execute("CREATE ROLE workflow_list_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO workflow_list_test");
        var application = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                "workflow_list_test", "test-only-password");
        assertEquals(false, new JdbcTemplate(application).queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("workflow-list-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), application));
        configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.addMapper(WorkflowContainerMapper.class);
        configuration.addMapper(WorkflowDraftRevisionMapper.class);
        try (var input = WorkflowDraftListIntegrationTest.class.getClassLoader()
                .getResourceAsStream("mapper/WorkflowDraftRevisionMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, "mapper/WorkflowDraftRevisionMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        containers = sessions.getMapper(WorkflowContainerMapper.class);
        drafts = sessions.getMapper(WorkflowDraftRevisionMapper.class);
    }

    @BeforeEach
    void seed() {
        admin.execute("TRUNCATE nw_workflow_containers CASCADE");
        admin.update("INSERT INTO nw_workflow_containers(id,tenant_id,workflow_id,current_draft_revision,updated_at)"
                + " VALUES(41,7,'first-flow',2,'2026-01-02T00:00:00Z'),"
                + "(42,7,'second-flow',4,'2026-01-01T00:00:00Z'),(81,8,'foreign-flow',2,now())");
        insertDraft(101, 41, 7, 1, "历史名称");
        insertDraft(102, 41, 7, 2, "当前名称");
        insertDraft(103, 41, 7, 3, "更高修订");
        insertDraft(104, 42, 7, 2, "不能串用另一容器的修订");
        insertDraft(105, 42, 7, 4, "第二名称");
        insertDraft(106, 81, 8, 2, "其他机构");
        service = service(containers);
        TenantContext.setTenantId("7");
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void largeGraphsStayOutOfJavaMetadataProjectionWhileFullDraftAndFallbacksRemainAvailable() {
        admin.update("UPDATE nw_workflow_draft_revisions SET definition=jsonb_build_object("
                + "'meta',jsonb_build_object('display_name','  ','name',' 名称回退 ','description',' 描述 '),"
                + "'nodes',jsonb_build_array(jsonb_build_object('body',repeat('large-graph-',100000))),"
                + "'edges',jsonb_build_array(jsonb_build_object('source','start','target','end'))) WHERE id=102");
        var exact = new LambdaQueryWrapper<WorkflowDraftRevisionDO>()
                .eq(WorkflowDraftRevisionDO::getTenantId, 7L)
                .eq(WorkflowDraftRevisionDO::getContainerId, 41L)
                .eq(WorkflowDraftRevisionDO::getRevision, 2L);
        var metadata = drafts.selectListMetadata(exact).getFirst();
        assertEquals(java.util.Set.of("meta"), metadata.getDefinition().keySet());
        assertFalse(metadata.getDefinition().containsKey("nodes"));
        assertFalse(metadata.getDefinition().containsKey("edges"));
        assertTrue(drafts.selectOne(exact).getDefinition().containsKey("nodes"), "详情读取仍包含完整定义");
        assertEquals("名称回退", service.list(7).items().getFirst().name());
        assertEquals("描述", service.list(7).items().getFirst().description());
        admin.update("UPDATE nw_workflow_draft_revisions SET definition=jsonb_build_object('meta','invalid-meta') WHERE id=102");
        assertEquals("first-flow", service.list(7).items().getFirst().name());
        assertEquals("", service.list(7).items().getFirst().description());
        assertTrue(drafts.selectListMetadata(new LambdaQueryWrapper<WorkflowDraftRevisionDO>()
                .eq(WorkflowDraftRevisionDO::getTenantId, 8L)
                .eq(WorkflowDraftRevisionDO::getContainerId, 81L)
                .eq(WorkflowDraftRevisionDO::getRevision, 2L)).isEmpty());
        assertTrue(drafts.selectListMetadata(new LambdaQueryWrapper<WorkflowDraftRevisionDO>()
                .eq(WorkflowDraftRevisionDO::getTenantId, 7L)
                .eq(WorkflowDraftRevisionDO::getContainerId, 41L)
                .eq(WorkflowDraftRevisionDO::getRevision, 999L)).isEmpty());
    }

    @Test
    void readsExactRevisionPairsInContainerOrderAndRejectsCrossTenantContext() {
        var result = service.list(7);
        assertEquals(List.of("first-flow", "second-flow"), result.items().stream()
                .map(item -> item.workflowId()).toList());
        assertEquals(List.of("当前名称", "第二名称"), result.items().stream().map(item -> item.name()).toList());
        assertEquals(List.of(2L, 4L), result.items().stream().map(item -> item.revision()).toList());
        assertEquals(0, service.list(8).total());
        TenantContext.setTenantId("8");
        assertEquals(List.of("foreign-flow"), service.list(8).items().stream()
                .map(item -> item.workflowId()).toList());
        TenantContext.clear();
        assertEquals(0, service.list(7).total());
    }

    @Test
    void testOwnedContainerIsExcludedBeforeItsDraftIsLoaded() {
        admin.update("INSERT INTO nw_workflow_test_sessions VALUES(7,42)");
        admin.update("UPDATE nw_workflow_containers SET current_draft_revision=999 WHERE id=42");
        assertEquals(List.of("first-flow"),service.list(7).items().stream().map(item->item.workflowId()).toList());
    }

    @Test
    void pointerAdvanceAfterContainerReadDoesNotChangeTheCapturedDraftIdentity() {
        WorkflowContainerMapper observed = mock(WorkflowContainerMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(containers));
        doAnswer(invocation -> {
            var loaded = containers.selectList(invocation.getArgument(0));
            admin.update("UPDATE nw_workflow_containers SET current_draft_revision=3 WHERE id=41");
            return loaded;
        }).when(observed).selectList(any());

        var result = service(observed).list(7);

        assertEquals(2L, result.items().getFirst().revision());
        assertEquals("当前名称", result.items().getFirst().name());
        assertEquals(3L, admin.queryForObject(
                "SELECT current_draft_revision FROM nw_workflow_containers WHERE id=41", Long.class));
        verify(observed).selectList(any());
        verify(observed, never()).selectOne(any());
    }

    @Test
    void aMissingCurrentRevisionFailsWithTheOriginalStatusAndMessage() {
        admin.update("UPDATE nw_workflow_containers SET current_draft_revision=5 WHERE id=42");
        ApiException error = assertThrows(ApiException.class, () -> service.list(7));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatus());
        assertEquals("Workflow 草稿游标未找到对应 revision: second-flow", error.getMessage());
    }

    private static WorkflowDraftLifecycleService service(WorkflowContainerMapper containers) {
        return new WorkflowDraftLifecycleService(containers, drafts,
                mock(WorkflowDefinitionVersionMapper.class), new WorkflowDraftValidator(),
                mock(CapabilityProducerPublicationBridge.class));
    }

    private static void insertDraft(long id, long container, long tenant, long revision, String name) {
        admin.update("INSERT INTO nw_workflow_draft_revisions(id,container_id,tenant_id,revision,definition)"
                + " VALUES(?,?,?,?,jsonb_build_object('meta',jsonb_build_object('display_name',?::text)))",
                id, container, tenant, revision, name);
    }
}
