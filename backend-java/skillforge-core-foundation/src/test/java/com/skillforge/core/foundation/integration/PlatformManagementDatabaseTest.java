package com.skillforge.core.foundation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationAccessMapper;
import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionCatalogue;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.CryptoService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.ibatis.session.Configuration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 完整迁移和受限 PostgreSQL 连接上验证真实管理凭据、CAS、并发幂等及平台对象隔离。 */
@Testcontainers(disabledWithoutDocker = true)
class PlatformManagementDatabaseTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("platform_management").withUsername("skillforge").withPassword("synthetic_container_password");
    private static JdbcTemplate owner;
    private static ManagementMapper mapper;
    private static FoundationAccess access;
    private static PlatformManagementService service;
    private static TransactionTemplate writer;
    private static final String TENANT = "2962001";
    private static final String OTHER = "2962002";
    private static final String ACTION = "platform.usage.events.read";
    private static final FoundationAccess.Subject ADMIN = new FoundationAccess.Subject(true, null, "2961001");
    private static final FoundationAccess.Subject READER = new FoundationAccess.Subject(true, null, "2961002");

    /** 专属容器运行全迁移链，生产 XML 使用 NOSUPERUSER/NOBYPASSRLS 连接。 */
    @BeforeAll static void database() throws Exception {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("CREATE ROLE management_test LOGIN PASSWORD 'synthetic_restricted_password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO management_test");
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO management_test");
        var connections = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "management_test", "synthetic_restricted_password");
        Configuration configuration = new Configuration(); configuration.setMapUnderscoreToCamelCase(true); configuration.addInterceptor(new TenantRlsInterceptor());
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(connections); factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/ManagementMapper.xml"), new ClassPathResource("mapper/foundation/FoundationAccessMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject()); mapper = session.getMapper(ManagementMapper.class);
        var transactions = new DataSourceTransactionManager(connections); writer = new TransactionTemplate(transactions);
        FoundationPermissionContributor permissions = () -> java.util.stream.Stream.concat(ManagementActions.SCOPES.stream(),
                java.util.stream.Stream.of("platform.management_principals.read", "platform.management_principals.create", "platform.management_principals.rotate", "platform.management_principals.revoke"))
                .map(code -> new FoundationPermission(code, code, FoundationPermission.Audience.PLATFORM)).toList();
        access = new FoundationAccess(session.getMapper(FoundationAccessMapper.class), new FoundationPermissionCatalogue(List.of(permissions)), transactions);
        SkillForgeProperties properties = new SkillForgeProperties(); properties.setSecretKey("synthetic_platform_management_key_296_32_chars");
        service = new PlatformManagementService(access, mapper, new CryptoService(properties),
                new ObjectMapper().registerModule(new JavaTimeModule()), mock(AuditService.class));
        Long plan = owner.queryForObject("SELECT id FROM nw_billing_plans ORDER BY id LIMIT 1", Long.class);
        for (String tenant : List.of(TENANT, OTHER)) owner.update("INSERT INTO nw_tenants(id,name,slug,plan_id,status,deployment_mode,settings) VALUES(?::bigint,?,?,?,'active','cloud','{}'::jsonb)",
                tenant, "管理身份隔离机构", "management-test-" + tenant, plan);
        for (String member : List.of(ADMIN.memberId(), READER.memberId())) {
            owner.update("INSERT INTO nw_platform_members(id,username,display_name,email,password_hash,status) VALUES(?::bigint,?,?,?,'synthetic_unused','active')",
                    member, "management-test-" + member, "管理身份测试人员", member + "@example.test");
            owner.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES('platform',?::bigint,'管理身份测试人员')", member);
        }
        owner.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) SELECT 'platform',?::bigint,id FROM nw_foundation_roles WHERE scope_key='platform' AND administrator", ADMIN.memberId());
        owner.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(2963001,'platform','management_reader','受限平台读取','self')");
        owner.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES('platform',?::bigint,2963001)", READER.memberId());
        owner.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES('platform',2963001,?)", ACTION);
    }

    /** 仅清理本测试独占容器里的管理身份；平台账号与机构基线独立于被测生命周期。 */
    @BeforeEach void fixture() {
        owner.execute("TRUNCATE nw_platform_management_receipts,nw_platform_management_scopes,nw_platform_management_targets,nw_platform_management_principals");
        owner.update("UPDATE nw_platform_members SET status='active' WHERE id IN(2961001,2961002)");
        owner.update("UPDATE nw_foundation_roles SET enabled=true WHERE id=2963001");
        owner.update("DELETE FROM nw_foundation_resource_grants WHERE scope_key='platform' AND resource_id IN(2962001,2962002)");
        owner.update("DELETE FROM nw_foundation_resource_ownership WHERE scope_key='platform' AND kind='tenant' AND resource_id IN(2962001,2962002)");
        owner.update("INSERT INTO nw_foundation_resource_ownership(scope_key,kind,resource_id,owner_member_id) VALUES('platform','tenant',2962001,2961002)");
    }

    /** 与真实请求生命周期相同，异常路径也不能残留管理身份。 */
    @AfterEach void cleanup() { ManagementContext.clear(); TenantContext.clear(); }

    /** 创建、稳定重读、轮换、旧Key拒绝、CAS冲突和终态撤销在真实库上闭环。 */
    @Test void credentialLifecycleNeverRedeliversSecretsOrAcceptsOldKeys() throws Exception {
        var issued = asAdmin(() -> service.create(request(), "create-1"));
        assertThat(issued.managementKey()).startsWith("sfmg_"); assertThat(issued.signingSecret()).isNotBlank();
        var replay = asAdmin(() -> service.create(request(), "create-1"));
        assertThat(replay.replayed()).isTrue(); assertThat(replay.principal()).isEqualTo(issued.principal());
        assertThat(replay.managementKey()).isNull(); assertThat(replay.signingSecret()).isNull();
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_platform_management_principals", Integer.class)).isEqualTo(1);
        var identity = authenticate(issued); ManagementContext.set(identity);
        service.requireTenant(ACTION, Long.parseLong(TENANT));
        assertThatThrownBy(() -> service.requireTenant(ACTION, Long.parseLong(OTHER))).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        ManagementContext.clear();
        var command = new ManagementContracts.Command(issued.principal().principalId(), issued.principal().revision());
        var rotated = asAdmin(() -> service.rotate(command, "rotate-1"));
        assertThat(rotated.principal().credentialVersion()).isEqualTo(issued.principal().credentialVersion() + 1);
        assertThatThrownBy(() -> authenticate(issued)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        var rotationReplay = asAdmin(() -> service.rotate(command, "rotate-1"));
        assertThat(rotationReplay.replayed()).isTrue(); assertThat(rotationReplay.managementKey()).isNull();
        assertThat(rotationReplay.principal()).isEqualTo(rotated.principal());
        assertThatThrownBy(() -> asAdmin(() -> service.rotate(command, "rotate-stale"))).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        var revoked = asAdmin(() -> service.revoke(new ManagementContracts.Command(rotated.principal().principalId(), rotated.principal().revision()), "revoke-1"));
        assertThat(revoked.principal().status()).isEqualTo("revoked"); assertThat(revoked.managementKey()).isNull();
        assertThatThrownBy(() -> authenticate(rotated)).isInstanceOf(ApiException.class);
        assertThat(owner.queryForObject("SELECT result_json::text FROM nw_platform_management_receipts WHERE operation='create'", String.class))
                .doesNotContain(issued.managementKey(), issued.signingSecret(), "signing_secret_encrypted", "key_hash");
    }

    /** 同时到达的同一创建请求只产生一份主体和一份明文交付；后者返回安全原回执。 */
    @Test void concurrentSameKeyCreatesOnePrincipalAndOneSecretDelivery() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return asAdmin(() -> service.create(request(), "concurrent-create")); });
            var second = executor.submit(() -> { start.await(); return asAdmin(() -> service.create(request(), "concurrent-create")); });
            start.countDown();
            var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result.managementKey() != null)).hasSize(1);
            assertThat(results.get(0).principal()).isEqualTo(results.get(1).principal());
            assertThat(owner.queryForObject("SELECT count(*) FROM nw_platform_management_principals", Integer.class)).isEqualTo(1);
        }
    }

    /** 同键异参冲突不签发第二份身份，未完成安全回执也不能单独提交。 */
    @Test void conflictsAndIncompleteTransactionsCannotLeaveUntrackedCredentials() {
        asAdmin(() -> service.create(request(), "same-key"));
        var changed = new ManagementContracts.Create("changed-name", Set.of(ACTION), Set.of(TENANT), Set.of(), Set.of());
        assertThatThrownBy(() -> asAdmin(() -> service.create(changed, "same-key"))).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_platform_management_principals", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> writer.execute(status -> mapper.claimReceipt("platform_member:2961001", "create", "new", "incomplete", TokenHash.sha256Hex("synthetic"))))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .rootCause().hasMessageContaining("platform_management_receipt_incomplete");
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_platform_management_receipts WHERE idempotency_key='incomplete'", Integer.class)).isZero();
    }

    /** 平台动作仍受当前真实对象范围过滤；角色/账号撤销在下一边界立即生效。 */
    @Test void currentHumanScopeIsAppliedBeforeTargetListingAndWithdrawalsTakeEffect() {
        access.withSubject(READER, () -> {
            assertThat(service.allowedTenantIds(ACTION)).containsExactly(Long.parseLong(TENANT));
            service.requireTenant(ACTION, Long.parseLong(TENANT));
            assertThatThrownBy(() -> service.requireTenant(ACTION, Long.parseLong(OTHER))).isInstanceOf(ApiException.class);
            return null;
        });
        owner.update("UPDATE nw_foundation_roles SET enabled=false WHERE id=2963001");
        assertThatThrownBy(() -> access.withSubject(READER, () -> service.allowedTenantIds(ACTION))).isInstanceOf(ApiException.class);
        owner.update("UPDATE nw_platform_members SET status='disabled' WHERE id=2961002");
        assertThatThrownBy(() -> access.withSubject(READER, () -> service.allowedTenantIds(ACTION)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    /** 机构隔离连接无法读取管理密文，平台主体也不因管理目标得到业务执行身份。 */
    @Test void managementStorageIsInvisibleInInstitutionDatabaseScope() {
        var issued = asAdmin(() -> service.create(request(), "rls-create"));
        TenantContext.setTenantId(TENANT);
        assertThat(mapper.principal(Long.parseLong(issued.principal().principalId()))).isNull();
        assertThat(TenantContext.getTenantId()).isEqualTo(TENANT);
        assertThatThrownBy(() -> writer.execute(status -> mapper.insertTargets(Long.parseLong(issued.principal().principalId()), "tenant", List.of(OTHER))))
                .isInstanceOf(RuntimeException.class);
        TenantContext.clear();
        assertThat(asAdmin(() -> service.list(1, 20)).items()).hasSize(1);
    }

    /** 受限平台人员即使获签发动作，也不能授予自身没有的对象范围。 */
    @Test void currentPersonCannotIssueArbitraryTargetsOrNamespaces() {
        owner.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES('platform',2963001,'platform.management_principals.create') ON CONFLICT DO NOTHING");
        var request = new ManagementContracts.Create("foreign", Set.of(ACTION), Set.of(OTHER), Set.of(), Set.of());
        assertThatThrownBy(() -> access.withSubject(READER, () -> writer.execute(status -> service.create(request, "foreign-create"))))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(owner.queryForObject("SELECT count(*) FROM nw_platform_management_principals", Integer.class)).isZero();
    }

    /** 固定真实 Tenant 目标，不依赖任何旧应用行。 */
    private static ManagementContracts.Create request() { return new ManagementContracts.Create("isolated-usage-reader", Set.of(ACTION), Set.of(TENANT), Set.of(), Set.of()); }
    /** 用当前 Foundation 平台身份执行，显式事务替代单测实例上的 Spring AOP。 */
    private static <T> T asAdmin(Supplier<T> operation) { return access.withSubject(ADMIN, () -> writer.execute(status -> operation.get())); }
    /** 独立实现协议HMAC，不从被测组件获取签名答案。 */
    private static ManagementContext.Identity authenticate(ManagementContracts.Issued credentials) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond()), target = "/api/v1/platform/usage/events?tenant_id=" + TENANT;
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(credentials.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "\nGET\n" + target + "\n" + TokenHash.sha256Hex("")).getBytes(StandardCharsets.UTF_8)));
        return service.authenticate(credentials.managementKey(), timestamp, signature, "GET", target, "");
    }
}
