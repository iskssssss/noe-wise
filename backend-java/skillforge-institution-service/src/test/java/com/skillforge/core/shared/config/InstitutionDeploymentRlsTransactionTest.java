package com.skillforge.core.shared.config;

import com.skillforge.core.foundation.config.TenantRlsInterceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityCatalogContextMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationAccessMapper;
import com.skillforge.core.foundation.access.FoundationPermissionCatalogue;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementMapper;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService;
import com.skillforge.core.foundation.institution.InstitutionRelationMapper;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.chat.persistence.ChatMessageMapper;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordDO;
import com.skillforge.core.foundation.application.AuditService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 正式管理服务签发目标证明，实际Spring/MyBatis/受限PG核验部署窗口及所有者影响读取。 */
@Testcontainers(disabledWithoutDocker = false)
class InstitutionDeploymentRlsTransactionTest {
    private static final String CREATE = "tenant.agent_deployments.create";
    private static final String READ = "tenant.agent_deployments.read";
    private static final String DEPLOYMENTS = "com.skillforge.core.agent.mapper.AgentDeploymentMapper.";
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("institution_deployment_rls").withUsername("skillforge").withPassword("isolated-test-password");
    private static JdbcTemplate owner;
    private static HikariDataSource source;
    private static SqlSessionTemplate sessions;
    private static SqlSessionTemplate otherSessions;
    private static DataSourceTransactionManager transactions;
    private static TransactionTemplate writer;
    private JdbcTemplate jdbc;
    private FoundationAccess foundation;
    private FoundationAccess.Subject subject;
    private InstitutionDeploymentManagementService management;
    private ToolDeploymentRecordMapper tools;
    private long headquarters, target, third, relation, grant, toolVersion, definition, targetAgentDeployment;
    private long headquartersTool, targetTool;

    /** 新隔离库迁移至创建请求回执版本V348；机构连接不使用迁移所有者或BYPASSRLS。 */
    @BeforeAll static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").target("348").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE institution_deployment_reader LOGIN PASSWORD 'isolated-restricted-password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO institution_deployment_reader");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO institution_deployment_reader");
        var config = new HikariConfig();
        config.setJdbcUrl(DATABASE.getJdbcUrl()); config.setUsername("institution_deployment_reader");
        config.setPassword("isolated-restricted-password"); config.setMaximumPoolSize(2); config.setMinimumIdle(0);
        config.setConnectionInitSql("SELECT set_config('app.platform_admin','false',false)");
        source = new HikariDataSource(config);
        transactions = new DataSourceTransactionManager(source); writer = new TransactionTemplate(transactions);
        sessions = sessions(source); otherSessions = sessions(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
            }
            try (var rows = statement.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='nw_tool_deployments'::regclass")) {
                assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
            }
            statement.execute("SELECT set_config('app.platform_admin','false',false)");
        }
    }

    /** 两个SqlSessionFactory共享正式Spring连接，另建工厂用于真实一级缓存恢复检查。 */
    private static SqlSessionTemplate sessions(javax.sql.DataSource dataSource) throws Exception {
        var config = new MybatisConfiguration(new Environment("institution-deployment-rls", new SpringManagedTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true); config.setArgNameBasedConstructorAutoMapping(true);
        config.addInterceptor(new TenantRlsInterceptor(java.util.List.of(new com.skillforge.core.foundation.access.FoundationAuthorizedSqlRule(), new com.skillforge.core.agent.AgentAuthorizedSqlRule(), new com.skillforge.core.capability.CapabilityAuthorizedSqlRule())));
        for (var type : List.of(ToolDeploymentRecordMapper.class, SessionMapper.class, ChatMessageMapper.class, com.skillforge.core.foundation.persistence.TenantMapper.class)) config.addMapper(type);
        for (String resource : List.of("mapper/InstitutionDeploymentManagementMapper.xml", "mapper/InstitutionRelationMapper.xml",
                "mapper/CapabilityCatalogContextMapper.xml")) {
            try (var xml = new ClassPathResource(resource).getInputStream()) {
                new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
            }
        }
        // core-shared不反向依赖core-agent；读取官方SQL，仅将跨模块返回DTO改为Map，不替换SQL或语句身份。
        loadSelects(config, Files.readString(repositoryFile("backend-java/skillforge-core-agent/src/main/resources/mapper/AgentDeploymentMapper.xml")),
                Set.of("databaseContext", "restoreDatabaseContext", "impact", "findExternal", "findCreateResponse"));
        for (String resource : List.of("mapper/AgentRuntimeExecutionStoreMapper.xml", "mapper/AgentRuntimeMemoryMapper.xml")) {
            var input = new ClassPathResource(resource);
            loadSelects(config, input.getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                    Set.of("now"));
        }
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    }

    /** 提取官方SELECT及所需列片段；保留原SQL、参数与缓存设置，避免镜像业务查询。 */
    private static void loadSelects(MybatisConfiguration config, String original, Set<String> names) {
        var namespace = Pattern.compile("<mapper namespace=\"([^\"]+)\"").matcher(original);
        assertTrue(namespace.find()); String name = namespace.group(1);
        var statements = Pattern.compile("<select\\s+id=\"([^\"]+)\"[^>]*>.*?</select>", Pattern.DOTALL).matcher(original);
        var selected = new ArrayList<String>();
        while (statements.find()) if (names.contains(statements.group(1)))
            selected.add(statements.group().replaceFirst("resultType=\"[^\"]+\"", "resultType=\"map\""));
        assertEquals(names.size(), selected.size(), "官方Mapper语句必须仍存在");
        String fragments = "";
        if (names.contains("findExternal")) {
            var columns = Pattern.compile("<sql id=\"columns\">.*?</sql>", Pattern.DOTALL).matcher(original);
            assertTrue(columns.find());
            fragments = columns.group();
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                + "<mapper namespace=\"" + name + "\">" + fragments + String.join("\n", selected) + "</mapper>";
        new XMLMapperBuilder(new StringReader(xml), config, "rls-official-fragment:" + name, config.getSqlFragments()).parse();
    }

    /** 从模块或仓库工作目录定位正式源文件，不复制另一模块的Java类型。 */
    private static Path repositoryFile(String relative) {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path candidate = current.resolve(relative); if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("official_mapper_source_missing:" + relative);
    }

    /** 仅夹具使用迁移所有者置入正式关系/许可；窗口内全部SQL使用受限角色。 */
    @BeforeEach void fixture() {
        jdbc = new JdbcTemplate(source); headquarters = tenant(null); target = tenant(headquarters); third = tenant(headquarters);
        relation = relation(target); long otherRelation = relation(third);
        grant = managementGrant(target, relation, CREATE); managementGrant(target, relation, READ); managementGrant(third, otherRelation, READ);
        toolVersion = publishedTool(); headquartersTool = seedTool(headquarters, "hq-original"); targetTool = seedTool(target, "target-original");
        definition = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_agent_templates(id,template_key,name,version,definition,status,published_at,distribution_policy,owner_kind,owner_tenant_id) VALUES(?,?,?,'1.0.0','{}','published',now(),'AUTHORIZED_DEPLOY','tenant',?)",
                definition, "rls-source-" + definition, "RLS source", headquarters);
        targetAgentDeployment = seedAgentDeployment(target, relation); seedAgentDeployment(third, otherRelation);
        subject = new FoundationAccess.Subject(false, Long.toString(headquarters), "1");
        foundation = spy(new FoundationAccess(mock(FoundationAccessMapper.class), mock(FoundationPermissionCatalogue.class), transactions));
        doReturn(new FoundationAccess.Snapshot(List.of("fixture"), List.of(CREATE, READ), 1)).when(foundation).snapshot(any());
        doNothing().when(foundation).require(any(FoundationAccess.Subject.class), anyString());
        management = new InstitutionDeploymentManagementService(sessions.getMapper(InstitutionDeploymentManagementMapper.class),
                sessions.getMapper(InstitutionRelationMapper.class), mock(PlatformManagementService.class), foundation,
                mock(InstitutionIntegrationService.class), mock(AuditService.class), new ObjectMapper());
        tools = sessions.getMapper(ToolDeploymentRecordMapper.class);
    }

    @AfterEach void clear() {
        assertNull(InstitutionDeploymentManagementService.currentTarget());
        TenantContext.clear(); ServicePrincipalContext.clear(); ManagementContext.clear();
    }
    @AfterAll static void close() { if (source != null) source.close(); }

    /** 正式管理proof允许单目标工具部署元数据；保存HQ主体、同连接，并清理另一个Executor的HQ缓存。 */
    @Test void formalServiceWritesOnlyTargetMetadataOnSameConnectionAndRestoresHeadquartersCaches() {
        long created = SnowflakeIds.nextId();
        asHeadquarters(() -> writer.execute(status -> {
            var otherTools = otherSessions.getMapper(ToolDeploymentRecordMapper.class);
            assertEquals("hq-original", otherTools.selectById(headquartersTool).getProviderRef());
            assertEquals(1, tools.selectCount(null)); int backend = backend();
            management.withTarget(Long.toString(target), CREATE, () -> {
                assertEquals(Long.toString(target), TenantContext.getTenantId()); assertEquals(subject, foundation.currentSubject());
                var proof = InstitutionDeploymentManagementService.currentTarget(); assertNotNull(proof);
                assertEquals(Long.toString(grant), proof.grantId()); assertEquals(Long.toString(relation), proof.relationRef());
                assertEquals(backend, backend()); assertEquals("target-original", tools.selectById(targetTool).getProviderRef());
                assertNull(tools.selectById(headquartersTool));
                assertEquals(1, tools.insert(tool(created, target, "created-target")));
                owner.update("UPDATE nw_tool_deployments SET provider_ref='hq-current' WHERE id=?", headquartersTool);
                return null;
            });
            assertHeadquarters(); assertEquals(backend, backend());
            assertEquals("hq-current", otherTools.selectById(headquartersTool).getProviderRef(), "不能复用窗口前另一Executor的旧HQ缓存");
            assertNull(tools.selectById(created)); return null;
        }));
        assertEquals(target, owner.queryForObject("SELECT tenant_id FROM nw_tool_deployments WHERE id=?", Long.class, created));
        assertPoolIdentityEmpty();
    }

    /** 读取窗口使用正式创建回执SQL，只允许原目标及原操作者的原请求。 */
    @Test void targetReadWindowFindsOnlyTheOriginalCreationReceipt() {
        String actor="tenant:"+headquarters+":member:1";
        owner.update("INSERT INTO nw_agent_deployment_revisions(deployment_id,revision,tenant_id,grant_id,grant_revision,bindings,grant_chain,actor_ref,command_ref) SELECT deployment_id,revision,tenant_id,grant_id,grant_revision,bindings,grant_chain,?,? FROM nw_agent_deployments WHERE deployment_id=?",actor,"original",targetAgentDeployment);
        owner.update("INSERT INTO nw_agent_deployment_receipts(deployment_id,revision,tenant_id,response) VALUES(?,1,?,?::jsonb)",targetAgentDeployment,target,"{\"result\":\"ok\"}");
        owner.update("INSERT INTO nw_agent_deployment_create_requests(deployment_id,tenant_id,actor_ref,command_ref) VALUES(?,?,?,?)",targetAgentDeployment,target,actor,"reused");
        String external=owner.queryForObject("SELECT external_agent_ref FROM nw_agent_deployments WHERE deployment_id=?",String.class,targetAgentDeployment);
        asHeadquarters(() -> writer.execute(status -> management.withTarget(Long.toString(target), READ, () -> {
            var query=new java.util.HashMap<String,Object>();
            query.put("tenantId",target); query.put("integrationId",null); query.put("externalAgentRef",external);
            Map<String,Object> row=sessions.selectOne(DEPLOYMENTS+"findExternal",query);
            assertEquals(targetAgentDeployment,((Number)row.get("deployment_id")).longValue());
            var request=Map.of("deploymentId",targetAgentDeployment,"tenantId",target,"actorRef",actor,"commandRef","reused");
            assertNotNull(sessions.selectOne(DEPLOYMENTS+"findCreateResponse",request));
            var wrongActor=Map.of("deploymentId",targetAgentDeployment,"tenantId",target,"actorRef",actor+"-other","commandRef","reused");
            assertNull(sessions.selectOne(DEPLOYMENTS+"findCreateResponse",wrongActor));
            var wrongKey=Map.of("deploymentId",targetAgentDeployment,"tenantId",target,"actorRef",actor,"commandRef","other");
            assertNull(sessions.selectOne(DEPLOYMENTS+"findCreateResponse",wrongKey));
            var wrongTarget=Map.of("deploymentId",targetAgentDeployment,"tenantId",third,"actorRef",actor,"commandRef","reused");
            assertNull(sessions.selectOne(DEPLOYMENTS+"findCreateResponse",wrongTarget));
            return null;
        })));
        assertPoolIdentityEmpty();
    }

    /** 普通事务、甚至已requireTarget但尚未进入私有proof生命周期，都不能自行改域。 */
    @Test void ordinaryTransactionCannotCreateTargetProofOrSwitchTenant() {
        asHeadquarters(() -> writer.execute(status -> {
            assertEquals(1, tools.selectCount(null));
            assertReason(assertThrows(RuntimeException.class, () -> TenantRlsInterceptor.withInstitutionDeploymentTarget(
                    Long.toString(headquarters), Long.toString(target), () -> tools.selectCount(null))), "institution_deployment_target_not_authorized");
            assertNotNull(management.requireTarget(Long.toString(target), CREATE));
            assertNull(InstitutionDeploymentManagementService.currentTarget());
            TenantContext.setTenantId(Long.toString(target));
            assertReason(assertThrows(RuntimeException.class, () -> tools.selectCount(null)), "tenant_transaction_scope_mismatch");
            TenantContext.setTenantId(Long.toString(headquarters)); assertHeadquarters(); return null;
        }));
        assertPoolIdentityEmpty();
    }

    /** 目录read证明在可写外层事务仍不授写；第三Tenant及聊天/执行/记忆/账单官方Mapper均拒绝。 */
    @Test void targetReadProofRejectsWritesAndCreateProofCannotReadBusinessMappersOrThirdTenant() {
        long forbidden = SnowflakeIds.nextId();
        asHeadquarters(() -> writer.execute(status -> {
            management.withTarget(Long.toString(target), READ, () -> {
                assertEquals(1, tools.selectCount(null));
                assertReason(assertThrows(RuntimeException.class, () -> tools.insert(tool(forbidden, target, "read-cannot-write"))), "institution_deployment_statement_not_allowed");
                return null;
            });
            management.withTarget(Long.toString(target), CREATE, () -> {
                for (Supplier<?> blocked : List.<Supplier<?>>of(
                        () -> sessions.getMapper(SessionMapper.class).selectCount(null),
                        () -> sessions.getMapper(ChatMessageMapper.class).selectCount(null),
                        () -> sessions.selectOne("com.skillforge.runtime.adapter.jdbc.execution.ExecutionStoreMapper.now"),
                        () -> sessions.selectOne("com.skillforge.runtime.adapter.jdbc.memory.MemoryMapper.now"),
                        () -> sessions.getMapper(com.skillforge.core.foundation.persistence.TenantMapper.class).selectById(target)))
                    assertReason(assertThrows(RuntimeException.class, blocked::get), "institution_deployment_statement_not_allowed");
                TenantContext.setTenantId(Long.toString(third));
                assertReason(assertThrows(RuntimeException.class, () -> tools.selectCount(null)), "institution_deployment_target_mismatch");
                TenantContext.setTenantId(Long.toString(target));
                var binder = sessions.getMapper(CapabilityCatalogContextMapper.class);
                assertReason(assertThrows(RuntimeException.class, () -> binder.bind("app.platform_admin", "true")), "institution_deployment_setting_not_allowed");
                return null;
            });
            assertHeadquarters(); return null;
        }));
        assertEquals(0, owner.queryForObject("SELECT count(*) FROM nw_tool_deployments WHERE id=?", Integer.class, forbidden));
        assertPoolIdentityEmpty();
    }

    /** 第二数据源自动提交与同源REQUIRES_NEW都不能逃逸原事务的目标窗口。 */
    @Test void anotherDataSourceAndRequiresNewCannotEscapeOriginalTransactionWindow() throws Exception {
        var other = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "institution_deployment_reader", "isolated-restricted-password");
        var foreign = sessions(other).getMapper(SessionMapper.class);
        var independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        asHeadquarters(() -> writer.execute(status -> {
            management.withTarget(Long.toString(target), CREATE, () -> {
                assertReason(assertThrows(RuntimeException.class, () -> foreign.selectCount(null)), "authorized_scope_requires_original_managed_transaction");
                assertReason(assertThrows(RuntimeException.class, () -> independent.execute(inner -> tools.selectCount(null))),
                        "authorized_scope_requires_original_managed_transaction");
                assertEquals(Long.toString(target), TenantContext.getTenantId());
                assertEquals(1, tools.selectCount(null), "拒绝独立事务后原目标事务仍可正常读取"); return null;
            });
            assertHeadquarters(); return null;
        }));
        assertPoolIdentityEmpty();
    }

    /** 业务异常先恢复HQ线程/连接/缓存，外层回滚不留下目标元数据。 */
    @Test void callbackFailureRestoresOriginalContextAndRollsBackTargetWrite() {
        long rolledBack = SnowflakeIds.nextId();
        asHeadquarters(() -> writer.execute(status -> {
            assertEquals(1, tools.selectCount(null));
            var expected = new IllegalArgumentException("fixture-target-failure");
            assertSame(expected, assertThrows(IllegalArgumentException.class, () -> management.withTarget(Long.toString(target), CREATE, () -> {
                tools.insert(tool(rolledBack, target, "must-rollback")); throw expected;
            })));
            assertHeadquarters(); assertEquals(1, tools.selectCount(null)); status.setRollbackOnly(); return null;
        }));
        assertEquals(0, owner.queryForObject("SELECT count(*) FROM nw_tool_deployments WHERE id=?", Integer.class, rolledBack));
        assertPoolIdentityEmpty();
    }

    /** 撤销、关系修订失配或缺当前IAM动作均不能进入回调，也不能留下proof。 */
    @Test void invalidGrantRelationOrCurrentActionRejectsBeforeWindow() {
        owner.update("UPDATE nw_institution_management_grants SET status='revoked',revision=revision+1 WHERE id=?", grant);
        assertTargetDenied(CREATE);
        owner.update("UPDATE nw_tenants SET relation_revision=relation_revision+1 WHERE id=?", target);
        assertTargetDenied(READ);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "fixture-iam-denied")).when(foundation).require(subject, READ);
        assertTargetDenied(READ);
        assertPoolIdentityEmpty();
    }

    /** owner不在目标集合时仍可读context；各目标影响结构沿同一RR快照，退出后恢复原HQ。 */
    @Test void ownerImpactWindowKeepsOneSnapshotAndRestoresOwnerTenant() {
        var read = new TransactionTemplate(transactions); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // 精确目标集合先由真实总部管理许可投影取得；RR只读片段不执行FOR SHARE。
        Set<Long> targets = asHeadquarters(() -> writer.execute(status -> {
            doReturn(true).when(foundation).allowed(subject, READ);
            return management.authorizedTargets(READ);
        }));
        assertEquals(Set.of(target, third), targets);
        asHeadquarters(() -> read.execute(status -> {
            assertEquals(1, tools.selectCount(null)); int originalBackend = backend();
            assertEquals("on", jdbc.queryForObject("SHOW transaction_read_only", String.class));
            TenantRlsInterceptor.withDefinitionImpactTargets(definition, targets, Long.toString(headquarters), () -> {
                assertContext(Long.toString(headquarters));
                assertReason(assertThrows(RuntimeException.class, () -> tools.selectCount(null)), "definition_impact_statement_not_allowed");
                var first = impact(target); assertEquals(1, first.size()); assertEquals(1L, ((Number) first.getFirst().get("revision")).longValue());
                owner.update("UPDATE nw_agent_deployments SET revision=revision+1 WHERE deployment_id=?", targetAgentDeployment);
                assertEquals(1L, ((Number) impact(target).getFirst().get("revision")).longValue());
                assertEquals(1, impact(third).size()); assertEquals(originalBackend, backend());
                TenantContext.setTenantId(Long.toString(headquarters)); return null;
            });
            assertHeadquarters(); assertEquals(originalBackend, backend()); assertEquals(1, tools.selectCount(null)); return null;
        }));
        assertEquals(2L, owner.queryForObject("SELECT revision FROM nw_agent_deployments WHERE deployment_id=?", Long.class, targetAgentDeployment));
        assertPoolIdentityEmpty();
    }

    /** 实际影响SQL始终先获取原context，再绑定精确目标；恢复使用窗口捕获的原HQ值。 */
    private List<Map<String, Object>> impact(long tenant) {
        TenantContext.setTenantId(Long.toString(headquarters));
        Map<String, Object> original = sessions.selectOne(DEPLOYMENTS + "databaseContext");
        TenantContext.setTenantId(Long.toString(tenant));
        try {
            var binder = sessions.getMapper(CapabilityCatalogContextMapper.class);
            binder.bind("app.tenant_id", Long.toString(tenant)); binder.bind("app.platform_admin", "false");
            return sessions.selectList(DEPLOYMENTS + "impact", Map.of("tenantId", tenant, "definitionId", definition));
        } finally {
            sessions.selectOne(DEPLOYMENTS + "restoreDatabaseContext", Map.of("tenant", original.get("tenant"), "platformAdmin", original.get("platform_admin")));
            TenantContext.setTenantId(Long.toString(headquarters));
        }
    }

    /** 不改变授权后的目标作用域；失败必须发生在消费回调之前。 */
    private void assertTargetDenied(String action) {
        var called = new AtomicBoolean();
        asHeadquarters(() -> writer.execute(status -> {
            var rejected = assertThrows(ApiException.class, () -> management.withTarget(Long.toString(target), action, () -> { called.set(true); return null; }));
            assertEquals(403, rejected.getStatus().value()); assertFalse(called.get());
            assertEquals(Long.toString(headquarters), TenantContext.getTenantId()); assertNull(InstitutionDeploymentManagementService.currentTarget()); return null;
        }));
    }

    /** 正式Foundation受信入口保存总部主体，测试仅替代账号/IAM数据，不替代withSubject行为。 */
    private <T> T asHeadquarters(Supplier<T> work) { return foundation.withSubject(subject, work); }
    private int backend() { return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class); }
    private void assertHeadquarters() {
        assertEquals(Long.toString(headquarters), TenantContext.getTenantId()); assertEquals(subject, foundation.currentSubject());
        assertNull(InstitutionDeploymentManagementService.currentTarget()); assertContext(Long.toString(headquarters));
    }
    /** 原连接GUC必须在下一条Mapper重绑前已经恢复，不能仅依靠线程断言。 */
    private void assertContext(String tenant) {
        assertEquals(tenant, jdbc.queryForObject("SELECT current_setting('app.tenant_id',true)", String.class));
        assertEquals("false", jdbc.queryForObject("SELECT current_setting('app.platform_admin',true)", String.class));
    }
    private void assertPoolIdentityEmpty() {
        try (Connection connection = source.getConnection(); var query = connection.createStatement();
                var rows = query.executeQuery("SELECT COALESCE(current_setting('app.tenant_id',true),''),COALESCE(current_setting('app.platform_admin',true),'false')")) {
            assertTrue(rows.next()); assertEquals("", rows.getString(1)); assertEquals("false", rows.getString(2));
        } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
    }
    private static void assertReason(Throwable failure, String reason) {
        for (Throwable current = failure; current != null; current = current.getCause()) if (reason.equals(current.getMessage())) return;
        fail("必须由正式窗口边界拒绝:" + reason, failure);
    }

    /** 完整迁移后的最小合法领域种子，全部引用实际Tenant/版本/精确关系。 */
    private long tenant(Long parent) {
        long id = SnowflakeIds.nextId(); Long plan = owner.queryForObject("SELECT id FROM nw_billing_plans WHERE is_active ORDER BY id LIMIT 1", Long.class);
        owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings,parent_tenant_id) VALUES(?,?,?,?,'active','cloud','{}',?)", id, "rls-" + id, "rls-" + id, plan, parent); return id;
    }
    private long relation(long child) {
        long id = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_institution_relation_revisions(id,child_tenant_id,parent_tenant_id,revision,tree_revision,actor_ref,command_ref) VALUES(?,?,?,1,(SELECT revision FROM nw_institution_tree_revision WHERE singleton),'fixture',?)", id, child, headquarters, "relation-" + id); return id;
    }
    private long managementGrant(long child, long reference, String action) {
        long id = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_institution_management_grants(id,actor_domain,actor_ref,target_tenant_id,action,status,revision,relation_ref) VALUES(?,'tenant',?, ?,?,'active',1,?)", id, "tenant:" + headquarters, child, action, reference); return id;
    }
    private long publishedTool() {
        long stable = SnowflakeIds.nextId(), version = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_capability_definitions(id,capability_type,capability_key,name,trust_level,status,created_at,updated_at) VALUES(?,'TOOL',?,?,'PLATFORM','PUBLISHED',now(),now())", stable, "rls-tool-" + stable, "RLS tool");
        owner.update("INSERT INTO nw_capability_versions(id,definition_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at,published_at) VALUES(?,?,'TOOL','1.0.0',?,'PLATFORM','{}',?,'PUBLISHED',now(),now())", version, stable, "RLS tool", "rls-hash-" + stable); return version;
    }
    private long seedTool(long tenant, String provider) {
        long id = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_tool_deployments(id,tenant_id,deployment_key,tool_version_id,provider_type,provider_ref,environment,status,revision,created_at,updated_at) VALUES(?,?,?,?, 'PLATFORM',?,'TEST','ACTIVE',1,now(),now())", id, tenant, "rls-" + id, toolVersion, provider); return id;
    }
    private ToolDeploymentRecordDO tool(long id, long tenant, String provider) {
        var row = new ToolDeploymentRecordDO(); row.setId(id); row.setTenantId(tenant); row.setDeploymentKey("rls-" + id);
        row.setToolVersionId(toolVersion); row.setProviderType("PLATFORM"); row.setProviderRef(provider); row.setEnvironment("TEST");
        row.setStatus("ACTIVE"); row.setRevision(1L); row.setCreatedAt(Instant.now()); row.setUpdatedAt(row.getCreatedAt()); return row;
    }
    private long seedAgentDeployment(long tenant, long reference) {
        long agent = SnowflakeIds.nextId(), permission = SnowflakeIds.nextId(), deployment = SnowflakeIds.nextId();
        owner.update("INSERT INTO nw_agents(id,agent_key,tenant_id,name,status) VALUES(?,?,?,?,'draft')", agent, "rls-" + agent, tenant, "RLS agent");
        owner.update("INSERT INTO nw_agent_definition_grants(grant_id,definition_id,target_tenant_id,actions,status,revision,actor_ref,request_key,fingerprint,created_at,owner_kind,owner_tenant_id,grantor_kind,grantor_tenant_id,relation_ref,relation_revision) VALUES(?,?,?,'deploy,execute','active',1,?,?,?,now(),'tenant',?,'tenant',?,?,1)", permission, definition, tenant, "tenant:" + headquarters + ":member:1", "grant-" + permission, "a".repeat(64), headquarters, headquarters, reference);
        owner.update("INSERT INTO nw_agent_definition_grant_revisions SELECT g.*,'fixture','create',g.request_key FROM nw_agent_definition_grants g WHERE grant_id=?", permission);
        owner.update("""
                INSERT INTO nw_agent_deployments(deployment_id,tenant_id,agent_id,definition_id,grant_id,grant_revision,external_agent_ref,fingerprint,bindings,revision,created_at,grant_chain)
                SELECT ?,?,?,?,g.grant_id,1,?,?,'{"resource_bindings":[],"delegation_bindings":[],"parameters":{}}',1,now(),
                    jsonb_build_object('definition_id',g.definition_id::text,'owner_kind',g.owner_kind,'owner_tenant_id',g.owner_tenant_id::text,
                    'hops',jsonb_build_array(jsonb_build_object('grant_id',g.grant_id::text,'revision',1,'grantor_kind',g.grantor_kind,
                    'grantor_tenant_id',g.grantor_tenant_id::text,'target_tenant_id',g.target_tenant_id::text,'parent_grant_id',NULL,
                    'parent_grant_revision',NULL,'relation_ref',g.relation_ref::text,'relation_revision',g.relation_revision)))
                FROM nw_agent_definition_grants g WHERE g.grant_id=?
                """, deployment, tenant, agent, definition, "rls-agent-" + agent, "b".repeat(64), permission);
        return deployment;
    }
}
