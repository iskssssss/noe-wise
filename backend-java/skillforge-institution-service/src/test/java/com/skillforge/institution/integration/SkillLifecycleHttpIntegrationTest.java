package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.institution.runtime.AgentRuntimeExecutionCatalog;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 HTTP/PG/Runtime 的零预置技能生命周期；模型只连接本测试拥有的 loopback 端点。 */
@Import(CodeExecutionChatIntegrationTest.VerifiedModelProviderConfiguration.class)
class SkillLifecycleHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "SkillLifecycleSynthetic123";
    private static final String DRAFTS = "/api/v1/capabilities/skill-drafts";
    private static final String MOUNTS = "/api/v1/agents/capability-mounts";
    private static final Duration WAIT = Duration.ofSeconds(50);
    private static final boolean SANDBOX = System.getenv("CODE_EXECUTION_SMOKE_ENDPOINT") != null
            && System.getenv("CODE_EXECUTION_SMOKE_TOKEN") != null;
    private static final int PREVIEW_PORT = freePort();
    private static final int APPLICATION_PORT = freePort();
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private EndUserMapper users;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private AgentRuntimeHostProvisioner provisioner;
    @Autowired private ExecutionStore executions;
    @Autowired private AgentRuntimeExecutionCatalog catalogs;
    @Autowired private OperationalSettingsService settings;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ScriptedModel model;
    private OperationalSettingsService.Snapshot sandboxBefore;
    private long settingsActor;

    @DynamicPropertySource static void workers(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> APPLICATION_PORT);
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("skillforge.team.poll-ms", () -> "3600000");
        registry.add("agent-runtime.worker-lease-duration", () -> "210s");
        registry.add("skillforge.code-execution.endpoint", () -> SANDBOX ? System.getenv("CODE_EXECUTION_SMOKE_ENDPOINT") : "http://127.0.0.1:9");
        registry.add("skillforge.code-execution.service-token", () -> SANDBOX ? System.getenv("CODE_EXECUTION_SMOKE_TOKEN") : "disabled-skill-lifecycle-sandbox-token-00000000");
        registry.add("skillforge.code-execution.preview-port", () -> PREVIEW_PORT);
        registry.add("skillforge.code-execution.artifact-root", () -> java.nio.file.Path.of("build/runtime-e2e/skill-lifecycle-" + PREVIEW_PORT).toAbsolutePath().toString());
        registry.add("skillforge.code-execution.ticket-key", () -> "synthetic-skill-lifecycle-ticket-key-0123456789");
        registry.add("skillforge.code-execution.platform-origin", () -> "http://localhost:" + APPLICATION_PORT);
        registry.add("skillforge.code-execution.download-origin", () -> "http://127.0.0.1:" + APPLICATION_PORT);
        registry.add("skillforge.code-execution.preview-origin", () -> "http://127.0.0.1:" + PREVIEW_PORT);
    }

    @AfterEach void close() {
        if (model != null) model.close();
        TenantContext.clear();
        if (sandboxBefore != null) settings.update("sandbox", settings.read("sandbox").revision(), sandboxBefore.values(), settingsActor);
    }

    @Test @Timeout(180)
    void onlineAndZipPublicationPreservePinnedAndInFlightVersionsUntilExplicitAdoption() throws Exception {
        model = new ScriptedModel();
        var owner = account("skill-http");
        TenantContext.setTenantId(owner.tenant());
        PlatformModelFixtures.selectLlm(models, owner.tenant(), model.endpoint(), "skill-http-fixture",
                "synthetic-skill-http-key", "custom", "custom-openai-compatible", "custom-v1");
        provisioner.provision(owner.tenant());
        if (SANDBOX) {
            settingsActor = SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",
                    settingsActor, "skill-http-settings-" + owner.suffix(), "unused");
            sandboxBefore = settings.read("sandbox");
            var enabled = new LinkedHashMap<>(sandboxBefore.values()); enabled.put("enabled", true);
            settings.update("sandbox", sandboxBefore.revision(), enabled, settingsActor);
        }
        assertTrue(call(owner.token(), "GET", "/api/v1/capabilities/definitions?capability_type=SKILL", null, null, 200).isEmpty(),
                "全新租户不得由启动过程获得系统预置 Skill");
        var definition = call(owner.token(), "POST", "/api/v1/capabilities/definitions", Map.of(
                "capability_type", "SKILL", "capability_key", "skill-http-" + owner.suffix(),
                "name", "在线技能验收", "description", "合成测试包"), null, 201);
        String definitionId = definition.path("id").asText();
        var draft = call(owner.token(), "POST", DRAFTS, Map.of("definition_id", definitionId), null, 201);
        String draftId = draft.path("id").asText();
        var invalid = call(owner.token(), "POST", DRAFTS + "/validate",
                Map.of("draft_id", draftId, "revision", draft.path("revision").asLong()), null, 200);
        assertFalse(invalid.path("valid").asBoolean(), invalid.toString());
        var refused = send(owner.token(), "POST", DRAFTS + "/publish", Map.of("draft_id", draftId,
                "revision", draft.path("revision").asLong(), "definition_revision", definition.path("revision").asLong()), null);
        assertTrue(refused.statusCode() >= 400 && refused.statusCode() < 500, refused.body());
        assertTrue(call(owner.token(), "GET", "/api/v1/capabilities/definitions/versions?definitionId=" + definitionId,
                null, null, 200).isEmpty(), "无效维护草稿不得产生 Runtime 版本");

        // 在线编辑产生 V1，不从服务器内置目录或已存在包取得内容。
        draft = save(owner, draft, "v1");
        var v1 = publish(owner, definitionId, draft);
        String version1 = v1.path("version_id").asText();
        String agentId = call(owner.token(), "POST", "/api/v1/agents", Map.of("agent_key", "skill-http-" + owner.suffix(),
                "name", "技能冻结验收", "system_prompt", "根据已挂载技能读取材料，完成后简洁回答。"), null, 200).path("id").asText();
        var mounted = call(owner.token(), "POST", MOUNTS, Map.of("agent_id", agentId,
                "payload", mountPayload(definitionId, version1, "ACTIVE")), null, 201).path("mounts").get(0);
        if (SANDBOX) mountCodeExecution(owner, agentId);
        publishAgent(owner, agentId);
        String handle = "skill:" + mounted.path("mount_id").asText();

        var inFlight = start(owner, agentId, handle, "v1", true);
        try {
            assertTrue(inFlight.entered.await(20, TimeUnit.SECONDS),
                    () -> "模型暂停点未进入，不能声称 Run 已进行中: " + run(owner, inFlight.input));
            var original = frozen(owner, inFlight.input);
            assertEquals(version1, original.skills().getFirst().capability().versionId());
            assertEquals(RunStatus.RUNNING, run(owner, inFlight.input).status());

            // 第二版由标准 ZIP 导入，再经过在线编辑、校验和正式发布。
            var secondDraft = call(owner.token(), "POST", DRAFTS,
                    Map.of("definition_id", definitionId, "base_version_id", version1), null, 201);
            secondDraft = importZip(owner, secondDraft, files("v2", "ZIP_IMPORTED"));
            assertEquals("GUIDE_V2", new String(Base64.getDecoder().decode(
                    secondDraft.path("files").path("references/guide.md").asText()), StandardCharsets.UTF_8));
            assertTrue(new String(Base64.getDecoder().decode(secondDraft.path("files").path("SKILL.md").asText()),
                    StandardCharsets.UTF_8).contains("ZIP_IMPORTED"), "ZIP 内容必须实际进入维护草稿，再显式填写平台版本号");
            secondDraft = save(owner, secondDraft, "v2");
            var v2 = publish(owner, definitionId, secondDraft);
            String version2 = v2.path("version_id").asText();
            assertNotEquals(version1, version2);
            assertEquals(version2, call(owner.token(), "GET", "/api/v1/capabilities/definitions/detail?definitionId=" + definitionId,
                    null, null, 200).path("default_version_id").asText(), "V2 发布须确实推进默认指针，再验证固定挂载与进行中 Run 不受影响");
            assertEquals(version1, mount(owner, agentId).path("capability_version_id").asText(),
                    "发布 V2 不得改变固定 V1 挂载");
            assertEquals(original, frozen(owner, inFlight.input), "进行中 Run 必须继续使用原持久冻结目录");
            assertEquals("GUIDE_V1", file(owner, version1, "references/guide.md"));
            assertEquals("GUIDE_V2", file(owner, version2, "references/guide.md"));
            inFlight.release.countDown();
            completed(owner, inFlight, "v1");
            var stillPinned = start(owner, agentId, handle, "v1", false);
            completed(owner, stillPinned, "v1");
            assertEquals(version1, frozen(owner, stillPinned.input).skills().getFirst().capability().versionId(),
                    "发布 V2 后第二个新 Run 的持久 Catalog 也必须选择固定 V1");

            String adoptedAgent = call(owner.token(), "POST", "/api/v1/agents/revise",
                    Map.of("agent_id", agentId), null, 200).path("id").asText();
            var before = mount(owner, adoptedAgent);
            call(owner.token(), "PUT", MOUNTS + "/update", Map.of("agent_id", adoptedAgent,
                    "mount_id", before.path("mount_id").asText(), "payload", mountPayload(definitionId, version2, "ACTIVE")),
                    before.path("revision").asLong(), 200);
            publishAgent(owner, adoptedAgent);
            String adoptedHandle = "skill:" + mount(owner, adoptedAgent).path("mount_id").asText();
            var adopted = start(owner, adoptedAgent, adoptedHandle, "v2", false);
            completed(owner, adopted, "v2");
            assertEquals(version2, frozen(owner, adopted.input).skills().getFirst().capability().versionId());
            assertEquals(original, frozen(owner, inFlight.input), "显式采用 V2 后历史 Run 仍为 V1");

            String disabledAgent = call(owner.token(), "POST", "/api/v1/agents/revise",
                    Map.of("agent_id", adoptedAgent), null, 200).path("id").asText();
            var active = mount(owner, disabledAgent);
            call(owner.token(), "PUT", MOUNTS + "/update", Map.of("agent_id", disabledAgent,
                    "mount_id", active.path("mount_id").asText(), "payload", mountPayload(definitionId, version2, "DISABLED")),
                    active.path("revision").asLong(), 200);
            publishAgent(owner, disabledAgent);
            var disabled = start(owner, disabledAgent, null, "none", false);
            completed(owner, disabled, "none");
            assertTrue(frozen(owner, disabled.input).skills().isEmpty(), "停用后新 Run 不得继续获得 Skill");
            assertFalse(disabled.requests.toString().contains("ROOT_V2"));
            permissions(owner, definitionId, draftId, version1);
        } finally {
            inFlight.release.countDown();
        }
        model.healthy();
    }

    /** Explicit paid-provider acceptance; no scripted responses and no automatic replay on UNKNOWN. */
    @Test @Timeout(600)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SKILL_LIVE_MODEL_ENABLED", matches = "true")
    void realModelLoadsOnDemandSkillExecutesPythonAndPublishesSummary() throws Exception {
        assertTrue(SANDBOX, "CODE_EXECUTION_SMOKE_ENDPOINT/TOKEN are required");
        String endpoint = requiredEnvironment("SKILL_LIVE_DEEPSEEK_ENDPOINT");
        String modelId = requiredEnvironment("SKILL_LIVE_DEEPSEEK_MODEL");
        String key = requiredEnvironment("SKILL_LIVE_DEEPSEEK_API_KEY");
        var reports = java.nio.file.Files.createDirectories(java.nio.file.Path.of("build", "reports"));
        var report = java.nio.file.Files.createTempDirectory(reports, "skill-live-");
        var facts = new LinkedHashMap<String, Object>();
        facts.put("status", "UNKNOWN");
        java.nio.file.Files.writeString(report.resolve("evidence.json"), json.writeValueAsString(facts));
        try (var provider = new RecordingLiveProvider(json, endpoint, key, report)) {
            var owner = account("skill-live");
            TenantContext.setTenantId(owner.tenant());
            var liveModel = models.create(new com.skillforge.core.model.ModelWrite(null, "真实销售汇总验收模型", "LLM", true,
                    "custom", "custom-openai-compatible", "custom-v1", "openai-chat-completions", "bearer-api-key",
                    provider.endpoint(), modelId, "local-forwarder-only", 8192, 32768, 1024, 0.2, "DISABLED", 120,
                    null, null, null, null, null, "CNY"), "skill-live");
            models.publish(liveModel.id(), liveModel.revision(), "skill-live");
            models.select(owner.tenant(), new com.skillforge.core.model.ModelSelections(liveModel.id(), null, null));
            provisioner.provision(owner.tenant());
            settingsActor = SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",
                    settingsActor, "skill-live-settings-" + owner.suffix(), "unused");
            sandboxBefore = settings.read("sandbox");
            var enabled = new LinkedHashMap<>(sandboxBefore.values()); enabled.put("enabled", true);
            settings.update("sandbox", sandboxBefore.revision(), enabled, settingsActor);
            var definition = call(owner.token(), "POST", "/api/v1/capabilities/definitions", Map.of(
                    "capability_type", "SKILL", "capability_key", "summary-" + owner.suffix(),
                    "name", "销售汇总与公式解释", "description", "计算销售额并交付 summary.txt"), null, 201);
            String definitionId = definition.path("id").asText();
            var draft = call(owner.token(), "POST", DRAFTS, Map.of("definition_id", definitionId), null, 201);
            Map<String, String> files = new LinkedHashMap<>();
            files.put("SKILL.md", "---\nname: sales-summary\ndescription: Calculate sales and explain the formula in a text deliverable\n---\n"
                    + "先读取 references/calculation.md，按其中给出的公式和精确输出格式计算。使用 Python 真正执行并写 summary.txt，"
                    + "声明 output_files，检查回执后调用 finalize_task_outputs 发布 document 成果，成功后给用户简洁中文解释和正式文件引用。不要仅口头报告。\n");
            files.put("references/calculation.md", "SALES_REFERENCE_CURRENT\n输入：甲数量3单价12.50，乙数量2单价8.00。"
                    + "金额=数量×单价，合计=各项金额之和，使用 Decimal。summary.txt 必须四行，前三行分别为甲、乙、合计，"
                    + "格式为名称=金额（小数点后两位），第四行逐字写：公式：金额=数量×单价；合计=各项金额之和\n");
            Map<String, String> encoded = new LinkedHashMap<>();
            files.forEach((path, text) -> encoded.put(path, Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))));
            draft = call(owner.token(), "PUT", DRAFTS, Map.of("draft_id", draft.path("id").asText(), "version_key", "current",
                    "files", encoded, "model_invocable", true, "user_invocable", true, "activation_mode", "ON_DEMAND",
                    "required_capabilities", List.of()), draft.path("revision").asLong(), 200);
            String version = publish(owner, definitionId, draft).path("version_id").asText();
            String agent = call(owner.token(), "POST", "/api/v1/agents", Map.of("agent_key", "summary-" + owner.suffix(),
                    "name", "销售汇总", "system_prompt", "使用已挂载的销售汇总技能完成用户任务，遵守技能参考和真实工具回执。"), null, 200).path("id").asText();
            call(owner.token(), "POST", MOUNTS, Map.of("agent_id", agent, "payload", mountPayload(definitionId, version, "ACTIVE")), null, 201);
            mountPlatformTool(owner, agent, "code_execute");
            mountPlatformTool(owner, agent, "finalize_task_outputs");
            publishAgent(owner, agent);
            var user = new EndUserDO(); user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.valueOf(owner.tenant()));
            user.setDisplayName("真实模型汇总验收"); user.setStatus("active"); user.setCreatedAt(Instant.now());
            user.setFirstSeenAt(Instant.now()); user.setLastActiveAt(Instant.now()); users.insert(user);
            String session = call(owner.token(), "POST", "/api/v1/chat/sessions", Map.of("agent_id", agent, "end_user_id", user.getId()), null, 200).path("session_id").asText();
            String input = "skill-live-" + UUID.randomUUID();
            facts.put("run_id", "run:" + input); facts.put("session_id", session); facts.put("model", modelId);
            facts.put("provider", "real DeepSeek; main Agent follows the mounted Skill and actual tool results");
            java.nio.file.Files.writeString(report.resolve("evidence.json"), json.writeValueAsString(facts));
            call(owner.token(), "POST", "/api/v1/chat", Map.of("content", List.of(Map.of("type", "text", "text",
                    "请加载销售汇总技能，读取它的计算参考，真实执行 Python 计算甲乙销售额及总额，生成并发布 summary.txt，最后解释公式并给我可下载文件。")),
                    "session_id", session, "agent_id", agent, "client_message_id", input, "context", Map.of("end_user_id", user.getId())), null, 202);
            long deadline = System.nanoTime() + Duration.ofMinutes(7).toNanos();
            while (run(owner, input).status() != RunStatus.COMPLETED && System.nanoTime() < deadline) {
                provider.healthy();
                var status = run(owner, input).status();
                assertTrue(status == RunStatus.QUEUED || status == RunStatus.RUNNING || status == RunStatus.RESUMING,
                        () -> "Live run stopped; no replay: " + run(owner, input));
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
            assertEquals(RunStatus.COMPLETED, run(owner, input).status());
            var history = call(owner.token(), "GET", "/api/v1/chat/history?session_id=" + session, null, null, 200);
            java.nio.file.Files.writeString(report.resolve("history.json"), json.writeValueAsString(history));
            assertTrue(history.path("messages").toString().contains("53.50"), "终答须包含真实合计");
            var deliverables = call(owner.token(), "GET", "/api/v1/chat/sessions/deliverables?sessionId=" + session + "&includeHistory=true", null, null, 200);
            java.nio.file.Files.writeString(report.resolve("deliverables.json"), json.writeValueAsString(deliverables));
            assertEquals(1, deliverables.size(), deliverables.toString());
            var delivered = deliverables.get(0);
            assertEquals("ready", delivered.path("status").asText());
            assertEquals("run:" + input, delivered.path("run_id").asText());
            var deliveredFile = element(delivered.path("files"), "path", "summary.txt");
            var ticket = call(owner.token(), "POST", "/api/v1/chat/deliverables/download-ticket", Map.of(
                    "id", delivered.path("id").asText(), "file_id", deliveredFile.path("file_id").asText()), null, 200);
            URI issued = URI.create(ticket.path("url").asText());
            assertEquals("127.0.0.1", issued.getHost()); assertEquals(port, issued.getPort());
            assertEquals("/api/v1/chat/deliverable-content/detail", issued.getPath());
            var download = http.send(HttpRequest.newBuilder(URI.create(baseUrl()).resolve(issued))
                    .timeout(WAIT).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, download.statusCode());
            java.nio.file.Files.writeString(report.resolve("summary.txt"), download.body());
            assertEquals("甲=37.50\n乙=16.00\n合计=53.50\n公式：金额=数量×单价；合计=各项金额之和", download.body().strip().replace("\r\n", "\n"));
            String observed = String.join("\n", provider.requests);
            assertTrue(observed.contains("SALES_REFERENCE_CURRENT"), "真实模型上下文须包含工具读取的参考内容");
            for (String tool : List.of("use_skill", "read_skill_resource", "code_execute", "finalize_task_outputs"))
                assertTrue(provider.called(tool), "真实模型必须实际调用 " + tool);
            facts.put("status", "PASS");
        } finally {
            java.nio.file.Files.writeString(report.resolve("evidence.json"), json.writeValueAsString(facts), StandardCharsets.UTF_8);
            System.out.println("SKILL_LIVE_EVIDENCE=" + report.toAbsolutePath());
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " is required");
        assertFalse(value.isBlank(), name + " is required");
        return value;
    }


    private void permissions(Account owner, String definitionId, String draftId, String versionId) throws Exception {
        assertEquals(401, send(null, "GET", DRAFTS + "/detail?draftId=" + draftId, null, null).statusCode());
        String access = "/api/v1/tenant/foundation/access";
        var role = call(owner.token(), "POST", access + "/role", Map.of("code", "reader_" + owner.suffix(),
                "name", "技能只读", "data_scope", "all", "permissions", List.of("foundation.skills.read"),
                "department_ids", List.of()), null, 200);
        String username = "skill-reader-" + owner.suffix();
        call(owner.token(), "POST", access + "/member", Map.of("username", username,
                "display_name", "技能只读成员", "password", PASSWORD, "role_ids", List.of(role.path("id").asText())), null, 200);
        String reader = login(owner.slug(), username);
        assertEquals(403, send(reader, "POST", DRAFTS, Map.of("definition_id", definitionId), null).statusCode());
        var other = account("skill-other");
        assertEquals(404, send(other.token(), "GET", DRAFTS + "/version?versionId=" + versionId, null, null).statusCode());
        assertEquals("GUIDE_V1", file(owner, versionId, "references/guide.md"));
    }

    @Test @Timeout(120)
    void deploymentErrorsPreserveCodesOverHttpAndDoNotCreateExtraRows() throws Exception {
        var owner = account("deployment-errors");
        var definition = element(call(owner.token(), "GET", "/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED",
                null, null, 200), "capability_key", "tool:code_execute");
        var version = call(owner.token(), "GET", "/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId="
                + definition.path("id").asText(), null, null, 200).get(0);
        String key = "deployment-errors-" + owner.suffix();
        var payload = Map.of("deployment_key", key, "tool_version_id", version.path("id").asText(),
                "provider_type", "PLATFORM", "provider_ref", "code_execute", "environment", "TEST", "status", "ACTIVE");
        var created = call(owner.token(), "POST", "/api/v1/capabilities/deployments", payload, null, 201);
        var duplicate = send(owner.token(), "POST", "/api/v1/capabilities/deployments", payload, null);
        assertEquals(409, duplicate.statusCode(), duplicate.body());
        var duplicateBody = json.readTree(duplicate.body());
        assertEquals("CAPABILITY_DUPLICATE", duplicateBody.path("code").asText());
        assertEquals("tool deployment key already exists", duplicateBody.path("message").asText());
        assertFalse(duplicateBody.path("retryable").asBoolean());
        var invalid = new LinkedHashMap<>(payload);
        invalid.put("provider_type", "UNSUPPORTED");
        var rejected = send(owner.token(), "POST", "/api/v1/capabilities/deployments", invalid, null);
        assertEquals(422, rejected.statusCode(), rejected.body());
        assertEquals("CAPABILITY_DEPLOYMENT_INVALID", json.readTree(rejected.body()).path("code").asText());
        var deployments = call(owner.token(), "GET", "/api/v1/capabilities/deployments", null, null, 200);
        int matches = 0;
        for (var deployment : deployments) {
            if (key.equals(deployment.path("deployment_key").asText())) {
                matches++;
                assertEquals(created.path("id").asText(), deployment.path("id").asText());
            }
        }
        assertEquals(1, matches);
    }

    private Account account(String prefix) throws Exception {
        String suffix = Long.toString(SnowflakeIds.nextId());
        String slug = prefix + "-" + suffix;
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String username = "admin-" + suffix;
        createTestTenantAdministrator(tenant, username, PASSWORD);
        return new Account(tenant, slug, suffix, login(slug, username));
    }

    private String login(String slug, String username) throws Exception {
        return call(null, "POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", username, "password", PASSWORD), null, 200).path("access_token").asText();
    }

    private JsonNode save(Account owner, JsonNode draft, String version) throws Exception {
        Map<String, String> encoded = new LinkedHashMap<>();
        files(version, "EDITED").forEach((path, text) -> encoded.put(path, Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))));
        return call(owner.token(), "PUT", DRAFTS, Map.of("draft_id", draft.path("id").asText(), "version_key", version,
                "files", encoded, "model_invocable", true, "user_invocable", true,
                "activation_mode", "ON_DEMAND", "required_capabilities", List.of()), draft.path("revision").asLong(), 200);
    }

    private JsonNode publish(Account owner, String definitionId, JsonNode draft) throws Exception {
        assertTrue(call(owner.token(), "POST", DRAFTS + "/validate", Map.of("draft_id", draft.path("id").asText(),
                "revision", draft.path("revision").asLong()), null, 200).path("valid").asBoolean());
        var definition = call(owner.token(), "GET", "/api/v1/capabilities/definitions/detail?definitionId=" + definitionId, null, null, 200);
        return call(owner.token(), "POST", DRAFTS + "/publish", Map.of("draft_id", draft.path("id").asText(),
                "revision", draft.path("revision").asLong(), "definition_revision", definition.path("revision").asLong()), null, 200);
    }

    private JsonNode importZip(Account owner, JsonNode draft, Map<String, String> files) throws Exception {
        var archive = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(archive)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        String boundary = "skill-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"skill.zip\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(archive.toByteArray());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + DRAFTS + "/import-zip?draft_id=" + draft.path("id").asText()))
                .timeout(WAIT).header("Authorization", "Bearer " + owner.token())
                .header("If-Match", Long.toString(draft.path("revision").asLong()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return data(http.send(request, HttpResponse.BodyHandlers.ofString()), 200);
    }

    private static Map<String, String> files(String version, String edit) {
        String tag = version.toUpperCase(java.util.Locale.ROOT);
        return Map.of("SKILL.md", "---\nname: lifecycle-skill\ndescription: Synthetic HTTP lifecycle fixture\nmetadata:\n  version: " + version
                + "\n---\nROOT_" + tag + " " + edit + "\nRead references/guide.md and scripts/check.py only when requested.",
                "references/guide.md", "GUIDE_" + tag,
                "scripts/check.py", "from pathlib import Path\nprint('EXECUTED_" + tag + "|' + (Path(__file__).resolve().parent.parent / 'references/guide.md').read_text(encoding='utf-8'))\n# SCRIPT_" + tag + "\n",
                "manifest.json", "{\"entry\":{\"language\":\"python\",\"script_path\":\"scripts/check.py\"},\"dependencies\":[]}");
    }

    private Map<String, Object> mountPayload(String definition, String version, String status) {
        return Map.of("capability_type", "SKILL", "capability_definition_id", definition, "capability_version_id", version,
                "skill_config", Map.of("activation_mode", "ON_DEMAND", "token_budget", 8000), "priority", 0, "status", status);
    }

    private JsonNode mount(Account owner, String agent) throws Exception {
        return element(call(owner.token(), "GET", MOUNTS + "?agentId=" + agent, null, null, 200).path("mounts"), "capability_type", "SKILL");
    }

    private void mountCodeExecution(Account owner, String agent) throws Exception {
        mountPlatformTool(owner, agent, "code_execute");
    }

    private void mountPlatformTool(Account owner, String agent, String tool) throws Exception {
        var definition = element(call(owner.token(), "GET", "/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED",
                null, null, 200), "capability_key", "tool:" + tool);
        String versionKey = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor())
                .entries().stream().filter(entry -> entry.name().equals(tool)).findFirst().orElseThrow().publishedVersionKey();
        var version = element(call(owner.token(), "GET", "/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId="
                + definition.path("id").asText(), null, null, 200), "version_key", versionKey);
        String deploymentKey = "skill-http-" + tool + "-" + owner.suffix();
        var deployment = call(owner.token(), "POST", "/api/v1/capabilities/deployments", Map.of("deployment_key", deploymentKey,
                "tool_version_id", version.path("id").asText(), "provider_type", "PLATFORM", "provider_ref", tool,
                "environment", "TEST", "status", "ACTIVE"), null, 201);
        call(owner.token(), "POST", MOUNTS, Map.of("agent_id", agent, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", definition.path("id").asText(), "capability_version_id", version.path("id").asText(),
                "tool_config", Map.of("tool_deployment_id", deploymentKey, "tool_deployment_revision", deployment.path("revision").asLong()),
                "priority", 0)), null, 201);
    }

    private static JsonNode element(JsonNode values, String field, String expected) {
        for (var value : values) if (value.path(field).asText().equals(expected)) return value;
        throw new AssertionError("未找到正式响应项 " + field + "=" + expected);
    }

    private void publishAgent(Account owner, String agent) throws Exception {
        call(owner.token(), "POST", "/api/v1/agents/publish", Map.of("agent_id", agent), null, 200);
    }

    private String file(Account owner, String version, String path) throws Exception {
        var response = send(owner.token(), "GET", "/api/v1/capabilities/skill-versions/file?versionId=" + version + "&path=" + path, null, null);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private Plan start(Account owner, String agent, String handle, String version, boolean hold) throws Exception {
        TenantContext.setTenantId(owner.tenant());
        var user = new EndUserDO(); user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.valueOf(owner.tenant()));
        user.setDisplayName("技能生命周期终端用户"); user.setStatus("active"); user.setCreatedAt(Instant.now());
        user.setFirstSeenAt(Instant.now()); user.setLastActiveAt(Instant.now()); users.insert(user);
        String session = call(owner.token(), "POST", "/api/v1/chat/sessions", Map.of("agent_id", agent, "end_user_id", user.getId()), null, 200).path("session_id").asText();
        String input = "skill-http-" + UUID.randomUUID();
        var plan = new Plan(input, handle, version, hold);
        model.plans.put(input, plan);
        var receipt = call(owner.token(), "POST", "/api/v1/chat", Map.of("content", List.of(Map.of("type", "text", "text", input)),
                "session_id", session, "agent_id", agent, "client_message_id", input, "context", Map.of("end_user_id", user.getId())), null, 202);
        assertEquals("run:" + input, receipt.path("execution_id").asText());
        return plan;
    }

    private ExecutionStore.Run run(Account owner, String input) {
        TenantContext.setTenantId(owner.tenant());
        return executions.load(new RunRef(new TenantId(owner.tenant()), "run:" + input)).orElseThrow();
    }

    private AgentRuntimeExecutionCatalog.FrozenCatalog frozen(Account owner, String input) {
        var run = run(owner, input);
        return catalogs.read(executions.material(run.identity().runRef().tenantId(),
                AgentRuntimeExecutionCatalog.reference(run.identity().runRef())).orElseThrow(), run.identity());
    }

    private void completed(Account owner, Plan plan, String version) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            model.healthy();
            var run = run(owner, plan.input);
            if (run.status() == RunStatus.COMPLETED) {
                var report = java.nio.file.Files.createDirectories(java.nio.file.Path.of("build", "reports", "skill-lifecycle-private"))
                        .resolve(plan.input + ".requests.json").toAbsolutePath();
                java.nio.file.Files.writeString(report, "[" + String.join(",\n", plan.requests) + "]", StandardCharsets.UTF_8);
                java.nio.file.Files.writeString(report.resolveSibling(plan.input + ".catalog.json"), json.writeValueAsString(Map.of(
                        "run", plan.input, "skill_version_ids", frozen(owner, plan.input).skills().stream()
                                .map(skill -> skill.capability().versionId()).toList())), StandardCharsets.UTF_8);
                if (!version.equals("none")) {
                    String observed = plan.requests.toString();
                    String tag = version.toUpperCase(java.util.Locale.ROOT);
                    String details = "run=" + plan.input + ", expected=" + version + ", requests=" + report;
                    assertTrue(observed.contains("ROOT_" + tag), "冻结 Skill 正文不匹配；" + details);
                    assertTrue(observed.contains("GUIDE_" + tag), "冻结参考资源不匹配；" + details);
                    assertTrue(observed.contains("SCRIPT_" + tag), "冻结脚本资源不匹配；" + details);
                    assertTrue(observed.contains("RESOURCE_NOT_FOUND"), "缺失资源必须反馈失败而非伪造内容");
                    assertFalse(observed.contains(version.equals("v1") ? "ROOT_V2" : "ROOT_V1"));
                    if (SANDBOX) assertTrue(plan.requests.stream().map(text -> {
                        try { return json.readTree(text); } catch (IOException error) { throw new AssertionError(error); }
                    }).anyMatch(request -> observedStdout(request.path("messages"), "EXECUTED_" + tag + "|GUIDE_" + tag + "\n", 0)),
                            "真实脚本回执必须成功并返回当前版本及辅助资源；读取源码中的 marker 不算执行证据");
                }
                return;
            }
            assertNotEquals(RunStatus.FAILED, run.status(), run.toString());
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(40));
        }
        fail("技能 Run 未完成: " + run(owner, plan.input) + "; calls=" + plan.index.get());
    }

    private JsonNode call(String token, String method, String path, Object body, Long revision, int status) throws Exception {
        return data(send(token, method, path, body, revision), status);
    }

    private HttpResponse<String> send(String token, String method, String path, Object body, Long revision) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(WAIT).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (revision != null) builder.header("If-Match", revision.toString());
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        var envelope = json.readTree(response.body());
        assertEquals("OK", envelope.path("code").asText(), response.body());
        return envelope.path("data");
    }

    private String baseUrl() { return "http://127.0.0.1:" + port; }
    private static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private boolean observedStdout(JsonNode node, String expected, int depth) {
        if (depth > 16) return false;
        if (node.isObject() && node.path("success").asBoolean() && expected.equals(node.path("stdout").asText())) return true;
        if (node.isContainerNode()) {
            for (var child : node) if (observedStdout(child, expected, depth + 1)) return true;
        } else if (node.isTextual() && node.textValue().startsWith("{")) {
            try { return observedStdout(json.readTree(node.textValue()), expected, depth + 1); }
            catch (IOException ignored) { /* 非 JSON 的业务文字不是执行证据。 */ }
        }
        return false;
    }
    private record Account(String tenant, String slug, String suffix, String token) { }

    private static final class Plan {
        final String input, handle, version;
        final CountDownLatch entered = new CountDownLatch(1), release;
        final AtomicInteger index = new AtomicInteger();
        final List<String> requests = new CopyOnWriteArrayList<>();
        Plan(String input, String handle, String version, boolean hold) {
            this.input = input; this.handle = handle; this.version = version; release = new CountDownLatch(hold ? 1 : 0);
        }
    }

    private final class ScriptedModel implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Map<String, Plan> plans = new ConcurrentHashMap<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        ScriptedModel() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers); server.createContext("/", this::handle); server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void healthy() { if (!failures.isEmpty()) throw new AssertionError("合成本地模型失败", failures.getFirst()); }
        void handle(HttpExchange exchange) throws IOException {
            try {
                var request = json.readTree(exchange.getRequestBody());
                String messages = request.path("messages").toString();
                Map<String, Object> message;
                String finish;
                if (messages.contains("记忆提取器")) {
                    message = Map.of("role", "assistant", "content", "{\"candidates\":[]}"); finish = "stop";
                } else {
                    var plan = plans.values().stream().filter(value -> messages.contains(value.input)).findFirst().orElseThrow();
                    plan.requests.add(request.toString());
                    int step = plan.index.getAndIncrement();
                    if (step == 0) { plan.entered.countDown(); assertTrue(plan.release.await(25, TimeUnit.SECONDS)); }
                    int finalStep = SANDBOX ? 5 : 4;
                    if (plan.handle == null || step >= finalStep) {
                        assertTrue(step <= finalStep, "意外重复的模型完成请求");
                        message = Map.of("role", "assistant", "content", "技能验收完成 " + plan.version); finish = "stop";
                    } else {
                        String name;
                        Map<String, Object> arguments;
                        if (step == 4) {
                            JsonNode function = null;
                            for (var tool : request.path("tools")) if (tool.path("function").path("parameters").path("properties")
                                    .has("script_path")) function = tool.path("function");
                            assertNotNull(function, "必须从真实已挂载工具 schema 选择 code_execute");
                            name = function.path("name").asText();
                            arguments = Map.of(
                                    "mode", "skill", "language", "python", "skill_handle", plan.handle,
                                    "script_path", "scripts/check.py", "output_files", List.of());
                        } else {
                            name = step == 0 ? "use_skill" : "read_skill_resource";
                            arguments = new LinkedHashMap<>(); arguments.put("skill_handle", plan.handle);
                            arguments.put("commentary", "读取当前冻结技能的验收材料");
                            if (step > 0) arguments.put("path", step == 1 ? "references/guide.md" : step == 2 ? "scripts/check.py" : "references/missing.md");
                            assertTrue(request.path("tools").toString().contains("\"name\":\"" + name + "\""), "动作必须来自正式模型工具目录");
                        }
                        message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", plan.input + "-" + step,
                                "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments)))));
                        finish = "tool_calls";
                    }
                }
                String responseId = UUID.randomUUID().toString();
                var usage = Map.of("prompt_tokens", 30, "completion_tokens", 5, "total_tokens", 35);
                byte[] response;
                if (request.path("stream").asBoolean()) {
                    var delta = new LinkedHashMap<String, Object>(message);
                    if (delta.containsKey("tool_calls")) {
                        @SuppressWarnings("unchecked") var calls = (List<Map<String, Object>>) delta.get("tool_calls");
                        var indexed = new LinkedHashMap<>(calls.getFirst()); indexed.put("index", 0);
                        delta.put("tool_calls", List.of(indexed));
                    }
                    String first = json.writeValueAsString(Map.of("id", responseId, "object", "chat.completion.chunk", "model", "skill-http-fixture",
                            "choices", List.of(Map.of("index", 0, "delta", delta))));
                    String last = json.writeValueAsString(Map.of("id", responseId, "object", "chat.completion.chunk", "model", "skill-http-fixture",
                            "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
                    response = ("data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                } else {
                    response = json.writeValueAsBytes(Map.of("id", responseId, "object", "chat.completion",
                        "model", "skill-http-fixture", "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)),
                        "usage", usage));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                }
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Throwable failure) { failures.add(failure); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }
        @Override public void close() {
            plans.values().forEach(plan -> plan.release.countDown());
            server.stop(0); workers.shutdownNow();
            try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "模型线程未清理"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
    }
}
