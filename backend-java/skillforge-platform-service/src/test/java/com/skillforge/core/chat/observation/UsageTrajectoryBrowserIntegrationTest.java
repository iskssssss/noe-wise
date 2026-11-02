package com.skillforge.core.chat.observation;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.platform.PlatformApplication;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 隔离浏览器访问真实平台接口和 Runtime 事实，不调用外部模型。 */
@EnabledIfEnvironmentVariable(named="SESSION_OBSERVATION_BROWSER", matches="1")
@SpringBootTest(classes=PlatformApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"agent-runtime.workers.auto-startup=false", "skillforge.secret-key=synthetic-session-browser-master-key-32",
                "skillforge.platform-admin-username=", "skillforge.platform-admin-password="})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class UsageTrajectoryBrowserIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("usage_browser").withUsername("skillforge").withPassword("test-only");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.data.redis.host",REDIS::getHost);
        registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("spring.cloud.nacos.discovery.enabled",()->"false");
        registry.add("spring.cloud.nacos.config.enabled",()->"false");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired FoundationOrganizationService organization;

    @Test void browserFindsTheActualUsageAttempt() throws Exception {
        jdbc.update("INSERT INTO nw_billing_plans(id,plan_code,display_name,slo_availability_target) VALUES (?, 'usage-browser', '浏览器验收套餐', 0.99)", SessionObservationFixture.PLAN_ID);
        var fixture=new SessionObservationFixture(jdbc);
        fixture.populate(501L,"session-browser","root-browser-a","root-browser-b",2L,3L,4L);
        for (int i=0;i<20;i++) fixture.appendCompletedRound(501L,"session-browser","root-page-"+i, 2_000_000L+i);
        jdbc.update("UPDATE nw_usage_events SET origin_kind='native' WHERE tenant_id=501 AND usage_event_kind='usage_report'");
        jdbc.update("INSERT INTO nw_usage_publication_pending(tenant_id,usage_id,event_id) SELECT tenant_id,id,tenant_id::text||':'||event_id FROM nw_usage_events WHERE tenant_id=501 AND usage_event_kind='usage_report'");
        jdbc.queryForObject("SELECT public.publish_committed_usage(1000)",Long.class);
        String username="usage-browser-admin", password=UUID.randomUUID().toString();
        assertTrue(organization.initializePlatformAdministrator("995001",()->jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,display_name,status) VALUES (995001,?,?, '浏览器验收','active')",username,passwords.encode(password))));
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("frontend-platform")))root=root.getParent();
        assertNotNull(root);
        Path artifacts=Files.createTempDirectory(root.resolve("backend-java/skillforge-platform-service/build"),"usage-browser-");
        var builder=new ProcessBuilder("node","e2e/usage-trajectory-live.mjs").directory(root.resolve("frontend-platform").toFile())
                .redirectErrorStream(true).redirectOutput(artifacts.resolve("browser.log").toFile());
        builder.environment().put("E2E_BACKEND_URL","http://127.0.0.1:"+port);
        builder.environment().put("E2E_USERNAME",username);
        builder.environment().put("E2E_PASSWORD",password);
        var process=builder.start();
        try {
            assertTrue(process.waitFor(180,TimeUnit.SECONDS),"浏览器验收超时："+artifacts);
            assertEquals(0,process.exitValue(),()->{try{return Files.readString(artifacts.resolve("browser.log"));}catch(Exception failure){return failure.toString();}});
        } finally {
            if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
        }
    }
}
