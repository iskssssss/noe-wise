package com.skillforge.core.chat.observation;

import com.skillforge.core.chat.observation.SessionObservationQuery;
import com.skillforge.core.chat.observation.SessionObservationScope;
import com.skillforge.core.chat.observation.SessionObservationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.platform.PlatformApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实浏览器经过登录、生产 Controller 与 PostgreSQL，不拦截或替换业务接口。 */
@EnabledIfEnvironmentVariable(named = "SESSION_OBSERVATION_BROWSER", matches = "1")
@SpringBootTest(classes = PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent-runtime.workers.auto-startup=false",
                "skillforge.secret-key=synthetic-session-browser-master-key-32",
                "skillforge.platform-admin-username=", "skillforge.platform-admin-password="})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionObservationBrowserIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("session_browser").withUsername("skillforge").withPassword("test-only");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.cloud.nacos.discovery.enabled", () -> "false");
        registry.add("spring.cloud.nacos.config.enabled", () -> "false");
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private SessionObservationService observation;

    @Test
    void browserReadsAuthenticatedSessionFactsWithoutPrecisionLoss() throws Exception {
        jdbc.update("""
                INSERT INTO nw_billing_plans(id, plan_code, display_name, slo_availability_target)
                VALUES (?, 'session-browser', '浏览器验收套餐', 0.99)
                """, SessionObservationFixture.PLAN_ID);
        new SessionObservationFixture(jdbc).populate(501L, "session-browser", "root-browser-a",
                "root-browser-b", 9007199254740993L, 0L, 0L);
        // 同一真实生产 service 消费当前 Runtime 事件；页面验收不依赖 assistant 行伪造完成状态。
        var platform = new SessionObservationScope(SessionObservationScope.Kind.PLATFORM_ADMIN, null, "browser-admin");
        var detail = observation.detail(platform, new SessionObservationQuery.Resource(501L, "session-browser"));
        assertEquals("COMPLETED", detail.session().answerStatus());
        assertEquals("COMPLETED", detail.session().executionStatus());
        assertEquals(4, detail.session().messageCount());
        var messages = observation.messages(platform, new SessionObservationQuery.Messages(
                501L, "session-browser", "root-browser-a", null, 10));
        assertEquals(2, messages.items().size());
        assertTrue(messages.items().stream().anyMatch(message -> "assistant".equals(message.role())
                && "completed".equals(message.state()) && "answer root-browser-a".equals(message.content())));
        jdbc.update("""
                UPDATE nw_usage_events SET cached_input_tokens=0, cache_write_input_tokens=0,
                    reasoning_output_tokens=0 WHERE tenant_id=501 AND usage_event_kind='usage_report'
                """);
        String username = "session-browser-admin";
        String password = java.util.UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO nw_platform_members(id, username, password_hash, display_name, status)
                VALUES (995001, ?, ?, '会话浏览器验收', 'active')
                """, username, passwords.encode(password));
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (repository != null && !Files.isDirectory(repository.resolve("frontend-platform"))) {
            repository = repository.getParent();
        }
        if (repository == null) throw new IllegalStateException("无法定位前端工作区");
        Path reports = repository.resolve("backend-java/skillforge-platform-service/build/reports");
        Files.createDirectories(reports);
        Path artifacts = Files.createTempDirectory(reports, "session-browser-");
        ProcessBuilder builder = new ProcessBuilder("node", "e2e/session-observation-live.mjs")
                .directory(repository.resolve("frontend-platform").toFile())
                .redirectErrorStream(true).redirectOutput(artifacts.resolve("browser.log").toFile());
        builder.environment().put("SESSION_BROWSER_BACKEND", "http://127.0.0.1:" + port);
        builder.environment().put("SESSION_BROWSER_USERNAME", username);
        builder.environment().put("SESSION_BROWSER_PASSWORD", password);
        builder.environment().put("SESSION_BROWSER_ARTIFACTS", artifacts.toString());
        Process browser = builder.start();
        try {
            assertTrue(browser.waitFor(300, TimeUnit.SECONDS), "前端构建或浏览器超时；参见 " + artifacts);
            assertEquals(0, browser.exitValue(), () -> {
                try { return Files.readString(artifacts.resolve("browser.log")); }
                catch (java.io.IOException failure) { return failure.toString(); }
            });
        } finally {
            if (browser.isAlive()) {
                browser.descendants().forEach(ProcessHandle::destroyForcibly);
                browser.destroyForcibly();
                browser.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
