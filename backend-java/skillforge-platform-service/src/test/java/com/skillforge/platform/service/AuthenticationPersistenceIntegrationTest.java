package com.skillforge.platform.service;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.capability.persistence.ToolDeploymentRecordMapper;
import com.skillforge.core.chat.persistence.SessionOutboxMapper;
import com.skillforge.core.foundation.persistence.AuditLogMapper;
import com.skillforge.core.foundation.persistence.PlatformMemberMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.security.FoundationSecurityService;
import com.skillforge.core.foundation.security.SecurityMapper;
import com.skillforge.core.foundation.security.SecuritySessionCleanupScheduler;
import com.skillforge.core.foundation.security.Totp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.time.BusinessTime;
import com.skillforge.core.shared.config.AuditMetaObjectHandler;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import com.skillforge.platform.dto.tenant.CreateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.ResetPasswordRequest;
import com.skillforge.platform.dto.tenant.UpdateTenantMemberRequest;
import com.skillforge.platform.dto.tenant.UpdateTenantRequest;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.messaging.application.NotificationService;
import com.skillforge.core.foundation.messaging.contract.NotificationCommands;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.foundation.security.RefreshTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** 真实迁移、Mapper、非绕过角色的 RLS 和独立 Redis 验证认证与账号写入组合。 */
class AuthenticationPersistenceIntegrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    /** 外部模式只借用父新建的auth库及已授权Redis，禁止Testcontainers另启资源。 */
    private static final String EXTERNAL_URL=System.getenv().keySet().stream().anyMatch(key->key.startsWith("SKILLFORGE_S03_EXTERNAL_"))?required("SKILLFORGE_S03_EXTERNAL_JDBC_URL"):null;
    private static JdbcTemplate admin;
    private static SqlSessionTemplate sessions;
    private static DataSourceTransactionManager transactions;
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();

    @BeforeAll
    static void schema() throws Exception {
        if(EXTERNAL_URL==null) { DATABASE.start(); REDIS.start(); }
        String url=EXTERNAL_URL==null?DATABASE.getJdbcUrl():EXTERNAL_URL;
        var source = new DriverManagerDataSource(url, EXTERNAL_URL==null?DATABASE.getUsername():required("SKILLFORGE_S03_EXTERNAL_ADMIN_USER"), EXTERNAL_URL==null?DATABASE.getPassword():required("SKILLFORGE_S03_EXTERNAL_ADMIN_PASSWORD"));
        admin = new JdbcTemplate(source);
        if(EXTERNAL_URL==null) admin.execute("CREATE ROLE skillforge NOSUPERUSER NOBYPASSRLS");
        else verifyExternalFixture();
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        String appUser=EXTERNAL_URL==null?"auth_account_test":required("SKILLFORGE_S03_EXTERNAL_DB_USER");
        String appPassword=EXTERNAL_URL==null?"test-only-password":required("SKILLFORGE_S03_EXTERNAL_DB_PASSWORD");
        if(EXTERNAL_URL==null) {
        admin.execute("CREATE ROLE auth_account_test LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOBYPASSRLS");
        admin.execute("GRANT USAGE ON SCHEMA public TO auth_account_test");
        admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO auth_account_test");
        admin.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO auth_account_test");
        // 隔离测试角色显式模拟scheduler调用者；生产只授既有scheduler应用角色，不授PUBLIC。
        admin.execute("GRANT EXECUTE ON FUNCTION public.foundation_security_cleanup_scopes() TO auth_account_test");
        }
        var application = new DriverManagerDataSource(url, appUser, appPassword);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("auth-account-test",
                new org.mybatis.spring.transaction.SpringManagedTransactionFactory(), application));
        GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(new AuditMetaObjectHandler());
        configuration.addInterceptor(new TenantRlsInterceptor());
        for (var mapper : List.of(TenantMapper.class, TenantMemberMapper.class, PlanMapper.class, AuditLogMapper.class,
                PlatformMemberMapper.class, com.skillforge.core.agent.mapper.AgentMapper.class,
                com.skillforge.core.agent.capabilitymount.AgentCapabilityMountSetMapper.class,
                com.skillforge.core.agent.capabilitymount.AgentCapabilityMountMapper.class,
                ToolDeploymentRecordMapper.class)) configuration.addMapper(mapper);
        try (var xml = AuthenticationPersistenceIntegrationTest.class.getClassLoader().getResourceAsStream("mapper/SessionOutboxMapper.xml")) {
            assertNotNull(xml);
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml, configuration, "mapper/SessionOutboxMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        try(var xml=AuthenticationPersistenceIntegrationTest.class.getClassLoader().getResourceAsStream("mapper/foundation/security/SecurityMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml,configuration,"mapper/foundation/security/SecurityMapper.xml",configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        transactions = new DataSourceTransactionManager(application);
        connections = new LettuceConnectionFactory(EXTERNAL_URL==null?REDIS.getHost():required("SKILLFORGE_S03_EXTERNAL_REDIS_HOST"), EXTERNAL_URL==null?REDIS.getMappedPort(6379):Integer.parseInt(required("SKILLFORGE_S03_EXTERNAL_REDIS_PORT")));
        connections.afterPropertiesSet(); connections.start();
        redis = new StringRedisTemplate(connections); redis.afterPropertiesSet();
        assertEquals(false, new JdbcTemplate(application).queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
        admin.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target,is_active) VALUES(701,'auth-test-plan','测试套餐',0.99,true)");
        admin.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(7,'机构','auth-account-seven',701),(8,'其他机构','auth-account-eight',701)");
        admin.execute("CREATE FUNCTION reject_account_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit unavailable'; END $$");
    }

    @BeforeEach
    void seed() {
        TenantContext.clear();
        for(String table:List.of("nw_foundation_security_recovery_codes","nw_foundation_security_mfa","nw_foundation_security_sessions","nw_foundation_security_policies","nw_foundation_security_recovery_tokens")) admin.update("DELETE FROM "+table);
        admin.execute("DROP TRIGGER IF EXISTS reject_account_audit ON nw_audit_logs");
        // 该测试独占 Testcontainers PostgreSQL；管理员只重置隔离 fixture，不绕过生产不可变审计。
        admin.execute("TRUNCATE TABLE nw_foundation_audit_read_batches,nw_audit_logs");
        admin.update("DELETE FROM nw_tenant_members WHERE tenant_id IN(7,8)");
        admin.update("UPDATE nw_tenants SET name='机构',status='active' WHERE id=7");
        admin.update("INSERT INTO nw_tenant_members(id,tenant_id,username,password_hash,role) VALUES(11,7,'member',?,'developer'),(12,8,'foreign',?,'developer')",
                PASSWORDS.encode("old-password"), PASSWORDS.encode("old-password"));
        admin.update("DELETE FROM nw_platform_members WHERE id=21");
        admin.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(21,'auth-test-platform',?,'active')", PASSWORDS.encode("old-password"));
        try (var connection = connections.getConnection()) { connection.serverCommands().flushDb(); }
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }
    @AfterAll static void closeRedis() { if (connections != null) connections.destroy(); if(EXTERNAL_URL==null) { REDIS.stop(); DATABASE.stop(); } }

    /** 仅消费父分配的全新auth lane；已跑fixture必须重新申请新库，不能盲目清空重放。 */
    private static void verifyExternalFixture() {
        String database=admin.queryForObject("SELECT current_database()",String.class);
        assertTrue(database!=null&&database.matches("sf_s03_host_auth_[a-z0-9_]+"),"外部模式必须是父新建的认证专属库");
        assertEquals(1,admin.queryForObject("SELECT count(*) FROM public.s03_host_fixture_guard WHERE lane='host_auth' AND token=?",Integer.class,required("SKILLFORGE_S03_EXTERNAL_FIXTURE_TOKEN")));
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM public.nw_foundation_security_sessions",Integer.class),"外部lane仅可消费一次，不能重置已有安全会话");
    }
    /** 读取受控环境参数，缺值拒绝且不打印秘密。 */
    private static String required(String name) { String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("缺少外部fixture参数 "+name);return value; }

    /** 空tenant目录必须跨机构工作；真正清理仍使用普通RLS角色并恢复调用线程scope。 */
    @Test
    void securityCleanupDirectoryAndSchedulerReachBothTenantsWithPrivilegedOwnerOnly() {
        var mapper=sessions.getMapper(SecurityMapper.class);
        var application=new JdbcTemplate(transactions.getDataSource());
        assertFalse(application.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
        assertTrue(application.queryForObject("SELECT has_function_privilege(current_user,'public.foundation_security_cleanup_scopes()','EXECUTE')",Boolean.class));
        assertTrue(admin.queryForObject("SELECT r.rolsuper OR r.rolbypassrls FROM pg_proc p JOIN pg_roles r ON r.oid=p.proowner WHERE p.oid='public.foundation_security_cleanup_scopes()'::regprocedure",Boolean.class));
        assertTrue(admin.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) acl WHERE p.oid='public.foundation_security_cleanup_scopes()'::regprocedure AND acl.grantee=0 AND acl.privilege_type='EXECUTE')",Boolean.class));

        admin.update("INSERT INTO nw_foundation_security_policies(scope_key) VALUES('tenant:7'),('tenant:8')");
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM nw_foundation_security_policies WHERE session_retention_days=90",Integer.class));
        String credential="a".repeat(64);
        admin.update("""
                INSERT INTO nw_foundation_security_sessions(id,scope_key,member_id,credential_hash,state,device_label,created_at,expires_at,revoked_at,investigation_protected) VALUES
                (8101,'tenant:7',11,?,'full','expired',now()-interval '120 days',now()-interval '100 days',NULL,false),
                (8102,'tenant:8',12,?,'full','revoked',now()-interval '120 days',now()+interval '1 day',now()-interval '100 days',false),
                (8103,'tenant:7',11,?,'full','active',now()-interval '1 day',now()+interval '1 day',NULL,false),
                (8104,'tenant:8',12,?,'full','protected',now()-interval '120 days',now()-interval '100 days',NULL,true)
                """,credential,credential,credential,credential);

        TenantContext.clear();
        assertEquals(java.util.Set.of("tenant:7","tenant:8"),java.util.Set.copyOf(mapper.sessionCleanupScopes()));
        assertNull(TenantContext.getTenantId());
        // 目录领取会推进水位；重置隔离fixture后经正式scheduler再次从空scope领取。
        admin.update("UPDATE nw_foundation_security_policies SET next_session_cleanup_at=now()-interval '1 second'");
        TenantContext.setTenantId("8");
        new SecuritySessionCleanupScheduler(mapper,security(redis,mapper)).cleanup();
        assertEquals("8",TenantContext.getTenantId());
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM nw_foundation_security_sessions WHERE id IN(8101,8102)",Integer.class));
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM nw_foundation_security_sessions WHERE id IN(8103,8104)",Integer.class));
        assertNull(mapper.session("tenant:7",8103));
        assertNotNull(mapper.session("tenant:8",8104));
        assertFalse(application.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class));
    }

    /** 真实RLS、显式保留及调查引用保护；有效和跨租户会话不能被生命周期删除。 */
    @Test
    void sessionRetentionKeepsActiveProtectedAndOtherTenantRows() {
        var mapper=sessions.getMapper(SecurityMapper.class);
        String credential="a".repeat(64);
        admin.update("INSERT INTO nw_foundation_security_sessions(id,scope_key,member_id,credential_hash,state,device_label,created_at,expires_at,investigation_protected) VALUES(8001,'tenant:7',11,?,'full','expired',now()-interval '10 days',now()-interval '5 days',false),(8002,'tenant:7',11,?,'full','active',now()-interval '1 day',now()+interval '1 day',false),(8003,'tenant:7',11,?,'full','protected',now()-interval '10 days',now()-interval '5 days',true),(8004,'tenant:8',12,?,'full','foreign',now()-interval '10 days',now()-interval '5 days',false)",credential,credential,credential,credential);
        TenantContext.setTenantId("7");
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status->mapper.policy("tenant:7",false));
        assertEquals(90,mapper.sessionRetention("tenant:7"));
        int defaultRetentionDeleted=tx.<Integer>execute(status->mapper.cleanupSessions("tenant:7",100));
        assertEquals(0,defaultRetentionDeleted);

        tx.executeWithoutResult(status->mapper.setSessionRetention("tenant:7",1));
        int expiredDeleted=tx.<Integer>execute(status->mapper.cleanupSessions("tenant:7",100));
        assertEquals(1,expiredDeleted);
        assertNull(mapper.session("tenant:7",8001));
        assertNotNull(mapper.session("tenant:7",8002));
        assertNotNull(mapper.session("tenant:7",8003));

        int foreignDeleted=tx.<Integer>execute(status->mapper.cleanupSessions("tenant:8",100));
        assertEquals(0,foreignDeleted);
        TenantContext.setTenantId("8");
        assertNotNull(mapper.session("tenant:8",8004));

        TenantContext.setTenantId("7");
        tx.executeWithoutResult(status->assertEquals(1,mapper.protectSession("tenant:7",8003,false)));
        int unprotectedDeleted=tx.<Integer>execute(status->mapper.cleanupSessions("tenant:7",100));
        assertEquals(1,unprotectedDeleted);
        assertNull(mapper.session("tenant:7",8003));
    }

    @Test
    void authoritySessionRotatesUnderRlsAndPasswordOrDisableImmediatelyRejectsAccessAndRefresh() throws Exception {
        String raw=RefreshTokens.create();seedSession(false,raw);
        var mapper=sessions.getMapper(SecurityMapper.class);
        TenantContext.setTenantId("8");assertNull(mapper.session("tenant:7",7000));TenantContext.clear();
        try(var stp=mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("tenant.jwt");
            var result=security(redis,mapper).refresh(false,"7."+raw);
            assertEquals("full",result.mfaState());assertNotEquals("7."+raw,result.refreshToken());
            assertUnauthorized(()->security(redis,mapper).refresh(false,"7."+raw));
            stp.when(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM)).thenReturn("7000");
            assertNotNull(security(redis,mapper).currentSession());
            admin.update("UPDATE nw_tenant_members SET password_hash=? WHERE id=11",PASSWORDS.encode("ChangedPassword123"));
            assertUnauthorized(()->security(redis,mapper).currentSession());
            assertUnauthorized(()->security(redis,mapper).refresh(false,result.refreshToken()));
            admin.update("UPDATE nw_tenant_members SET status='disabled' WHERE id=11");
            assertUnauthorized(()->security(redis,mapper).currentSession());
        }
    }

    @Test
    void warningTenantRemainsUsableAndSuspensionImmediatelyInvalidatesAuthoritySession() throws Exception {
        seedSession(false,RefreshTokens.create());TenantContext.setTenantId("7");
        var service=security(redis,sessions.getMapper(SecurityMapper.class));
        try(var stp=mockStatic(StpUtil.class)) {
            stp.when(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM)).thenReturn("7000");
            admin.update("UPDATE nw_tenants SET status='warning' WHERE id=7");assertNotNull(service.currentSession());
            admin.update("UPDATE nw_tenants SET status='suspended' WHERE id=7");assertUnauthorized(service::currentSession);
        }
    }

    @Test
    void committedAccessChangeRevokesDevicesInIndependentTransaction() throws Exception {
        seedSession(false,RefreshTokens.create());TenantContext.setTenantId("7");
        var service=security(redis,sessions.getMapper(SecurityMapper.class));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            sessions.getMapper(SecurityMapper.class).policy("tenant:7",false);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){service.revokeSubject(false,"7","11");}
            });
        });
        assertTrue(admin.queryForObject("SELECT revoked_at IS NOT NULL AND refresh_hash IS NULL FROM nw_foundation_security_sessions WHERE id=7000",Boolean.class));
        assertEquals("7",TenantContext.getTenantId());
    }

    @Test
    void bothDomainsHaveExactlyOneRefreshWinnerWithRealDatabaseCompareAndSet() throws Exception {
        for(boolean platform:List.of(false,true)) {
            String raw=RefreshTokens.create();seedSession(platform,raw);
            String scope=platform?"platform":"tenant:7",prefix=platform?"platform":"7";
            var production=sessions.getMapper(SecurityMapper.class);var barrier=new CyclicBarrier(2);
            var gated=mock(SecurityMapper.class,org.mockito.AdditionalAnswers.delegatesTo(production));
            doAnswer(call->{var row=production.refresh(scope,hash(raw));barrier.await(10,TimeUnit.SECONDS);return row;}).when(gated).refresh(scope,hash(raw));
            var pool=Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> action=()->{try(var stp=mockStatic(StpUtil.class)){stp.when(StpUtil::getTokenValue).thenReturn("race.jwt");try{security(redis,gated).refresh(platform,prefix+"."+raw);return 200;}catch(ApiException failure){return failure.getStatus().value();}}finally{TenantContext.clear();}};
                var first=pool.submit(action);var second=pool.submit(action);
                assertEquals(List.of(200,401),List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)).stream().sorted().toList());
            }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }

    @Test
    void voluntaryMfaEncryptsSeedRejectsOtpReplayAndConsumesRecoveryCodeOnlyOnce() throws Exception {
        seedSession(false,RefreshTokens.create());TenantContext.setTenantId("7");
        var service=security(redis,sessions.getMapper(SecurityMapper.class));
        Instant time=Instant.now();
        try(var clock=mockStatic(BusinessTime.class);var stp=mockStatic(StpUtil.class)) {
            clock.when(BusinessTime::clock).thenReturn(Clock.fixed(time,ZoneOffset.UTC));
            stp.when(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM)).thenReturn("7000");
            stp.when(StpUtil::getTokenValue).thenReturn("mfa.jwt");
            assertFalse(service.status().enabled());assertFalse(service.status().required());
            assertEquals("full",service.status().mfaState());assertDoesNotThrow(service::requireStepUp);
            var enrollment=service.enroll();
            assertNotEquals(enrollment.secret(),admin.queryForObject("SELECT secret_encrypted FROM nw_foundation_security_mfa WHERE scope_key='tenant:7' AND member_id=11",String.class));
            long acceptedCounter=time.getEpochSecond()/30+1;
            String proof=Totp.generate(enrollment.secret(),acceptedCounter);
            var enabled=service.enable(proof);
            assertEquals("full",enabled.authentication().mfaState());assertEquals(10,enabled.recoveryCodes().size());
            assertTrue(service.status().enabled());assertFalse(service.status().required());assertDoesNotThrow(service::requireStepUp);
            assertEquals(acceptedCounter,admin.queryForObject("SELECT last_counter FROM nw_foundation_security_mfa WHERE scope_key='tenant:7' AND member_id=11",Long.class));
            assertUnauthorized(()->service.verify(proof));
            String code=enabled.recoveryCodes().getFirst();
            assertEquals(hash(code),admin.queryForObject("SELECT code_hash FROM nw_foundation_security_recovery_codes WHERE code_hash=?",String.class,hash(code)));
            assertEquals("full",service.verify(code).mfaState());assertEquals(9,service.status().recoveryCodesRemaining());
            assertUnauthorized(()->service.verify(code));
            assertEquals(2,admin.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE tenant_id=7 AND action='security.mfa.verify' AND outcome='denied'",Integer.class));
            admin.update("UPDATE nw_foundation_security_sessions SET verified_at=NULL WHERE id=7000");
            assertEquals("challenge",service.status().mfaState());
            assertEquals(403,assertThrows(ApiException.class,service::requireFull).getStatus().value());
        }
    }

    @Test
    void recoveryCodeHasExactlyOneWinnerWithRealMfaRowLockAndDelete() throws Exception {
        seedSession(false,RefreshTokens.create());seedEnabledMfa();
        String code=RefreshTokens.create();admin.update("INSERT INTO nw_foundation_security_recovery_codes(scope_key,member_id,code_hash) VALUES('tenant:7',11,?)",hash(code));
        var production=sessions.getMapper(SecurityMapper.class);var barrier=new CyclicBarrier(2);
        var gated=mock(SecurityMapper.class,org.mockito.AdditionalAnswers.delegatesTo(production));
        doAnswer(call->{barrier.await(10,TimeUnit.SECONDS);return production.mfa("tenant:7",11,true);}).when(gated).mfa("tenant:7",11,true);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> action=()->{TenantContext.setTenantId("7");try(var stp=mockStatic(StpUtil.class)){stp.when(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM)).thenReturn("7000");stp.when(StpUtil::getTokenValue).thenReturn("mfa-race.jwt");try{security(redis,gated).verify(code);return 200;}catch(ApiException failure){return failure.getStatus().value();}}finally{TenantContext.clear();}};
            var first=pool.submit(action);var second=pool.submit(action);
            assertEquals(List.of(200,401),List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)).stream().sorted().toList());
            assertEquals(0,admin.queryForObject("SELECT count(*) FROM nw_foundation_security_recovery_codes WHERE scope_key='tenant:7' AND member_id=11",Integer.class));
            assertEquals(1,admin.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE tenant_id=7 AND action='security.mfa.verify' AND outcome='denied'",Integer.class));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test
    void emailRecoveryTargetsRegisteredEmailIsOneTimeAndPreservesMfa() throws Exception {
        seedSession(false,RefreshTokens.create());seedEnabledMfa();
        admin.update("UPDATE nw_tenant_members SET email='registered@example.test' WHERE id=11");
        var notifications=mock(NotificationService.class);
        var service=security(redis,sessions.getMapper(SecurityMapper.class),notifications);
        service.requestRecovery(false,"auth-account-seven","member");
        var delivery=org.mockito.ArgumentCaptor.forClass(NotificationCommands.Enqueue.class);
        verify(notifications).enqueue(delivery.capture());var mail=delivery.getValue();
        assertEquals("registered@example.test",mail.recipientEmail());assertNull(mail.recipientMemberId());
        assertFalse(mail.inApp());assertTrue(mail.email());assertEquals("security.password_recovery",mail.templateKey());
        String token=mail.variables().get("token");
        assertUnauthorized(()->service.completeRecovery(true,"recovery",token,"RecoveredPassword123"));
        service.completeRecovery(false,"recovery",token,"RecoveredPassword123");
        assertTrue(PASSWORDS.matches("RecoveredPassword123",admin.queryForObject("SELECT password_hash FROM nw_tenant_members WHERE id=11",String.class)));
        assertTrue(admin.queryForObject("SELECT enabled FROM nw_foundation_security_mfa WHERE scope_key='tenant:7' AND member_id=11",Boolean.class));
        assertTrue(admin.queryForObject("SELECT revoked_at IS NOT NULL FROM nw_foundation_security_sessions WHERE id=7000",Boolean.class));
        assertUnauthorized(()->service.completeRecovery(false,"recovery",token,"AnotherPassword123"));
        service.requestRecovery(false,"auth-account-seven","missing-account");verifyNoMoreInteractions(notifications);
    }

    @Test
    void emailRecoveryHasOneConcurrentWinnerAndInvitationCannotReactivateDisabledMember() throws Exception {
        String raw=RefreshTokens.create();
        admin.update("INSERT INTO nw_foundation_security_recovery_tokens(scope_key,member_id,purpose,token_hash,expires_at) VALUES('tenant:7',11,'recovery',?,now()+interval '30 minutes')",hash(raw));
        var production=sessions.getMapper(SecurityMapper.class);var barrier=new CyclicBarrier(2);
        var gated=mock(SecurityMapper.class,org.mockito.AdditionalAnswers.delegatesTo(production));
        doAnswer(call->{barrier.await(10,TimeUnit.SECONDS);return production.consumeRecovery("tenant:7","recovery",hash(raw));}).when(gated).consumeRecovery("tenant:7","recovery",hash(raw));
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> action=()->{try{security(redis,gated).completeRecovery(false,"recovery","7."+raw,"RecoveredPassword123");return 200;}catch(ApiException failure){return failure.getStatus().value();}finally{TenantContext.clear();}};
            var first=pool.submit(action);var second=pool.submit(action);
            assertEquals(List.of(200,401),List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)).stream().sorted().toList());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        String invitation=RefreshTokens.create();
        admin.update("UPDATE nw_tenant_members SET status='disabled' WHERE id=11");
        admin.update("INSERT INTO nw_foundation_security_recovery_tokens(scope_key,member_id,purpose,token_hash,expires_at) VALUES('tenant:7',11,'invitation',?,now()+interval '2 days')",hash(invitation));
        assertUnauthorized(()->security(redis,production).completeRecovery(false,"invitation","7."+invitation,"InvitedPassword123"));
        assertEquals("disabled",admin.queryForObject("SELECT status FROM nw_tenant_members WHERE id=11",String.class));
        admin.update("UPDATE nw_tenant_members SET status='invited' WHERE id=11");
        security(redis,production).completeRecovery(false,"invitation","7."+invitation,"InvitedPassword123");
        assertEquals("active",admin.queryForObject("SELECT status FROM nw_tenant_members WHERE id=11",String.class));
        assertTrue(PASSWORDS.matches("InvitedPassword123",admin.queryForObject("SELECT password_hash FROM nw_tenant_members WHERE id=11",String.class)));
        assertUnauthorized(()->security(redis,production).completeRecovery(false,"invitation","7."+invitation,"DifferentPassword123"));
    }

    @Test
    void quotaResetPersistsNullAndKeepsOtherOverride() {
        TenantContext.setTenantId("7");
        admin.update("UPDATE nw_tenants SET override_quota_api_calls_per_month=42,override_rate_limit_per_minute=77 WHERE id=7");
        var accounts = accounts(sessions.getMapper(TenantMemberMapper.class));
        var result = accounts.updateTenant(7L, new UpdateTenantRequest(null, null, null, null,
                null, null, java.util.Set.of("override_quota_api_calls_per_month")));
        assertNull(result.overrideQuotaApiCallsPerMonth());
        assertNull(admin.queryForObject("SELECT override_quota_api_calls_per_month FROM nw_tenants WHERE id=7", Integer.class));
        assertEquals(77, admin.queryForObject("SELECT override_rate_limit_per_minute FROM nw_tenants WHERE id=7", Integer.class));
        assertTrue(accounts.listPlans().stream().anyMatch(plan -> plan.planCode().equals("auth-test-plan")));
    }

    @Test
    void productionMappersRollbackAccountWritesAndSelfServicePasswordWhenAuditInsertFails() {
        admin.execute("CREATE TRIGGER reject_account_audit BEFORE INSERT ON nw_audit_logs FOR EACH ROW EXECUTE FUNCTION reject_account_audit()");
        TenantContext.setTenantId("7");
        var accounts = accounts(sessions.getMapper(TenantMemberMapper.class));
        List<Runnable> writes = List.of(
                () -> accounts.setTenantStatus(7L, "suspended"),
                () -> accounts.updateTenant(7L, new UpdateTenantRequest("新机构", null, null, null, null, null, null)),
                () -> accounts.createMember(7L, new CreateTenantMemberRequest("new-member", "Password1234", List.of("developer"), null)),
                () -> accounts.updateMember(7L, 11L, new UpdateTenantMemberRequest("disabled", List.of("auditor"), "new@x")),
                () -> accounts.resetPassword(7L, 11L, new ResetPasswordRequest("Password1234")),
                () -> changePasswordAgainstAuthoritySession());
        for (var write : writes) {
            var failure = assertThrows(RuntimeException.class, write::run);
            boolean rejectedByAudit = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().contains("audit unavailable")) rejectedByAudit = true;
            }
            assertTrue(rejectedByAudit, "必须由真实审计触发器拒绝，不能以前置 Mapper 失败代替回滚验证");
            assertEquals("机构", admin.queryForObject("SELECT name FROM nw_tenants WHERE id=7", String.class));
            assertEquals("active", admin.queryForObject("SELECT status FROM nw_tenants WHERE id=7", String.class));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_tenant_members WHERE tenant_id=7", Integer.class));
            assertTrue(PASSWORDS.matches("old-password", admin.queryForObject("SELECT password_hash FROM nw_tenant_members WHERE id=11", String.class)));
            assertEquals("developer", admin.queryForObject("SELECT role FROM nw_tenant_members WHERE id=11", String.class));
            assertEquals("active", admin.queryForObject("SELECT status FROM nw_tenant_members WHERE id=11", String.class));
        }
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE tenant_id=7", Integer.class));
    }

    @Test
    void usernameRaceUsesProductionUniqueConstraintAndCommitsOnlyWinnerAndItsAudit() throws Exception {
        var mapper = sessions.getMapper(TenantMemberMapper.class);
        var bothChecked = new CyclicBarrier(2);
        var gated = mock(TenantMemberMapper.class, org.mockito.AdditionalAnswers.delegatesTo(mapper));
        doAnswer(call -> {
            Long count = mapper.selectCount(call.getArgument(0));
            bothChecked.await(10, TimeUnit.SECONDS);
            return count;
        }).when(gated).selectCount(any());
        var accounts = accounts(gated);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> create = () -> {
                TenantContext.setTenantId("7");
                try {
                    accounts.createMember(7L, new CreateTenantMemberRequest("same-name", "Password1234", List.of("developer"), null));
                    return 201;
                } catch (ApiException failure) { return failure.getStatus().value(); }
                finally { TenantContext.clear(); }
            };
            var first = pool.submit(create); var second = pool.submit(create);
            assertEquals(List.of(201, 409), List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)).stream().sorted().toList());
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_tenant_members WHERE tenant_id=7 AND username='same-name'", Integer.class));
            assertEquals(1, admin.queryForObject("SELECT count(*) FROM nw_audit_logs WHERE tenant_id=7", Integer.class));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    void deliveryCountRecordMapsEightZeroColumnsWhenNoPersistentFactsMatch() {
        TenantContext.setTenantId("7");
        var counts = sessions.getMapper(SessionOutboxMapper.class).deliveryCounts(7, "missing", "missing");
        assertEquals(new com.skillforge.core.chat.persistence.DeliveryCounts(0, 0, 0, 0, 0), counts);
    }

    @Test
    void batchMountReadUsesProductionRlsAndNeverInitializesAbsentSets() {
        admin.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name,status) VALUES(71,7,'batch-one','成员一','published'),"
                + "(72,7,'batch-two','成员二','published'),(81,8,'batch-foreign','外部成员','published')");
        admin.update("INSERT INTO nw_agent_capability_mount_sets(tenant_id,agent_id,generation) VALUES(7,71,1),(8,81,1)");
        var repository = new com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceRepository(
                sessions.getMapper(com.skillforge.core.agent.capabilitymount.AgentCapabilityMountSetMapper.class),
                sessions.getMapper(com.skillforge.core.agent.capabilitymount.AgentCapabilityMountMapper.class),
                sessions.getMapper(com.skillforge.core.agent.mapper.AgentMapper.class),
                new com.skillforge.core.capability.ToolDeploymentLookup(sessions.getMapper(ToolDeploymentRecordMapper.class)));
        TenantContext.setTenantId("7");
        var result = repository.readAllIfPresent(7L, List.of(71L, 72L, 81L));
        assertEquals(java.util.Set.of(71L), result.keySet());
        assertEquals(1, result.get(71L).generation());
        assertTrue(result.get(71L).mounts().isEmpty());
        assertEquals(2, admin.queryForObject("SELECT count(*) FROM nw_agent_capability_mount_sets WHERE tenant_id IN(7,8)", Integer.class));
        admin.update("DELETE FROM nw_agents WHERE id IN(71,72,81)");
    }

    private FoundationSecurityService security(StringRedisTemplate storage,SecurityMapper mapper) {
        return security(storage,mapper,mock(NotificationService.class));
    }
    private FoundationSecurityService security(StringRedisTemplate storage,SecurityMapper mapper,NotificationService notifications) {
        var properties=new SkillForgeProperties();properties.setSecretKey("synthetic-auth-test-master-key-32-bytes-or-more");
        var access=mock(FoundationAccess.class);
        when(access.snapshot(any())).thenReturn(new FoundationAccess.Snapshot(List.of("developer"),List.of(),1));
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","11"));
        return transactional(new FoundationSecurityService(mapper,access,PASSWORDS,new CryptoService(properties),properties,storage,
                notifications,transactional(new AuditService(sessions.getMapper(AuditLogMapper.class)))));
    }
    private void seedEnabledMfa() {
        var properties=new SkillForgeProperties();properties.setSecretKey("synthetic-auth-test-master-key-32-bytes-or-more");
        admin.update("INSERT INTO nw_foundation_security_mfa(scope_key,member_id,secret_encrypted,enabled,last_counter) VALUES('tenant:7',11,?,true,-1)",new CryptoService(properties).encrypt(Totp.newSecret()));
    }
    private void seedSession(boolean platform,String raw) throws Exception {
        String scope=platform?"platform":"tenant:7";long member=platform?21:11,id=platform?7001:7000;
        String table=platform?"platform_members":"tenant_members";
        String credential=hash(admin.queryForObject("SELECT password_hash FROM "+table+" WHERE id=?",String.class,member));
        admin.update("DELETE FROM nw_foundation_security_sessions WHERE id=?",id);
        admin.update("INSERT INTO nw_foundation_security_sessions(id,scope_key,member_id,credential_hash,refresh_hash,state,device_label,expires_at) VALUES(?,?,?,?,?,'full','test browser',now()+interval '1 hour')",id,scope,member,credential,hash(raw));
    }
    private void changePasswordAgainstAuthoritySession() {
        try {
            seedSession(false,RefreshTokens.create());
            try(var stp=mockStatic(StpUtil.class)) {
                stp.when(()->StpUtil.getExtra(FoundationSecurityService.SESSION_CLAIM)).thenReturn("7000");
                security(redis,sessions.getMapper(SecurityMapper.class)).changePassword("old-password","NewPassword123");
            }
        } catch(RuntimeException failure){throw failure;}catch(Exception invalid){throw new IllegalStateException(invalid);}
    }
    private static String hash(String raw) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}
    private PlatformTenantAccountService accounts(TenantMemberMapper members) {
        var organization=mock(com.skillforge.core.foundation.access.FoundationOrganizationService.class);
        when(organization.changeAccount(any(),anyBoolean(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(2)).get());
        return transactional(new PlatformTenantAccountService(sessions.getMapper(TenantMapper.class), members,
                sessions.getMapper(PlanMapper.class), PASSWORDS, new AuditService(sessions.getMapper(AuditLogMapper.class)),
                organization,mock(com.skillforge.core.foundation.catalog.entitlements.ResourceQuota.class)));
    }
    @SuppressWarnings("unchecked") private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
    private void assertUnauthorized(Runnable action) {
        assertEquals(401, assertThrows(ApiException.class, action::run).getStatus().value());
    }
}
