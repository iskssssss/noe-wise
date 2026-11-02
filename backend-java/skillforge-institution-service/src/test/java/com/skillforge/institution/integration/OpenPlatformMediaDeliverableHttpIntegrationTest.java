package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.application.ServicePrincipalService.IssuedPrincipal;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.artifact.ArtifactPreviewServer;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 真实HTTP、Python消费者和外部Sandbox形成附件到正式媒体成果闭环；模型是确定协议服务器。 */
@Import(CodeExecutionChatIntegrationTest.VerifiedModelProviderConfiguration.class)
class OpenPlatformMediaDeliverableHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String RUNNER = System.getenv("CODE_EXECUTION_SMOKE_ENDPOINT");
    private static final String RUNNER_TOKEN = System.getenv("CODE_EXECUTION_SMOKE_TOKEN");
    private static final Path EVIDENCE = evidenceDirectory();
    private static final PreviewBoundary PREVIEW = new PreviewBoundary();
    private static final String PASSWORD = "MediaHttpFixture123";
    private static final Set<String> SCOPES = Set.of("sessions.write", "sessions.read", "artifacts.read",
            "end_users.write", "business.scopes.write");
    @Autowired ObjectMapper json;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired InstitutionIntegrationMapper integrations;
    @Autowired ServicePrincipalService principals;
    @Autowired PlatformModelCatalogService models;
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired OperationalSettingsService settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired AgentRuntimeProperties runtime;
    /** 本场景只替代未验收的预览监听器；持久化、执行和HMAC下载仍使用正式实现。 */
    @MockitoBean ArtifactPreviewServer unusedPreviewServer;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 端口由宿主及Provider绑定0分配；只有文件执行端点来自父拥有的隔离Sandbox。 */
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) {
        registry.add("skillforge.code-execution.endpoint", () -> RUNNER == null ? "http://127.0.0.1:9" : RUNNER);
        registry.add("skillforge.code-execution.service-token", () -> RUNNER_TOKEN == null ? "unconfigured-media-runner-token-00000000" : RUNNER_TOKEN);
        registry.add("skillforge.code-execution.artifact-root", () -> EVIDENCE.resolve("artifacts").toString());
        registry.add("skillforge.code-execution.preview-port", () -> Integer.toString(PREVIEW.port()));
        registry.add("skillforge.code-execution.ticket-key", () -> "gi08-media-test-ticket-key-32-characters-20261023");
        registry.add("skillforge.code-execution.platform-origin", () -> "http://localhost");
        registry.add("skillforge.code-execution.download-origin", () -> "http://localhost");
        registry.add("skillforge.code-execution.preview-origin", () -> "http://127.0.0.1:" + PREVIEW.port());
        registry.add("agent-runtime.worker-lease-duration", () -> "210s");
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
    }

    @AfterEach void clear() { TenantContext.clear(); }
    @AfterAll static void releasePreviewBoundary() throws Exception { PREVIEW.close(); }

    /** 输入、代码执行、冻结、完成与正式下载均为真实路径；拒绝后同时检查没有写入。 */
    @Test @Timeout(300)
    void pythonConsumesAuthorizedUploadsAndRuntimePublishedMediaWithoutScopeEscape() throws Exception {
        assumeTrue(RUNNER != null && RUNNER_TOKEN != null && RUNNER_TOKEN.length() >= 32,
                "requires parent-owned isolated CODE_EXECUTION_SMOKE_ENDPOINT/TOKEN");
        String marker = Long.toString(SnowflakeIds.nextId());
        String slug = "gi08-media-" + marker;
        String tenant = createTenant(tenants, plans, slug);
        String foreignTenant = createTenant(tenants, plans, slug + "-foreign");
        String integration = "media-" + marker;
        IssuedPrincipal owner = credential(tenant, integration, SCOPES);
        IssuedPrincipal otherIntegration = credential(tenant, integration + "-other", SCOPES);
        IssuedPrincipal foreign = credential(foreignTenant, integration, SCOPES);
        IssuedPrincipal readonly = credential(tenant, integration, Set.of("sessions.read", "artifacts.read"));
        String user = "media-user-" + marker;
        TenantContext.setTenantId(tenant);
        createTestTenantAdministrator(tenant, user, PASSWORD);
        TenantContext.clear();
        String token = member("POST", "/auth/login", Map.of("tenant_slug", slug, "username", user, "password", PASSWORD), null, 200)
                .path("access_token").asText();
        assertThat(token).isNotBlank();
        var openApi = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(openApi.statusCode()).isEqualTo(200); Files.write(EVIDENCE.resolve("runtime-openapi.json"), openApi.body());
        long actor = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')", actor, "media-settings-" + marker, "unused");
        var original = settings.read("sandbox");
        var enabled = new LinkedHashMap<>(original.values()); enabled.put("enabled", true);
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("tenant_id", tenant); evidence.put("evidence_kind", "real_host_python_runtime_sandbox");
        evidence.put("preview_boundary", "owned_port_listener_only_preview_not_verified");
        Throwable testFailure = null;
        try (MediaModel model = new MediaModel(json)) {
            settings.update("sandbox", original.revision(), enabled, actor);
            model.start();
            TenantContext.setTenantId(tenant);
            try {
                PlatformModelFixtures.selectLlm(models, tenant, model.endpoint(), "gi08-media-model", "synthetic-media-model-key", "custom", "custom-openai-compatible", "custom-v1");
                provisioner.provision(tenant);
                requireRunningRuntimeWorkers(context, runtime);
            } finally { TenantContext.clear(); }
            String agent = member("POST", "/agents", Map.of("agent_key", "media-" + marker, "name", "媒体正式交付验证",
                    "system_prompt", "根据授权输入生成明确模拟的媒体成果，执行代码后冻结正式交付文件。"), token, 200).path("id").asText();
            mount(token, agent, "code_execute", marker);
            mount(token, agent, "finalize_task_outputs", marker);
            member("POST", "/agents/publish", Map.of("agent_id", agent), token, 200);
            JsonNode scope = machine(owner, "POST", "/open-platform/business-scopes/ensure", Map.of("scope_type", "store", "external_ref", marker, "display_name", "媒体门店"), "scope-" + marker, 200);
            JsonNode endUser = machine(owner, "POST", "/open-platform/end-users/ensure", Map.of("external_user_ref", user, "display_name", "媒体验证用户"), null, 200);
            String session = machine(owner, "POST", "/open-platform/sessions", Map.of("external_user_ref", user, "external_conversation_ref", "media-" + marker,
                    "agent_id", agent, "end_user_id", endUser.path("end_user_id").asText(), "business_scope_type", "store", "business_scope_ref", marker), null, 200).path("session_id").asText();
            evidence.put("session_id", session);
            Path attachment = EVIDENCE.resolve("input.json");
            String upload = UUID.randomUUID().toString();
            Files.writeString(attachment, json.writeValueAsString(Map.of("marker", marker, "simulated", true,
                    "description", "模拟转写与视频分析结果，生成音频为静音样本")));
            JsonNode uploadProof = python(owner, session, "attachment", 0, List.of("upload", "--upload-id", upload, "--file", attachment.toString()));
            assertThat(uploadProof.path("receipt").path("id").asText()).isEqualTo(upload);
            assertThat(uploadProof.path("receipt").path("sha256").asText()).isEqualTo(sha(Files.readAllBytes(attachment)));
            JsonNode repeated = python(owner, session, "attachment-replay", 0, List.of("upload", "--upload-id", upload, "--file", attachment.toString()));
            assertThat(repeated.path("receipt")).isEqualTo(uploadProof.path("receipt"));
            Path png = EVIDENCE.resolve("input.png");
            var pixels = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB); pixels.setRGB(1, 1, 0x33aabb);
            assertThat(ImageIO.write(pixels, "png", png.toFile())).isTrue();
            JsonNode imageProof = python(owner, session, "image", 0, List.of("upload", "--upload-id", UUID.randomUUID().toString(), "--file", png.toString(), "--image"));
            String mediaId = imageProof.path("receipt").path("media_id").asText();
            assertThat(mediaId).isNotBlank();
            assertThat(imageProof.path("receipt").path("width").asInt()).isEqualTo(4);
            assertThat(imageProof.path("receipt").path("height").asInt()).isEqualTo(3);
            var imageBytes = raw(owner, "GET", "/open-platform/sessions/media/content?sessionId=" + session + "&mediaId=" + mediaId, null, null);
            assertHttpStatus(imageBytes,200);
            assertThat(ImageIO.read(new java.io.ByteArrayInputStream(imageBytes.body())).getWidth()).isEqualTo(4);
            long beforeRejected = queryCount(jdbc, tenant, "SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id=?", Long.parseLong(tenant));
            assertDeniedUpload(readonly, session, attachment, "read-only", 403);
            assertDeniedUpload(otherIntegration, session, attachment, "other-integration", 403);
            assertDeniedUpload(foreign, session, attachment, "foreign-tenant", 404);
            assertThat(queryCount(jdbc, tenant, "SELECT count(*) FROM nw_chat_session_attachments WHERE tenant_id=?", Long.parseLong(tenant))).isEqualTo(beforeRejected);
            Path changed = EVIDENCE.resolve("changed.json"); Files.writeString(changed, "{\"changed\":true}");
            assertThat(python(owner, session, "changed-upload", 1, List.of("upload", "--upload-id", upload, "--file", changed.toString()))
                    .path("http_status").asInt()).isEqualTo(409);
            model.attachment.set(upload);
            JsonNode accepted = machine(owner, "POST", "/open-platform/sessions/messages", Map.of("session_id", session,
                    "payload", Map.of("content", List.of(Map.of("type", "text", "text", "生成媒体结果 JSON 和模拟音频 WAV，并正式交付，来源标记 " + marker)),
                            "context", Map.of("attachment_ids", List.of(upload)))), "media-message-" + marker, 200);
            String run = accepted.path("run_id").asText(); assertThat(run).isNotBlank(); evidence.put("run_id", run);
            await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
                if(model.failure.get()!=null)throw new IllegalStateException("确定模型协议夹具失败，见model-failure证据",model.failure.get());
                var facts = queryRows(jdbc, tenant, "SELECT status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenant, run);
                assertThat(facts).hasSize(1);
                assertThat(facts.getFirst().get("status")).isEqualTo("COMPLETED");
                assertThat(queryCount(jdbc, tenant, "SELECT count(*) FROM nw_deliverable_revisions WHERE tenant_id=? AND run_id=?", Long.parseLong(tenant), run)).isEqualTo(2);
            });
            JsonNode collected = python(owner, session, "collect", 0, List.of("collect", "--run-id", run, "--directory", EVIDENCE.resolve("files").toString()));
            assertThat(collected.path("result").path("files").size()).isEqualTo(2);
            JsonNode publishedJson = null;
            for (JsonNode file : collected.path("result").path("files")) {
                byte[] content = Files.readAllBytes(Path.of(file.path("local_file").asText()));
                assertThat(content.length).isEqualTo(file.path("size").asInt());
                assertThat(sha(content)).isEqualTo(file.path("sha256").asText());
                if ("json".equals(file.path("format").asText())) {
                    publishedJson = file;
                    JsonNode report = json.readTree(content); assertThat(report.path("marker").asText()).isEqualTo(marker);
                    assertThat(report.path("simulated").asBoolean()).isTrue();
                } else {
                    assertThat(file.path("format").asText()).isEqualTo("wav");
                    assertThat(new String(content, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
                    assertThat(new String(content, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
                }
            }
            assertThat(publishedJson).isNotNull();
            JsonNode history = machine(owner, "GET", "/open-platform/sessions/history?sessionId=" + session, null, null, 200);
            Files.writeString(EVIDENCE.resolve("public-history.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(history));
            int succeededTools = 0;
            var processIds=new java.util.HashSet<String>();
            for (JsonNode process : history.path("processes")) if (run.equals(process.path("run_id").asText())
                    && "tool".equals(process.path("type").asText()) && "succeeded".equals(process.path("status").asText())) {
                // 历史ProcessItem用process_id/reference_id及更新序号标识事实；runtime_event_id属于增量信封。
                assertThat(process.path("process_id").asText()).isNotBlank();
                assertThat(processIds.add(process.path("process_id").asText())).isTrue();
                assertThat(process.path("reference_id").asText()).isNotBlank();
                assertThat(process.path("input_id")).isEqualTo(accepted.path("input_id"));
                assertThat(process.path("sequence").asLong()).isPositive();
                assertThat(process.path("updated_sequence").asLong()).isGreaterThanOrEqualTo(process.path("sequence").asLong());
                assertThat(process.path("finished_at").asText()).isNotBlank(); succeededTools++;
            }
            assertThat(succeededTools).isEqualTo(2);
            python(owner, session, "collect-replay", 0, List.of("collect", "--run-id", run, "--directory", EVIDENCE.resolve("files-replay").toString()));
            assertThat(model.codeCalls.get()).isEqualTo(1); assertThat(model.finalizeCalls.get()).isEqualTo(1);
            String fileRoute = "/open-platform/sessions/deliverables/file?sessionId=" + session + "&revisionId=" + encode(publishedJson.path("revision_id").asText()) + "&fileId=" + encode(publishedJson.path("file_id").asText());
            assertHttpStatus(raw(otherIntegration, "GET", fileRoute, null, null),403);
            assertHttpStatus(raw(foreign, "GET", fileRoute, null, null),404);
            // 正式成果内不存在的文件选择器由产物边界统一隐藏为GONE，不暴露缺失、撤权或过期的区别。
            assertHttpStatus(raw(owner, "GET", fileRoute.replace("fileId=" + encode(publishedJson.path("file_id").asText()), "fileId=unpublished-intermediate"), null, null),410);
            machine(owner, "POST", "/open-platform/business-scopes/status", Map.of("scope_id", scope.path("business_scope_id").asText(), "status", "revoked", "expected_revision", 1), "scope-revoke-" + marker, 200);
            assertHttpStatus(raw(owner, "GET", fileRoute, null, null),403);
            assertDeniedUpload(owner, session, attachment, "revoked-scope", 403);
            TenantContext.setTenantId(tenant);
            try { assertThat(principals.revoke(tenant, readonly.principal().getId())).isTrue(); }
            finally { TenantContext.clear(); }
            assertHttpStatus(raw(readonly, "GET", "/open-platform/sessions/attachments?sessionId=" + session, null, null),401);
            evidence.put("status", "PASS"); evidence.put("publication", collected); evidence.put("code_calls", model.codeCalls.get());
        } catch (Exception | AssertionError failure) {
            testFailure = failure;
            evidence.put("status", "FAILED"); evidence.put("failure_type", failure.getClass().getName());
            evidence.put("runs", queryRows(jdbc, tenant, "SELECT run_id,status,phase,current_action_id FROM agent_runtime_execution_runs WHERE tenant_id=?", tenant));
            throw failure;
        } finally {
            TenantContext.clear();
            try { settings.update("sandbox", settings.read("sandbox").revision(), original.values(), actor); }
            catch (Exception restore) { evidence.put("settings_restore_failure", restore.getClass().getName()); if (testFailure != null) testFailure.addSuppressed(restore); else throw restore; }
            finally { Files.writeString(EVIDENCE.resolve("host-evidence.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence)); }
        }
    }

    /** 凭据和执行域属于认证前置夹具；业务Session及文件均由公开入口建立。 */
    private IssuedPrincipal credential(String tenant, String integration, Set<String> scopes) {
        TenantContext.setTenantId(tenant);
        try { integrations.registerIntegration(Long.parseLong(tenant), integration); return principals.issue(tenant, integration, scopes); }
        finally { TenantContext.clear(); }
    }

    private void mount(String token, String agent, String name, String marker) throws Exception {
        JsonNode definition = find(member("GET", "/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", null, token, 200), "capability_key", "tool:" + name);
        String versionKey = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(item -> name.equals(item.name())).findFirst().orElseThrow().publishedVersionKey();
        JsonNode version = find(member("GET", "/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definition.path("id").asText(), null, token, 200), "version_key", versionKey);
        String key = "media-" + name.replace('_', '-') + "-" + marker;
        JsonNode deployment = member("POST", "/capabilities/deployments", Map.of("deployment_key", key, "tool_version_id", version.path("id").asText(),
                "provider_type", "PLATFORM", "provider_ref", name, "environment", "TEST", "status", "ACTIVE"), token, 201);
        member("POST", "/agents/capability-mounts", Map.of("agent_id", agent, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", definition.path("id").asText(), "capability_version_id", version.path("id").asText(),
                "tool_config", Map.of("tool_deployment_id", key, "tool_deployment_revision", deployment.path("revision").asLong()), "priority", 0)), token, 201);
    }

    private void assertDeniedUpload(IssuedPrincipal credential, String session, Path file, String label, int status) throws Exception {
        assertThat(python(credential, session, label, 1, List.of("upload", "--upload-id", UUID.randomUUID().toString(), "--file", file.toString())).path("http_status").asInt()).isEqualTo(status);
    }

    /** 真Python进程只持有此用例凭据；日志不写明文签名或环境，结束或超时均等待进程退出。 */
    private JsonNode python(IssuedPrincipal credential, String session, String name, int expectedExit, List<String> arguments) throws Exception {
        Path root = repositoryRoot(); Path proof = EVIDENCE.resolve(name + ".json");
        var command = new ArrayList<>(List.of("python", "-X", "utf8", "-B", root.resolve("tools/integration-scenario/media_deliverables.py").toString(),
                "--origin", "http://127.0.0.1:" + port, "--session-id", session, "--evidence", proof.toString())); command.addAll(arguments);
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(EVIDENCE.resolve(name + ".log").toFile());
        builder.environment().put("SKILLFORGE_MEDIA_API_KEY", credential.apiKey()); builder.environment().put("SKILLFORGE_MEDIA_SIGNING_SECRET", credential.signingSecret());
        Process process = builder.start();
        try { assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("Python媒体消费者需完成，日志 %s", name).isTrue(); assertThat(process.exitValue()).as(name).isEqualTo(expectedExit); }
        finally { if (process.isAlive()) { process.destroyForcibly(); assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue(); } }
        JsonNode result = json.readTree(proof.toFile()); assertThat(result.path("status").asText()).isEqualTo(expectedExit == 0 ? "PASS" : "FAILED"); return result;
    }

    private JsonNode member(String method, String path, Object body, String token, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()); return response(response, expected);
    }
    private JsonNode machine(IssuedPrincipal credential, String method, String path, Object body, String key, int expected) throws Exception { return response(raw(credential, method, path, body, key), expected); }
    private HttpResponse<byte[]> raw(IssuedPrincipal credential, String method, String path, Object body, String key) throws Exception {
        byte[] bytes = body == null ? new byte[0] : json.writeValueAsBytes(body); String target = "/api/v1" + path;
        String timestamp = Long.toString(Instant.now().getEpochSecond()); var hmac = Mac.getInstance("HmacSHA256"); hmac.init(new SecretKeySpec(credential.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String canonical = timestamp + "\n" + method + "\n" + target + "\n" + sha(bytes);
        // 宿主默认协商JSON；二进制读取明确接受文件，同时允许错误响应的正式JSON信封。
        String accept=path.startsWith("/open-platform/sessions/media/content") || path.startsWith("/open-platform/sessions/deliverables/file")
                ? "application/octet-stream, application/json" : "application/json";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .header("Accept",accept)
                .header("X-Api-Key", credential.apiKey()).header("X-Timestamp", timestamp).header("X-Signature", HexFormat.of().formatHex(hmac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private JsonNode response(HttpResponse<byte[]> response, int expected) throws Exception {
        assertHttpStatus(response,expected);
        var envelope = json.readTree(response.body()); assertThat(envelope.path("code").asText()).isEqualTo("OK"); return envelope.path("data");
    }
    private static void assertHttpStatus(HttpResponse<byte[]> response,int expected) {
        assertThat(response.statusCode()).as("HTTP status=%s body=%s",response.statusCode(),new String(response.body(),StandardCharsets.UTF_8)).isEqualTo(expected);
    }
    private static JsonNode find(JsonNode rows, String key, String value) { for (JsonNode row : rows) if (value.equals(row.path(key).asText())) return row; throw new AssertionError("missing published tool " + value); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Path evidenceDirectory() { try { Path reports = Path.of("build", "reports", "gi08-media-http").toAbsolutePath(); Files.createDirectories(reports); return Files.createTempDirectory(reports, "candidate-"); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); } }
    private static Path repositoryRoot() { Path path = Path.of("").toAbsolutePath(); while (path != null && !Files.isRegularFile(path.resolve("tools/integration-scenario/media_deliverables.py"))) path = path.getParent(); if (path == null) throw new AssertionError("repository root missing"); return path; }

    /** 保留实际绑定的端口到收尾，不探测空闲端口；此监听器不提供成果预览。 */
    private static final class PreviewBoundary implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        PreviewBoundary() {
            try { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
                server.createContext("/", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); }); server.start();
            } catch (IOException failure) { workers.shutdownNow(); throw new ExceptionInInitializerError(failure); }
        }
        int port() { return server.getAddress().getPort(); }
        @Override public void close() throws InterruptedException { server.stop(0); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    /** Provider只给出真实工具参数；Artifact身份必须从上一轮实际工具结果取得。 */
    private static final class MediaModel implements AutoCloseable {
        private final ObjectMapper json;
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final AtomicReference<String> attachment = new AtomicReference<>();
        final AtomicInteger requests = new AtomicInteger(), codeCalls = new AtomicInteger(), finalizeCalls = new AtomicInteger();
        MediaModel(ObjectMapper json) throws IOException { this.json = json; server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers); server.createContext("/chat/completions", this::handle); }
        void start() { server.start(); }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private void handle(HttpExchange exchange) throws IOException {
            int sequence = requests.incrementAndGet();
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                var roles=new ArrayList<String>();var toolResults=new ArrayList<JsonNode>();for(JsonNode message:request.path("messages")){roles.add(message.path("role").asText());if("tool".equals(message.path("role").asText()))toolResults.add(message);}
                Files.writeString(EVIDENCE.resolve("model-request-"+sequence+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("evidence_kind","synthetic_provider_tools","model",request.path("model"),"tools",request.path("tools"),"stream",request.path("stream"),"message_roles",roles,"tool_results",toolResults,
                                "response_format",request.path("response_format"),"tool_choice",request.path("tool_choice"))));
                if (request.path("messages").toString().contains("记忆提取器")) {
                    byte[] bytes = json.writeValueAsBytes(Map.of("id", "media-memory-" + sequence, "object", "chat.completion", "model", request.path("model").asText(),
                            "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", "{\"candidates\":[]}"), "finish_reason", "stop")),
                            "usage", Map.of("prompt_tokens", 20, "completion_tokens", 5, "total_tokens", 25)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); return;
                }
                List<JsonNode> observations = new ArrayList<>(); for (JsonNode message : request.path("messages")) if ("tool".equals(message.path("role").asText())) observations.add(message);
                String name; Map<String,Object> arguments;
                if (observations.isEmpty()) {
                    name = function(request, "output_files"); String id = attachment.get(); if (id == null) throw new IOException("uploaded attachment required");
                    String code = "from pathlib import Path\nimport json, wave, struct\n"
                            + "source=json.loads(Path('/input/data/attachments/" + id + "/input.json').read_text(encoding='utf-8'))\n"
                            + "assert source['simulated'] is True\nout=Path('/workspace/output');out.mkdir(parents=True,exist_ok=True)\n"
                            + "(out/'media-result.json').write_text(json.dumps(source,ensure_ascii=False),encoding='utf-8')\n"
                            + "with wave.open(str(out/'simulated-tone.wav'),'wb') as audio:\n audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(8000);audio.writeframes(struct.pack('<800h',*([0]*800)))\n"
                            + "(out/'not-published-debug.txt').write_text('internal candidate only')\n";
                    arguments = Map.of("mode", "inline", "language", "python", "code", code, "dependencies", List.of(), "attachment_ids", List.of(id), "output_files", List.of("media-result.json", "simulated-tone.wav")); codeCalls.incrementAndGet();
                } else if (observations.size() == 1) {
                    name = function(request, "deliverables"); String artifact = artifact(observations.getFirst());
                    arguments = Map.of("deliverables", List.of(selection(artifact, "media-result.json", "json", "data"), selection(artifact, "simulated-tone.wav", "wav", "media")), "evidence", List.of()); finalizeCalls.incrementAndGet();
                } else {
                    name = null; arguments = Map.of();
                }
                // 当前普通函数直接使用冻结的业务schema，不再套历史commentary/arguments包装。
                String encoded = json.writeValueAsString(arguments);
                var tool = name == null ? Map.<String,Object>of() : Map.<String,Object>of("id", "media-call-" + sequence, "type", "function", "function", Map.of("name", name, "arguments", encoded));
                Map<String,Object> message = name == null ? Map.of("role", "assistant", "content", "已根据授权输入正式交付媒体结果JSON及模拟音频文件。供应商结果为模拟。")
                        : Map.of("role", "assistant", "tool_calls", List.of(tool));
                String finish = name == null ? "stop" : "tool_calls";
                Map<String,Object> usage = Map.of("prompt_tokens", 40, "completion_tokens", 20, "total_tokens", 60);
                if (request.path("stream").asBoolean()) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                    var deltaTool = new LinkedHashMap<String,Object>(tool); deltaTool.put("index", 0);
                    var delta = name == null ? message : Map.of("role", "assistant", "tool_calls", List.of(deltaTool));
                    chunk(exchange, request, sequence, List.of(Map.of("index", 0, "delta", delta)), null);
                    chunk(exchange, request, sequence, List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)), null);
                    chunk(exchange, request, sequence, List.of(), usage); exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] bytes = json.writeValueAsBytes(Map.of("id", "media-" + sequence, "object", "chat.completion", "model", request.path("model").asText(), "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)), "usage", usage));
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
            } catch (Exception problem) {
                try { Files.writeString(EVIDENCE.resolve("model-failure-"+sequence+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("evidence_kind","synthetic_provider_failure","type",problem.getClass().getName(),"message",String.valueOf(problem.getMessage())))); }
                catch(IOException evidenceFailure) { problem.addSuppressed(evidenceFailure); }
                failure.compareAndSet(null, problem); byte[] bytes = "{\"error\":{\"message\":\"media fixture protocol failure\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        }
        private Map<String,Object> selection(String artifact, String path, String format, String kind) {
            return Map.of("key", format, "title", "模拟媒体成果 " + format, "kind", kind, "representations", List.of(Map.of("role", "source", "format", format, "primary_preview", true, "files", List.of(Map.of("artifact_id", artifact, "path", path)))));
        }
        private String function(JsonNode request, String property) throws IOException {
            for (JsonNode tool : request.path("tools")) { JsonNode function = tool.path("function"); if (function.path("parameters").path("properties").has(property)) return function.path("name").asText(); }
            throw new IOException("missing formal function " + property);
        }
        private String artifact(JsonNode observation) throws IOException {
            String content = observation.path("content").asText(); int start = content.indexOf('{'); if (start < 0) throw new IOException("missing actual artifact result");
            // AgentExecutionCodec.toolContents把安全structuredContent本身作为模型正文，并不暴露内部结果信封。
            JsonNode result = json.readTree(content.substring(start));
            if (!result.path("success").asBoolean() || !"ready".equals(result.path("delivery_status").asText())) throw new IOException("actual code execution did not produce ready artifacts");
            String id = result.path("artifacts").path(0).path("id").asText(); if (id.isBlank()) throw new IOException("actual artifact id missing"); return id;
        }
        private void chunk(HttpExchange exchange, JsonNode request, int sequence, List<?> choices, Map<String,Object> usage) throws IOException {
            var value = new LinkedHashMap<String,Object>(); value.put("id", "media-" + sequence); value.put("object", "chat.completion.chunk"); value.put("model", request.path("model").asText()); value.put("choices", choices); if (usage != null) value.put("usage", usage);
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(value) + "\n\n").getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
        }
        @Override public void close() throws InterruptedException { server.stop(0); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }
}
