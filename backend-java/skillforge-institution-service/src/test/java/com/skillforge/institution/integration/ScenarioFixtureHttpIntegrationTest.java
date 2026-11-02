package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 独立平台JVM与真实机构HTTP共同运行Python接入示例，数据库与模型协议均为隔离测试资源。 */
@Import(DeterministicModelCatalogConfiguration.class)
class ScenarioFixtureHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String TEST_JWT_SECRET = "synthetic-scenario-jwt-secret-20261022-signing-key";
    private static final Logger LOG = LoggerFactory.getLogger(ScenarioFixtureHttpIntegrationTest.class);
    @Autowired private ObjectMapper json;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private SkillForgeProperties properties;
    @LocalServerPort private int institutionPort;

    /** 机构真实Worker推进模型调用和Outbox，平台子进程关闭Worker避免重复认领。 */
    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("sa-token.jwt-secret-key", () -> TEST_JWT_SECRET);
        registry.add("skillforge.management-jwt-issuer", () -> "skillforge-platform");
        registry.add("skillforge.management-jwt-audience", () -> "skillforge-platform-api");
    }

    /** 公共示例从真实平台登录开始，所有平台定义/机构/授权/部署/聊天/用量操作均通过HTTP。 */
    @Test
    void runsThePublicPythonScenarioAgainstTwoRealHosts() throws Exception {
        LOG.debug("运行双宿主接入示例进入");
        Path root = repositoryRoot();
        Path platformJar = root.resolve("backend-java/skillforge-platform-service/build/libs/skillforge-platform-service.jar");
        assertThat(platformJar).as("验收前先构建本次platform bootJar").exists();
        Path evidence = root.resolve("backend-java/skillforge-institution-service/build/reports/integration-scenario")
                .resolve(Long.toString(SnowflakeIds.nextId()));
        Files.createDirectories(evidence);
        String namespace = "public-scenario-" + SnowflakeIds.nextId();
        String bootstrapTenant = createTenant(tenants, plans, namespace + "-model-owner");
        try (var provider = new NativeChatOpenAiProvider(json, namespace)) {
            provider.start();
            String model;
            TenantContext.setTenantId(bootstrapTenant);
            try {
                model = PlatformModelFixtures.selectLlm(models, bootstrapTenant, provider.endpoint(), "scenario-local-model",
                        "synthetic-fixture-key", "custom", "custom-openai-compatible", "custom-v1");
            } finally {
                TenantContext.clear();
            }
            int platformPort = freePort();
            String username = "scenario-platform-admin";
            String password = "fixture-" + UUID.randomUUID();
            Process platform = startPlatform(root, platformJar, evidence, platformPort, username, password);
            try {
                String platformOrigin = "http://127.0.0.1:" + platformPort;
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
                await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).until(() -> {
                    assertThat(platform.isAlive()).as("平台子进程应存活，诊断见platform.log").isTrue();
                    try {
                        var response = http.send(HttpRequest.newBuilder(URI.create(platformOrigin + "/actuator/health"))
                                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                        return response.statusCode() == 200;
                    } catch (IOException unavailable) {
                        return false;
                    }
                });
                // Health可先于CommandLineRunner完成；等首个平台管理员实际可登录再调用场景。
                var readyToken = new AtomicReference<String>();
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                        .untilAsserted(() -> readyToken.set(login(http, platformOrigin, username, password)));
                String token = readyToken.get();
                Path config = evidence.resolve("config.local.json");
                Files.writeString(config, json.writeValueAsString(Map.of(
                        "platform_origin", platformOrigin, "institution_origin", "http://127.0.0.1:" + institutionPort,
                        "model_config_id", model, "scenario_namespace", namespace,
                        "customer_cents_per_thousand_tokens", "1.50", "initial_balance_cents", "10000",
                        "run_timeout_seconds", 120)));
                Path state = evidence.resolve("scenario.sqlite3");
                runPython(root, evidence, config, state, token, "run");
                // 独立Python进程重新消费同一持久checkpoint，再验证既有发送与Run不重复执行。
                int invocations = provider.requestCount();
                runPython(root, evidence, config, state, token, "usage");
                runPython(root, evidence, config, state, token, "run");
                assertThat(provider.requestCount()).as("示例重启必须复用原Run和已消费事实").isEqualTo(invocations);
                assertThat(Files.readString(evidence.resolve("run.log"))).contains("\"state\": \"completed\"");
                assertThat(invocations).as("模型必须实际收到本地HTTP请求").isGreaterThanOrEqualTo(7);
                assertCrossHostSessionRevocation(http, platformOrigin, username, password, token);
            } finally {
                stop(platform);
            }
        } finally {
            TenantContext.clear();
            LOG.debug("运行双宿主接入示例退出");
        }
    }

    /** 独立进程避免两个宿主的SaToken静态配置互相覆盖，环境只绑定本测试容器。 */
    private Process startPlatform(Path root, Path jar, Path evidence, int port, String username, String password) throws IOException {
        LOG.debug("启动测试平台宿主进入");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var builder = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--server.port=" + port,
                "--agent-runtime.workers.auto-startup=false", "--spring.flyway.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.nacos.config.enabled=false");
        builder.directory(root.resolve("backend-java/skillforge-platform-service").toFile());
        builder.redirectErrorStream(true).redirectOutput(evidence.resolve("platform.log").toFile());
        var environment = builder.environment();
        environment.put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        // 平台专用Datasource后处理器使用这两个权威键，不能继承开发库身份。
        environment.put("PLATFORM_DATASOURCE_USERNAME", POSTGRES.getUsername());
        environment.put("PLATFORM_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        environment.put("REDIS_HOST", REDIS.getHost());
        environment.put("REDIS_PORT", REDIS.getMappedPort(6379).toString());
        environment.put("REDIS_DATABASE", "0");
        environment.put("SECRET_KEY", properties.getSecretKey());
        environment.put("PLATFORM_SECRET_KEY", TEST_PLATFORM_JWT_SECRET);
        environment.put("PLATFORM_ADMIN_USERNAME", username);
        environment.put("PLATFORM_ADMIN_PASSWORD", password);
        Process result = builder.start();
        LOG.debug("启动测试平台宿主退出");
        return result;
    }

    /** 平台JWT从真实认证端点取得，令牌只进入Python环境，不写报告或标准输出。 */
    private String login(HttpClient http, String origin, String username, String password) throws Exception {
        LOG.debug("登录测试平台进入");
        var response = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/platform/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", username, "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("平台真实登录状态").isEqualTo(200);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("code").asText()).isEqualTo("OK");
        String token = envelope.path("data").path("access_token").asText();
        assertThat(token).isNotBlank();
        LOG.debug("登录测试平台退出");
        return token;
    }

    /** 平台独立设备会话从真实登录到正式logout，机构宿主必须读取同一数据库撤销事实。 */
    private void assertCrossHostSessionRevocation(HttpClient http, String platformOrigin, String username,
            String password, String originalToken) throws Exception {
        var institutions = http.send(HttpRequest.newBuilder(URI.create(platformOrigin + "/api/v1/platform/institutions?page=1&page_size=20"))
                .timeout(Duration.ofSeconds(20)).header("X-Platform-Token", "Bearer " + originalToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(institutions.statusCode()).as("原平台会话应能读取当前获准机构").isEqualTo(200);
        JsonNode items = json.readTree(institutions.body()).path("data").path("items");
        assertThat(items.size()).isGreaterThan(0);
        String tenantId = items.get(0).path("tenant_id").asText();
        assertThat(tenantId).matches("[1-9][0-9]*");
        String target = "http://127.0.0.1:" + institutionPort + "/api/v1/platform/agent-deployments?tenant_id=" + tenantId;

        // 新登录拥有独立安全session，不撤销运行公共示例所使用的原平台设备。
        String independentToken = login(http, platformOrigin, username, password);
        assertThat(deploymentRead(http, target, independentToken).statusCode()).as("异密钥的真实平台JWT应可跨宿主管理明确目标").isEqualTo(200);
        var logout = http.send(HttpRequest.newBuilder(URI.create(platformOrigin + "/api/v1/platform/auth/logout"))
                .timeout(Duration.ofSeconds(20)).header("X-Platform-Token", "Bearer " + independentToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).as("独立平台设备应通过正式logout完成数据库会话撤销").isEqualTo(200);
        var revoked = deploymentRead(http, target, independentToken);
        assertThat(revoked.statusCode()).as("JWT未到期时，机构宿主也必须拒绝已撤销的平台数据库安全会话").isEqualTo(401);
        assertThat(json.readTree(revoked.body()).path("code").asText()).isEqualTo("AUTH_SESSION_INVALID");
        assertThat(deploymentRead(http, target, originalToken).statusCode()).as("撤销另一设备不能使原平台会话失效").isEqualTo(200);
    }

    /** 精确平台部署读路径仍走正式人员认证和Foundation会话门禁，不使用机构执行身份。 */
    private HttpResponse<String> deploymentRead(HttpClient http, String target, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(20))
                .header("X-Platform-Token", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 子进程使用真实HTTP调用公共示例，限定总耗时，日志只有安全诊断和业务汇总。 */
    private void runPython(Path root, Path evidence, Path config, Path state, String token, String command) throws Exception {
        LOG.debug("运行Python接入命令进入 command={}", command);
        String executable = System.getenv().getOrDefault("SKILLFORGE_SCENARIO_PYTHON", "python");
        var builder = new ProcessBuilder(executable, root.resolve("tools/integration-scenario/scenario.py").toString(), command,
                "--config", config.toString(), "--state", state.toString());
        builder.directory(root.toFile());
        builder.environment().put("SKILLFORGE_SCENARIO_PLATFORM_TOKEN", token);
        builder.environment().put("PYTHONUTF8", "1");
        builder.redirectErrorStream(true).redirectOutput(evidence.resolve(command + ".log").toFile());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(240, TimeUnit.SECONDS)).as("Python接入示例必须按时结束").isTrue();
            assertThat(process.exitValue()).as("公共示例命令应通过，诊断见%s.log", command).isZero();
        } finally {
            stop(process);
        }
        LOG.debug("运行Python接入命令退出 command={}", command);
    }

    /** 关闭仅由本测试创建的进程，超时升级终止，绝不使用共享服务PID。 */
    private void stop(Process process) throws InterruptedException {
        LOG.debug("关闭测试子进程进入");
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        LOG.debug("关闭测试子进程退出");
    }

    /** 获取本地临时服务端口，绑定只用于本测试子进程启动；启动失败由就绪检查明确报告。 */
    private int freePort() throws IOException {
        LOG.debug("选择测试端口进入");
        try (var socket = new ServerSocket(0)) {
            int result = socket.getLocalPort();
            LOG.debug("选择测试端口退出");
            return result;
        }
    }

    /** 从测试模块工作目录解析固定仓库入口，不依赖调用机器的绝对路径。 */
    private Path repositoryRoot() {
        LOG.debug("解析接入示例仓库进入");
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("tools/integration-scenario/scenario.py"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("integration scenario repository root not found");
        LOG.debug("解析接入示例仓库退出");
        return path;
    }
}
