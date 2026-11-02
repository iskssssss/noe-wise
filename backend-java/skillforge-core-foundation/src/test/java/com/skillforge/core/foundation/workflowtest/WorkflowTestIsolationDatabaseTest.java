package com.skillforge.core.foundation.workflowtest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.jdbc.execution.ExecutionCommands;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.fixture.TestJdbcIdentityTransactions;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionMode;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseRequest;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLeaseResult;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWriteResult;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/** 完整正式迁移与受限 PostgreSQL 角色验证测试资源归属；不启动 Runtime 或外部调用。 */
@Testcontainers(disabledWithoutDocker = false)
class WorkflowTestIsolationDatabaseTest {
    private static final AtomicLong IDS = new AtomicLong(8_332_000_000_000L);
    private static final String HASH = "a".repeat(64);
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("workflow_test_isolation");
    private static JdbcTemplate owner;
    private static JdbcTemplate restricted;
    private static TransactionTemplate ownerWriter;
    private static TransactionTemplate writer;
    private static WorkflowTestIsolationMapper mapper;
    private Resources own;
    private Resources foreign;

    @BeforeAll static void database() throws Exception {
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        owner = new JdbcTemplate(admin);
        ownerWriter = new TransactionTemplate(new DataSourceTransactionManager(admin));
        owner.execute("CREATE ROLE workflow_test_reader LOGIN PASSWORD 'fixture-only-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO workflow_test_reader");
        // 特意授予 DELETE 以验证不可变触发器本身，不能以缺少权限冒充身份保护。
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO workflow_test_reader");
        var dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "workflow_test_reader", "fixture-only-password");
        restricted = new JdbcTemplate(dataSource);
        writer = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var configuration = new MybatisConfiguration(new Environment(
                "workflow-test-isolation", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new TenantRlsInterceptor());
        configuration.addMapper(WorkflowTestIsolationMapper.class);
        String resource = "mapper/foundation/WorkflowTestIsolationMapper.xml";
        try (var xml = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(WorkflowTestIsolationMapper.class);
    }

    @BeforeEach void resources() throws Exception {
        own = resources(id());
        foreign = resources(id());
    }

    @AfterEach void clearIdentity() {
        TenantContext.clear();
        ChannelContext.clear();
        ServicePrincipalContext.clear();
    }

    @Test void formalMapperPreservesFalseZeroAndNestedParametersAndFindsOnlyOwnedTestResources() {
        var row = own.row();
        assertEquals(1, tenant(own.tenant(), () -> mapper.insert(row)));
        var stored = tenant(own.tenant(), () -> mapper.byRequest(own.tenant(), own.member(), row.getRequestId()));
        assertEquals(row.getParameters(), stored.getParameters());
        assertEquals(0, stored.getParameters().get("count"));
        assertSame(Boolean.FALSE, stored.getParameters().get("enabled"));
        assertEquals(Map.of("items", List.of(0, false), "label", "中文参数"), stored.getParameters().get("nested"));
        assertEquals(own.testId(), tenant(own.tenant(), () -> mapper.bySession(own.tenant(), own.session()).getId()));
        assertEquals(own.testId(), tenant(own.tenant(), () -> mapper.byAgent(own.tenant(), own.agent()).getId()));
        assertEquals(own.testId(), tenant(own.tenant(), () -> mapper.byInstance(own.tenant(), own.instance()).getId()));
        var registry = new WorkflowTestRegistry(mapper);
        assertEquals(Set.of(own.agent()), tenant(own.tenant(), () -> registry.ownedAgentIds(
                own.tenant(), List.of(own.agent(), own.source().container()))));
        assertTrue(registry.ownedAgentIds(own.tenant(), List.of()).isEmpty());
        var access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,
                Long.toString(own.tenant()), Long.toString(own.member())));
        var isolation = new WorkflowTestIsolation(new WorkflowTestRegistry(mapper), access);
        tenant(own.tenant(), () -> {
            assertTrue(isolation.isolated(own.tenant(), own.session()));
            for (var resource : Map.of("agent", own.agent(), "workflow", own.copy().container(),
                    "workflow_instance", own.instance(), "capability", own.copy().capability(), "end_user", own.user()).entrySet()) {
                assertEquals(403, assertThrows(ApiException.class, () -> isolation.requireOrdinary(
                        own.tenant(), resource.getKey(), resource.getValue().toString())).getStatus().value());
            }
            assertDoesNotThrow(() -> isolation.requireOrdinary(own.tenant(), "workflow", Long.toString(own.source().container())));
            assertDoesNotThrow(() -> isolation.requireTarget(own.tenant(), own.session(), own.agent()));
            assertEquals(403, assertThrows(ApiException.class, () -> isolation.requireTarget(
                    own.tenant(), "ordinary-session", own.agent())).getStatus().value());
            return null;
        });
    }

    @Test void forcedRlsRejectsCrossTenantReadsAndWritesForBothReceiptAndExactBindings() throws Exception {
        assertTrue(owner.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='workflow_test_reader'", Boolean.class));
        assertEquals(2, owner.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('nw_workflow_test_sessions','nw_workflow_test_capability_bindings') AND relrowsecurity AND relforcerowsecurity", Integer.class));
        tenant(own.tenant(), () -> mapper.insert(own.row()));
        tenant(foreign.tenant(), () -> mapper.insert(foreign.row()));
        var ownTool = tool(own.tenant());
        var foreignTool = tool(foreign.tenant());
        tenant(own.tenant(), () -> mapper.insertBinding(binding(own, ownTool, "lookup")));
        tenant(foreign.tenant(), () -> mapper.insertBinding(binding(foreign, foreignTool, "lookup")));
        assertEquals(0, restricted.queryForObject("SELECT count(*) FROM nw_workflow_test_sessions", Integer.class));
        assertEquals(0, restricted.queryForObject("SELECT count(*) FROM nw_workflow_test_capability_bindings", Integer.class));
        tenant(own.tenant(), () -> {
            assertNull(mapper.bySession(foreign.tenant(), foreign.session()));
            assertNull(mapper.byRequest(foreign.tenant(), foreign.member(), foreign.row().getRequestId()));
            assertFalse(mapper.ownedResource(foreign.tenant(), "agent", Long.toString(foreign.agent())));
            assertTrue(new WorkflowTestRegistry(mapper).ownedAgentIds(foreign.tenant(), List.of(foreign.agent())).isEmpty());
            assertTrue(mapper.bindings(foreign.tenant(), foreign.testId()).isEmpty());
            assertEquals(1, mapper.bindings(own.tenant(), own.testId()).size());
            return null;
        });
        var anotherForeign = resources(foreign.tenant());
        sqlState("42501", () -> tenant(own.tenant(), () -> mapper.insert(anotherForeign.row())));
        sqlState("42501", () -> tenant(own.tenant(), () -> mapper.insertBinding(binding(foreign, foreignTool, "other"))));
    }

    @Test void requestIdentityIsUniqueWithinTenantAndInitiatingMemberButNotAcrossMembersOrTenants() throws Exception {
        var first = own.row();
        first.setRequestId("same-request");
        tenant(own.tenant(), () -> mapper.insert(first));
        var second = resources(own.tenant());
        var duplicate = second.row();
        duplicate.setRequestId(first.getRequestId());
        duplicate.setInitiatorMemberId(own.member());
        duplicate.setRequestFingerprint("b".repeat(64));
        sqlState("23505", () -> tenant(own.tenant(), () -> mapper.insert(duplicate)));
        assertEquals(HASH, tenant(own.tenant(), () -> mapper.byRequest(own.tenant(), own.member(), first.getRequestId())).getRequestFingerprint());
        duplicate.setInitiatorMemberId(second.member());
        assertEquals(1, tenant(own.tenant(), () -> mapper.insert(duplicate)));
        var elsewhere = foreign.row();
        elsewhere.setRequestId(first.getRequestId());
        assertEquals(1, tenant(foreign.tenant(), () -> mapper.insert(elsewhere)));
        // 另一个资源包使用独立 Agent/Session，只有终端用户复用，不能以会话 FK 拒绝冒充唯一性。
        var sharingUser = resources(own.tenant());
        String otherSession = "shared-test-user-" + sharingUser.testId();
        owner.update("INSERT INTO nw_chat_sessions(id,tenant_id,session_id,agent_id,end_user_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'native',?,?)",
                id(), own.tenant(), otherSession, sharingUser.agent(), own.user(),
                "member:" + sharingUser.member(), "member:" + sharingUser.member());
        var reusedUser = sharingUser.row();
        reusedUser.setSessionId(otherSession);
        reusedUser.setEndUserId(own.user());
        sqlState("23505", () -> tenant(own.tenant(), () -> mapper.insert(reusedUser)));
    }

    @Test void budgetsRejectZeroAndValuesAboveEveryApprovedLimitWithoutLeavingPartialReceipts() {
        List<Consumer<WorkflowTestSessionDO>> invalid = List.of(
                row -> row.setMaxBusinessModelCalls(0), row -> row.setMaxBusinessModelCalls(17),
                row -> row.setMaxToolCalls(0), row -> row.setMaxToolCalls(33),
                row -> row.setMaxKnowledgeCalls(0), row -> row.setMaxKnowledgeCalls(33),
                row -> row.setMaxTotalTokens(0L), row -> row.setMaxTotalTokens(64_001L),
                row -> row.setMaxWallTimeSeconds(0L), row -> row.setMaxWallTimeSeconds(301L));
        for (var mutation : invalid) {
            var row = own.row();
            mutation.accept(row);
            sqlState("23514", () -> tenant(own.tenant(), () -> mapper.insert(row)));
        }
        assertNull(tenant(own.tenant(), () -> mapper.bySession(own.tenant(), own.session())));
        assertEquals(1, tenant(own.tenant(), () -> mapper.insert(own.row())));
    }

    @Test void sourceSelectionRequiresAnExactDraftRevisionOrTheOriginalPublishedContainer() {
        var missingDraftRevision = own.row();
        missingDraftRevision.setSourceDraftRevision(null);
        sqlState("23514", () -> tenant(own.tenant(), () -> mapper.insert(missingDraftRevision)));
        var mixed = own.row();
        mixed.setSourceDefinitionVersionId(own.source().definition());
        sqlState("23514", () -> tenant(own.tenant(), () -> mapper.insert(mixed)));
        var publication = own.row();
        publication.setSourceKind("publication");
        publication.setSourceDraftRevision(null);
        publication.setSourceDefinitionVersionId(own.copy().definition());
        sqlState("23503", () -> tenant(own.tenant(), () -> mapper.insert(publication)));
        publication.setSourceDefinitionVersionId(own.source().definition());
        assertEquals(1, tenant(own.tenant(), () -> mapper.insert(publication)));
    }

    @Test void realForeignKeysRejectForeignMembersSourcesResourcesAndMismatchedLocalDefinition() {
        List<Consumer<WorkflowTestSessionDO>> invalid = List.of(
                row -> row.setInitiatorMemberId(foreign.member()),
                row -> row.setSourceContainerId(foreign.source().container()),
                row -> row.setContainerId(foreign.copy().container()),
                row -> row.setDefinitionVersionId(foreign.copy().definition()),
                row -> row.setCapabilityDefinitionId(foreign.copy().capability()),
                row -> row.setCapabilityVersionId(foreign.copy().version()),
                row -> row.setInstanceId(foreign.instance()),
                row -> row.setAgentId(foreign.agent()), row -> row.setEndUserId(foreign.user()),
                row -> row.setSessionId(foreign.session()),
                row -> row.setDefinitionVersionId(own.source().definition()),
                row -> row.setInstallationRevision(2L));
        for (var mutation : invalid) {
            var row = own.row();
            mutation.accept(row);
            sqlState("23503", () -> tenant(own.tenant(), () -> mapper.insert(row)));
        }
        assertEquals(1, tenant(own.tenant(), () -> mapper.insert(own.row())));
    }

    @Test void originalReceiptAndBindingsCannotBeEditedOrDeletedEvenWithTableMutationPrivileges() {
        tenant(own.tenant(), () -> mapper.insert(own.row()));
        for (String change : List.of("request_id='changed'", "agent_id=" + foreign.agent(), "parameters='{}'::jsonb", "source_hash='" + "b".repeat(64) + "'",
                "max_total_tokens=32000", "expires_at=expires_at+interval '1 day'")) {
            sqlState("P0001", () -> tenantSql(own.tenant(), () -> restricted.update(
                    "UPDATE nw_workflow_test_sessions SET " + change + " WHERE id=?", own.testId())));
        }
        sqlState("P0001", () -> tenantSql(own.tenant(), () -> restricted.update("DELETE FROM nw_workflow_test_sessions WHERE id=?", own.testId())));
        var tool = tool(own.tenant());
        var exact = binding(own, tool, "lookup");
        tenant(own.tenant(), () -> mapper.insertBinding(exact));
        sqlState("P0001", () -> tenantSql(own.tenant(), () -> restricted.update(
                "UPDATE nw_workflow_test_capability_bindings SET tool_deployment_revision=2 WHERE tenant_id=? AND test_session_id=?", own.tenant(), own.testId())));
        sqlState("P0001", () -> tenantSql(own.tenant(), () -> restricted.update(
                "DELETE FROM nw_workflow_test_capability_bindings WHERE tenant_id=? AND test_session_id=?", own.tenant(), own.testId())));
        assertEquals(List.of(exact), tenant(own.tenant(), () -> mapper.bindings(own.tenant(), own.testId())));
        var stored = tenant(own.tenant(), () -> mapper.bySession(own.tenant(), own.session()));
        assertEquals(own.row().getParameters(), stored.getParameters());
        assertEquals(own.row().getExpiresAt(), stored.getExpiresAt());
    }

    @Test void exactBindingsRejectForeignDeploymentsWrongVersionsAndMissingPositiveRevision() {
        tenant(own.tenant(), () -> mapper.insert(own.row()));
        var local = tool(own.tenant());
        var another = tool(own.tenant());
        var remote = tool(foreign.tenant());
        sqlState("23503", () -> tenant(own.tenant(), () -> mapper.insertBinding(binding(own, remote, "foreign"))));
        var wrongVersion = new WorkflowTestIsolationMapper.Binding(own.tenant(), own.testId(), "wrong-version", "TOOL",
                local.capability(), another.version(), local.deployment(), local.key(), 1L, null);
        sqlState("23503", () -> tenant(own.tenant(), () -> mapper.insertBinding(wrongVersion)));
        var missingRevision = new WorkflowTestIsolationMapper.Binding(own.tenant(), own.testId(), "missing-revision", "TOOL",
                local.capability(), local.version(), local.deployment(), local.key(), null, null);
        sqlState("23514", () -> tenant(own.tenant(), () -> mapper.insertBinding(missingRevision)));
        assertEquals(1, tenant(own.tenant(), () -> mapper.insertBinding(binding(own, local, "lookup"))));
        sqlState("23505", () -> tenant(own.tenant(), () -> mapper.insertBinding(binding(own, another, "lookup"))));
    }

    @Test void exactKnowledgeBindingsUseBusinessRevisionWithTheirPublishedCapabilityVersion() {
        tenant(own.tenant(), () -> mapper.insert(own.row()));
        var first = knowledge(own.tenant(), 7);
        var otherVersion = knowledge(own.tenant(), 9);
        var foreignVersion = knowledge(foreign.tenant(), 7);
        assertNotEquals(first.id(), first.revision());
        // 行主键不是知识业务修订号；每个错误只改变精确引用元组的一项。
        sqlState("23503", () -> tenant(own.tenant(), () -> mapper.insertBinding(
                knowledgeBinding(own, first, first.id(), "row-id-is-not-revision"))));
        sqlState("23503", () -> tenant(own.tenant(), () -> mapper.insertBinding(
                knowledgeBinding(own, first, 8, "missing-revision"))));
        sqlState("23503", () -> tenant(own.tenant(), () -> mapper.insertBinding(
                knowledgeBinding(own, otherVersion, first.revision(), "wrong-version"))));
        sqlState("23503", () -> tenant(own.tenant(), () -> mapper.insertBinding(
                knowledgeBinding(own, foreignVersion, foreignVersion.revision(), "foreign-knowledge"))));
        var exact = knowledgeBinding(own, first, first.revision(), "knowledge");
        assertEquals(1, tenant(own.tenant(), () -> mapper.insertBinding(exact)));
        assertEquals(List.of(exact), tenant(own.tenant(), () -> mapper.bindings(own.tenant(), own.testId())));
        assertEquals(7L, tenant(own.tenant(), () -> mapper.bindings(own.tenant(), own.testId())).getFirst().knowledgeRevision());
    }

    @Test void closingAndClosedUseExpectedStateAndExpiryReadsKeepUnfinishedCleanupVisible() throws Exception {
        tenant(own.tenant(), () -> mapper.insert(own.row()));
        assertEquals(0, tenant(own.tenant(), () -> mapper.closed(own.tenant(), own.testId())));
        assertEquals(1, tenant(own.tenant(), () -> mapper.closing(own.tenant(), own.testId())));
        assertEquals(0, tenant(own.tenant(), () -> mapper.closing(own.tenant(), own.testId())));
        var registry = new WorkflowTestRegistry(mapper);
        var closing = tenant(own.tenant(), () -> registry.bySession(own.tenant(), own.session()));
        assertEquals("closing", closing.getStatus());
        var isolation = new WorkflowTestIsolation(registry, mock(FoundationAccess.class));
        assertEquals(409, assertThrows(ApiException.class, () -> isolation.requireLive(closing)).getStatus().value());
        assertTrue(tenant(own.tenant(), () -> mapper.expiring(own.tenant())).stream().anyMatch(row -> row.getId() == own.testId()));
        assertEquals(1, tenant(own.tenant(), () -> mapper.closed(own.tenant(), own.testId())));
        var closed = tenant(own.tenant(), () -> registry.bySession(own.tenant(), own.session()));
        assertEquals("closed", closed.getStatus());
        assertNotNull(closed.getClosedAt());
        assertEquals(409, assertThrows(ApiException.class, () -> isolation.requireLive(closed)).getStatus().value());
        assertEquals(0, tenant(own.tenant(), () -> mapper.closed(own.tenant(), own.testId())));
        assertEquals(0, tenant(own.tenant(), () -> mapper.closing(own.tenant(), own.testId())));
        assertFalse(tenant(own.tenant(), () -> mapper.expiring(own.tenant())).stream().anyMatch(row -> row.getId() == own.testId()));
        var expired = resources(own.tenant());
        var old = expired.row();
        old.setExpiresAt(Instant.parse("2021-01-01T00:00:00Z"));
        tenant(own.tenant(), () -> mapper.insert(old));
        var expiredSnapshot = tenant(own.tenant(), () -> registry.bySession(own.tenant(), expired.session()));
        assertEquals(409, assertThrows(ApiException.class, () -> isolation.requireLive(expiredSnapshot)).getStatus().value());
        assertTrue(tenant(own.tenant(), () -> mapper.expiring(own.tenant())).stream().anyMatch(row -> row.getId() == expired.testId()));
    }

    @Test void quietRequiresFinishedRunsResolvedAttemptsAndTerminalWorkflowExecutions() throws Exception {
        assertTrue(tenant(own.tenant(), () -> mapper.quiet(own.tenant(), own.session())));
        runtimeRun(own, "RUNNING", "READY");
        assertFalse(tenant(own.tenant(), () -> mapper.quiet(own.tenant(), own.session())));
        for (String result : List.of("PENDING", "UNKNOWN", "SUCCEEDED")) {
            var resource = resources(own.tenant());
            runtimeRun(resource, "COMPLETED", "FINISHED");
            runtimeAttempt(resource, result);
            assertEquals("SUCCEEDED".equals(result), tenant(resource.tenant(), () -> mapper.quiet(resource.tenant(), resource.session())), result);
        }
        for (String state : List.of("RUNNING", "COMPLETED")) {
            var resource = resources(own.tenant());
            runtimeRun(resource, "CANCELLED", "FINISHED");
            workflowExecution(resource, state);
            assertEquals("COMPLETED".equals(state), tenant(resource.tenant(), () -> mapper.quiet(resource.tenant(), resource.session())), state);
        }
        for (var known : List.of(ExecutionStore.ResultKind.SUCCEEDED, ExecutionStore.ResultKind.FAILED)) {
            reconcileUnknownThroughStore(resources(own.tenant()), known);
        }
    }

    private static <T> T tenant(long tenant, Supplier<T> work) {
        return TenantContext.callWithTenant(Long.toString(tenant), () -> writer.execute(status -> work.get()));
    }

    private static <T> T tenantSql(long tenant, Supplier<T> work) {
        return writer.execute(status -> {
            restricted.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, Long.toString(tenant));
            return work.get();
        });
    }

    private static void sqlState(String expected, Runnable operation) {
        Throwable failure = assertThrows(RuntimeException.class, operation::run);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                assertEquals(expected, sql.getSQLState(), failure::toString);
                return;
            }
        }
        fail("预期 PostgreSQL 拒绝，实际未找到 SQLState", failure);
    }

    private static long id() { return IDS.incrementAndGet(); }

    /** 每个场景拥有独立身份；所有业务表来自正式迁移，不创建影子表或依赖用例顺序。 */
    private static Resources resources(long tenant) throws Exception {
        long member = id(), agent = id(), user = id(), sessionRow = id(), test = id(), instance = id();
        try (var connection = owner.getDataSource().getConnection()) {
            CurrentHostFixtures.tenant(connection, tenant);
            CurrentHostFixtures.agent(connection, tenant, agent);
            CurrentHostFixtures.endUser(connection, tenant, user);
        }
        owner.update("INSERT INTO nw_tenant_members(id,tenant_id,username,role,status) VALUES(?,?,?,'developer','active')", member, tenant, "workflow-test-" + member);
        var source = workflow(tenant);
        var copy = workflow(tenant);
        owner.update("INSERT INTO nw_workflow_instances(id,tenant_id,instance_id,definition_version_id,status) VALUES(?,?,?,?,'draft')", instance, tenant, "test-instance-" + instance, copy.definition());
        owner.update("INSERT INTO nw_workflow_installation_revisions(tenant_id,instance_id,installation_revision,definition_version_id,config,custom_prompt,bindings,created_at) VALUES(?,?,1,?,'{}','','{}',clock_timestamp())", tenant, instance, copy.definition());
        owner.update("UPDATE nw_workflow_instances SET installation_revision=1,status='published' WHERE id=?", instance);
        String session = "workflow-test-" + sessionRow;
        owner.update("INSERT INTO nw_chat_sessions(id,tenant_id,session_id,agent_id,end_user_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'native',?,?)", sessionRow, tenant, session, agent, user, "member:" + member, "member:" + member);
        return new Resources(tenant, member, agent, user, session, test, instance, source, copy);
    }

    private static Workflow workflow(long tenant) {
        var capability = capability(tenant, "WORKFLOW");
        long container = id(), draft = id(), definition = id();
        owner.update("INSERT INTO nw_workflow_containers(id,tenant_id,workflow_id) VALUES(?,?,?)", container, tenant, "workflow-test-" + container);
        owner.update("INSERT INTO nw_workflow_draft_revisions(id,tenant_id,container_id,revision,definition) VALUES(?,?,?,1,'{}')", draft, tenant, container);
        owner.update("INSERT INTO nw_workflow_definition_versions(id,tenant_id,container_id,source_draft_id,definition_version,definition,capability_version_id) VALUES(?,?,?,?,1,'{}',?)", definition, tenant, container, draft, capability.version());
        return new Workflow(container, definition, capability.capability(), capability.version());
    }

    private static Capability capability(long tenant, String type) {
        long definition = id(), version = id();
        owner.update("INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,capability_key,name,trust_level,status,created_at,updated_at) VALUES(?,?,?,?,?,'TENANT','PUBLISHED',now(),now())", definition, tenant, type, "workflow-test-" + definition, "测试能力");
        owner.update("INSERT INTO nw_capability_versions(id,tenant_id,definition_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at,published_at) VALUES(?,?,?,?,'v1','测试能力','TENANT','{}',?,'PUBLISHED',now(),now())", version, tenant, definition, type, HASH);
        return new Capability(definition, version);
    }

    private static Tool tool(long tenant) {
        var capability = capability(tenant, "TOOL");
        long deployment = id();
        String key = "workflow-test-tool-" + deployment;
        owner.update("INSERT INTO nw_tool_deployments(id,tenant_id,deployment_key,tool_version_id,provider_type,provider_ref,environment,status,created_at,updated_at) VALUES(?,?,?,?,'TEST_MOCK','fixture-only','TEST','ACTIVE',now(),now())", deployment, tenant, key, capability.version());
        return new Tool(capability.capability(), capability.version(), deployment, key);
    }

    private static WorkflowTestIsolationMapper.Binding binding(Resources resource, Tool tool, String slot) {
        return new WorkflowTestIsolationMapper.Binding(resource.tenant(), resource.testId(), slot, "TOOL",
                tool.capability(), tool.version(), tool.deployment(), tool.key(), 1L, null);
    }

    private static Knowledge knowledge(long tenant, long revision) {
        var capability = capability(tenant, "KNOWLEDGE_SOURCE");
        long base = id(), identity = id();
        owner.update("INSERT INTO nw_knowledge_bases(id,tenant_id,name,indexing_mode) VALUES(?,?,?,'economical')",
                base, tenant, "测试知识库-" + base);
        owner.update("""
                INSERT INTO nw_knowledge_base_revisions(id,tenant_id,kb_id,revision,status,capability_version_id,
                  capability_policy,ready_at,published_at)
                VALUES(?,?,?,?,'published',?,
                  '{"model_invocable":true,"user_invocable":true,"allowed_operators":["KEYWORD"],"freshness_policy":"PINNED","citation_required":true}',
                  now(),now())
                """, identity, tenant, base, revision, capability.version());
        return new Knowledge(identity, revision, capability);
    }

    private static WorkflowTestIsolationMapper.Binding knowledgeBinding(Resources resource, Knowledge knowledge,
                                                                       long revision, String slot) {
        return new WorkflowTestIsolationMapper.Binding(resource.tenant(), resource.testId(), slot, "KNOWLEDGE_SOURCE",
                knowledge.capability().capability(), knowledge.capability().version(), null, null, null, revision);
    }

    /** 最小合法执行事实只供 quiet 查询；不模拟调度算法，也不派发任何外部动作。 */
    private static void runtimeRun(Resources resource, String status, String phase) {
        String tenant = Long.toString(resource.tenant()), agent = Long.toString(resource.agent()), run = run(resource);
        owner.update("INSERT INTO agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,end_user_id) VALUES(?,'AGENT',?,?,?)", tenant, agent, resource.session(), resource.user());
        owner.update("INSERT INTO agent_runtime_execution_sessions(tenant_id,session_id,target_kind,target_id,end_user_id) VALUES(?,?,'AGENT',?,?)", tenant, resource.session(), agent, resource.user());
        material(tenant, run + ":input");
        owner.update("""
                INSERT INTO agent_runtime_execution_runs(tenant_id,run_id,session_id,target_kind,target_id,end_user_id,agent_id,
                  input_id,trace_id,span_id,root_input_id,identity_ref,actor_verification_ref,configuration_ref,input_ref,
                  initial_context_ref,admission_fingerprint,status,phase,revision,context_ref,created_second,created_nano,
                  updated_second,updated_nano,requester_kind,requester_end_user_id,origin_ref)
                VALUES(?,?,?,'AGENT',?,?,?,'input','trace','span','input','identity','verification','configuration',
                  ?,?,'fingerprint',?,?,1,?,1,0,1,0,'END_USER',?,?)
                """, tenant, run, resource.session(), agent, resource.user(), agent, run + ":input", run + ":input",
                status, phase, run + ":input", resource.user(), run + ":input");
    }

    private static void runtimeAttempt(Resources resource, String result) {
        ownerWriter.executeWithoutResult(status -> {
            String tenant = Long.toString(resource.tenant()), run = run(resource), attempt = run + ":attempt", action = run + ":action";
            owner.update("INSERT INTO agent_runtime_budget_roots(tenant_id,root_id,scenario,source_version,active_limit_seconds) VALUES(?,?,'STANDARD','fixture',60)", tenant, run);
            owner.update("INSERT INTO agent_runtime_budget_runs(tenant_id,run_id,root_id,role,source_version,active_limit_seconds) VALUES(?, ?,?,'PRIMARY','fixture',60)", tenant, run, run);
            owner.update("INSERT INTO agent_runtime_budget_attempts(tenant_id,attempt_id,run_id,fingerprint,category,closing,state) VALUES(?,?,?,'fingerprint','TOOL',false,'SENT')", tenant, attempt, run);
            owner.update("INSERT INTO agent_runtime_execution_actions(tenant_id,action_id,run_id,action_ordinal,kind,target_handle,capability_ref,request_ref,fingerprint,status,current_attempt) VALUES(?,?,?,1,'TOOL','fixture','fixture',?,'fingerprint','DISPATCHED',?)", tenant, action, run, run + ":input", attempt);
            boolean pending = "PENDING".equals(result);
            if (!pending) material(tenant, run + ":result");
            owner.update("INSERT INTO agent_runtime_execution_attempts(tenant_id,attempt_id,action_id,run_id,fence,request_ref,fingerprint,result_kind,result_ref,uncertainty_ref) VALUES(?,?,?,?,1,?,'fingerprint',?,?,NULL)", tenant, attempt, action, run, run + ":input", pending ? null : result, pending ? null : run + ":result");
        });
    }

    /** 正式受理和原 Attempt 补回执，不派发供应商；取消后仅补事实，不重新开启 Run。 */
    private static void reconcileUnknownThroughStore(Resources resource, ExecutionStore.ResultKind known) {
        var test = resource.row();
        tenant(resource.tenant(), () -> mapper.insert(test));
        var tenant = new TenantId(Long.toString(resource.tenant()));
        var agent = new AgentId(Long.toString(resource.agent()));
        var user = new EndUserId(resource.user());
        var session = SessionOwner.user(new SessionRef(tenant, resource.session()), agent, user);
        var reference = new RunRef(tenant, run(resource));
        var input = new InputId(test.getInitialInputId());
        var caller = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, user), session,
                Set.of("sessions.write"), 1, new ResultRef("verified-" + resource.testId()));
        var identity = new RunIdentity(session, agent, input, reference,
                new TraceEnvelope("trace", "span", input, Optional.empty()), caller.verificationRef(),
                new RunRequester.EndUser(user), Optional.empty(), new RunOriginRef(tenant, input.value()));
        var host = new TestJdbcIdentityTransactions();
        host.register(caller);
        var store = new JdbcExecutionStore(owner.getDataSource(), host, (sql, run, actor, prepared) -> true);
        var capacity = new ExecutionBudgetPort.Resources(3, 3, 3, 3, 1024, 1024, 2048);
        var registration = new ExecutionBudgetPort.Registration(reference, reference, ExecutionBudgetPort.Role.PRIMARY,
                new ExecutionBudgetPort.Policy.Enforced(new ExecutionBudgetPort.Limits(
                        ExecutionBudgetPort.Scenario.STANDARD, capacity, capacity, capacity, 60, 60, "workflow-test:v1")));
        var admitted = store.admit(new ExecutionStore.Admission(caller, identity, HASH,
                new ResultRef("configuration"), storeMaterial(input.value()), storeMaterial(reference.runId() + ":context"),
                registration, List.of(), List.of()));
        assertTrue(admitted.created());
        var claimed = store.claim(new ExecutionStore.Claim(reference, "quiet-fixture", "claim-" + resource.testId(), Duration.ofSeconds(30))).orElseThrow();
        String attempt = reference.runId() + ":attempt";
        var prepared = new ExecutionStore.Prepared(new ActionRef(tenant, reference.runId() + ":action"), 1,
                ExecutionStore.Kind.TOOL, "fixture-only", new ResultRef("capability"), storeMaterial(attempt + ":request"), HASH);
        var dispatched = assertInstanceOf(ExecutionStore.DispatchResult.Registered.class, store.dispatch(
                new ExecutionStore.Dispatch(reference, claimed.revision(), claimed.lease().orElseThrow(), prepared, attempt,
                        new ExecutionBudgetPort.Demand(reference, attempt, HASH, ExecutionBudgetPort.Category.TOOL,
                                ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false),
                        caller, Optional.empty(), List.of())));
        assertTrue(store.authorizeSend(dispatched, prepared));
        var context = storeMaterial(attempt + ":feedback-context");
        var unknown = store.complete(storeCompletion(dispatched, ExecutionStore.ResultKind.UNKNOWN,
                storeMaterial(attempt + ":unknown"), context));
        assertTrue(unknown.newlySaved());
        assertTrue(unknown.advanced());
        assertEquals(ExecutionStore.Phase.UNCERTAIN, unknown.run().phase());
        assertEquals(RunStatus.SUSPENDED, unknown.run().status());
        assertEquals(Optional.of(ExecutionStore.ResultKind.UNKNOWN), unknown.attempt().resultKind());
        assertFalse(tenant(resource.tenant(), () -> mapper.quiet(resource.tenant(), resource.session())));
        var cancelled = store.cancel(new ExecutionStore.Cancel(caller, reference, new InputId("cancel-" + resource.testId()), List.of()));
        assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertEquals(ExecutionStore.Phase.FINISHED, cancelled.phase());
        assertFalse(tenant(resource.tenant(), () -> mapper.quiet(resource.tenant(), resource.session())));
        var resolved = store.complete(storeCompletion(dispatched, known, storeMaterial(attempt + ":known"), context));
        assertTrue(resolved.newlySaved());
        assertFalse(resolved.advanced());
        assertEquals(RunStatus.CANCELLED, resolved.run().status());
        assertEquals(ExecutionStore.Phase.FINISHED, resolved.run().phase());
        assertEquals(Optional.of(known), resolved.attempt().resultKind());
        assertEquals(unknown.attempt().result(), resolved.attempt().uncertainty());
        assertTrue(store.material(tenant, resolved.attempt().uncertainty().orElseThrow()).isPresent());
        assertFalse(store.authorizeSend(dispatched, prepared));
        assertTrue(tenant(resource.tenant(), () -> mapper.quiet(resource.tenant(), resource.session())));
    }

    private static ExecutionStore.Completion storeCompletion(ExecutionStore.DispatchResult.Registered original,
                                                            ExecutionStore.ResultKind kind, ExecutionStore.Material result,
                                                            ExecutionStore.Material context) {
        var run = original.run();
        return new ExecutionStore.Completion(run.identity().runRef(), original.attempt().id(), kind, result,
                Optional.empty(), Optional.of("fixture-operation:" + original.attempt().id()),
                kind == ExecutionStore.ResultKind.UNKNOWN ? ExecutionBudgetPort.Usage.unreported()
                        : ExecutionBudgetPort.Usage.reported(OptionalLong.of(0), OptionalLong.of(0)),
                run.revision(), run.lease().orElseThrow(), new ExecutionStore.Progress(0, 1, 0, context.reference()),
                context, List.of(), Optional.empty());
    }

    private static ExecutionStore.Material storeMaterial(String reference) {
        return new ExecutionStore.Material(new ResultRef(reference), new TypedPayload("application/json", "{}"));
    }

    private static void workflowExecution(Resources resource, String state) {
        var tenant = new TenantId(Long.toString(resource.tenant()));
        var agent = new AgentId(Long.toString(resource.agent()));
        var user = new EndUserId(resource.user());
        var session = SessionOwner.user(new SessionRef(tenant, resource.session()), agent, user);
        var verification = new ResultRef("verification");
        var input = new InputId("input");
        var identity = new RunIdentity(session, agent, input, new RunRef(tenant, run(resource)),
                new TraceEnvelope("trace", "span", input, Optional.empty()), verification,
                new RunRequester.EndUser(user), Optional.empty(), new RunOriginRef(tenant, run(resource) + ":input"));
        var caller = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, user), session,
                Set.of("workflow.execute"), 1, verification);
        var reference = new ExecutionRef(tenant, run(resource) + ":workflow");
        var checkpoint = new TypedPayload("application/json", "{}");
        var ledger = new JdbcExecutionLedgerPort(owner.getDataSource());
        // 正式 Port 自己提交 CREATE/TRANSITION journal、完整 record codec 与状态 publication。
        assertInstanceOf(ExecutionWriteResult.Applied.class, ledger.create(ExecutionCommands.create(
                reference, identity, Long.toString(resource.instance()), Long.toString(resource.copy().definition()),
                ExecutionMode.FOREGROUND, Optional.empty(), Optional.empty(), checkpoint, HASH,
                new EventId(reference.executionId() + ":queued"), Instant.now())));
        var acquired = assertInstanceOf(ExecutionLeaseResult.Acquired.class, ledger.acquireLease(
                new ExecutionLeaseRequest(caller, reference, "quiet-fixture", Duration.ofSeconds(30), Instant.now())));
        try {
            var running = assertInstanceOf(ExecutionWriteResult.Applied.class, ledger.transition(
                    ExecutionCommands.transition(acquired.execution(), acquired.lease(), WorkflowExecutionState.RUNNING,
                            checkpoint, Optional.empty(), Optional.empty(), Optional.empty(),
                            new EventId(reference.executionId() + ":started"), Instant.now()))).execution();
            if ("COMPLETED".equals(state)) {
                var completed = assertInstanceOf(ExecutionWriteResult.Applied.class, ledger.transition(
                        ExecutionCommands.transition(running, acquired.lease(), WorkflowExecutionState.COMPLETED,
                                checkpoint, Optional.empty(), Optional.of(new ResultRef("completed-result")), Optional.empty(),
                                new EventId(reference.executionId() + ":completed"), Instant.now()))).execution();
                assertEquals(WorkflowExecutionState.COMPLETED, completed.state());
                assertTrue(completed.lease().isEmpty());
            } else {
                assertEquals("RUNNING", state);
                assertEquals(WorkflowExecutionState.RUNNING, running.state());
            }
        } finally {
            ledger.release(acquired.lease());
        }
    }

    private static void material(String tenant, String reference) {
        owner.update("INSERT INTO agent_runtime_execution_materials(tenant_id,reference,schema_id,canonical_json) VALUES(?,?,'application/json','{}')", tenant, reference);
    }

    private static String run(Resources resource) { return "workflow-test-run-" + resource.testId(); }
    private record Capability(long capability, long version) { }
    private record Workflow(long container, long definition, long capability, long version) { }
    private record Tool(long capability, long version, long deployment, String key) { }
    private record Knowledge(long id, long revision, Capability capability) { }
    private record Resources(long tenant, long member, long agent, long user, String session, long testId,
                             long instance, Workflow source, Workflow copy) {
        WorkflowTestSessionDO row() {
            var row = new WorkflowTestSessionDO();
            row.setId(testId); row.setTenantId(tenant); row.setInitiatorMemberId(member);
            row.setRequestId("request-" + testId); row.setRequestFingerprint(HASH);
            row.setSourceKind("draft"); row.setSourceContainerId(source.container());
            row.setSourceWorkflowId("workflow-test-" + source.container()); row.setSourceDraftRevision(1L);
            row.setSourceHash(HASH); row.setContainerId(copy.container()); row.setDefinitionVersionId(copy.definition());
            row.setCapabilityDefinitionId(copy.capability()); row.setCapabilityVersionId(copy.version());
            row.setInstanceId(instance); row.setInstallationRevision(1L); row.setAgentId(agent); row.setEndUserId(user);
            row.setSessionId(session); row.setInitialInputId("workflow-test-input-" + testId); row.setInitialMessage("执行本次测试");
            row.setParameters(Map.of("count", 0, "enabled", false, "nested", Map.of("items", List.of(0, false), "label", "中文参数")));
            row.setMaxBusinessModelCalls(3); row.setMaxToolCalls(3); row.setMaxKnowledgeCalls(3);
            row.setMaxTotalTokens(64_000L); row.setMaxWallTimeSeconds(300L); row.setStatus("ready");
            row.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z")); row.setUpdatedAt(row.getCreatedAt());
            row.setExpiresAt(Instant.parse("2099-01-01T00:00:00Z"));
            return row;
        }
    }
}
