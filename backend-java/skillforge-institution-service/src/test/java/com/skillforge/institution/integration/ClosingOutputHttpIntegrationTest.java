package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.billing.pricing.ModelPriceSource;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.ExecutionManifest;
import com.skillforge.execution.ExecutionResponse;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.PublicResults;
import com.skillforge.institution.runtime.AgentRuntimeExecutionSources;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionOutputs;
import com.skillforge.runtime.session.AgentExecutionTime;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
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
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;

/** 真实宿主HTTP/PG/Redis与预算收尾；仅模型stream和外部Sandbox回执受控，不执行真实Python。 */
@TestExecutionListeners(listeners = ClosingOutputHttpIntegrationTest.SandboxCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ClosingOutputHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "SyntheticClosingOutput123";
    private static final String SANDBOX_TOKEN = "synthetic-closing-sandbox-token-000000000000";
    private static final String FILE = "report.md";
    private static final String CONTENT = "# 预算收尾回归\n\n这是受控 Sandbox 生成的模拟报告，不代表真实 Python Runner 执行。\n";
    private static final String CODE = "from pathlib import Path\nPath('/workspace/output/report.md').write_text(" +
            "'# 预算收尾回归\\n\\n这是受控 Sandbox 生成的模拟报告，不代表真实 Python Runner 执行。\\n', encoding='utf-8')\n";
    private static final String ANSWER = "模拟报告已核验并正式交付。";
    /** 不扫描或预绑定；两个正式监听器首次bind取得候选，碰撞即明确失败。 */
    private static final int APPLICATION_PORT = ThreadLocalRandom.current().nextInt(20_000, 40_000);
    private static final int PREVIEW_PORT = ThreadLocalRandom.current().nextInt(40_000, 60_000);
    private static final Path EVIDENCE = evidenceDirectory();
    private static final Sandbox SANDBOX = sandbox();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired EndUserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperationalSettingsService settings;
    @Autowired RunSettingsService runSettings;
    @Autowired PlatformModelCatalogService models;
    @Autowired ModelPriceSource prices;
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired JdbcExecutionBudgets budgets;
    @Autowired AgentRuntimeProperties runtime;
    @Autowired ApplicationContext application;
    @Autowired ArtifactAccess artifactAccess;
    @MockitoSpyBean ModelProviderRuntime provider;
    /** 仅记录真实方法结果；不替换预算、时钟或成果判定。 */
    @MockitoSpyBean AgentRuntimeExecutionSources sources;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Admission> admissions = new CopyOnWriteArrayList<>();
    private final List<ModelProvider.InvocationRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private String observedTenant;
    private String observedRun;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> APPLICATION_PORT);
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("agent-runtime.worker-lease-duration", () -> "210s");
        registry.add("skillforge.code-execution.endpoint", SANDBOX::endpoint);
        registry.add("skillforge.code-execution.service-token", () -> SANDBOX_TOKEN);
        registry.add("skillforge.code-execution.artifact-root", () -> EVIDENCE.resolve("artifacts").toString());
        registry.add("skillforge.code-execution.preview-port", () -> PREVIEW_PORT);
        registry.add("skillforge.code-execution.preview-origin", () -> "http://127.0.0.1:" + PREVIEW_PORT);
        registry.add("skillforge.code-execution.ticket-key", () -> "synthetic-closing-ticket-key-00000000000");
        registry.add("skillforge.code-execution.platform-origin", () -> "http://localhost:" + APPLICATION_PORT);
        registry.add("skillforge.code-execution.download-origin", () -> "http://127.0.0.1:" + APPLICATION_PORT);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    /** Spring关闭Runtime并等待活动执行停止后才关闭借给宿主的Sandbox端点。 */
    public static final class SandboxCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return DirtiesContextTestExecutionListener.ORDER - 1; }
        @Override public void afterTestClass(TestContext context) { SANDBOX.close(); }
    }

    @Test @Timeout(150)
    void actualOutputBudgetSwitchKeepsFrozenTimeAndAdoptsFinalReceiptThroughHttp() throws Exception {
        Files.createDirectories(EVIDENCE);
        System.out.println("CLOSING_OUTPUT_HTTP_EVIDENCE " + EVIDENCE);
        assertEquals(APPLICATION_PORT, port);
        artifactAccess.requireConfiguration();
        assertTrue(SANDBOX_TOKEN.length() >= 32);
        assertTrue(runtime.getWorkerLeaseDuration().compareTo(Duration.ofSeconds(200)) >= 0);
        var preview = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PREVIEW_PORT + "/p/invalid/index.html"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, preview.statusCode(), "正式独立预览监听器应已启动并拒绝无效票据");
        String marker = Long.toString(SnowflakeIds.nextId());
        String slug = "closing-http-" + marker;
        String tenant = createTenant(tenants, plans, slug);
        String username = "closing-" + marker;
        TenantContext.setTenantId(tenant);
        createTestTenantAdministrator(tenant, username, PASSWORD);
        var user = new EndUserDO(); user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.valueOf(tenant));
        user.setDisplayName("预算收尾模拟用户"); user.setStatus("active");
        user.setFirstSeenAt(Instant.now()); user.setLastActiveAt(user.getFirstSeenAt()); user.setCreatedAt(user.getFirstSeenAt());
        users.insert(user); TenantContext.clear();
        String token = request("POST", "/auth/login", null,
                Map.of("tenant_slug", slug, "username", username, "password", PASSWORD), 200).path("access_token").asText();
        assertFalse(token.isBlank());
        long actor = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_platform_members(id,username,password_hash,status) VALUES(?,?,?,'disabled')",
                actor, "closing-settings-" + marker, "unused");
        var original = settings.read("sandbox");
        var enabled = new LinkedHashMap<>(original.values()); enabled.put("enabled", true);
        var report = new LinkedHashMap<String, Object>();
        report.put("status", "FAILED"); report.put("tenant_id", tenant);
        report.put("external_boundary", "controlled_model_stream_and_sandbox_http_no_python_or_paid_supplier");
        Long changedRevision = null;
        try {
            changedRevision = settings.update("sandbox", original.revision(), enabled, actor).revision();
            installControlledModel();
            doAnswer(invocation -> {
                ExecutionStore.Run run = invocation.getArgument(0);
                AgentExecutionOutputs.Policy policy = invocation.getArgument(2);
                boolean actual = (boolean) invocation.callRealMethod();
                admissions.add(new Admission(run.identity().runRef().runId(), policy.closing(), actual));
                return actual;
            }).when(sources).admitOutput(any(), any(), any(), anyLong(), anyLong(), anyLong());
            var model = models.create(new ModelWrite(null, "预算收尾受控模型", "LLM", true,
                    "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions", "bearer-api-key",
                    "https://api.deepseek.com", "deepseek-chat", "synthetic-no-paid-dispatch", 4096, 131072, 1024,
                    0.2, "DISABLED", 30, null, null, new BigDecimal("25"), new BigDecimal("125"), null, "CNY"), "closing-http-fixture");
            var publication = models.publish(model.id(), model.revision(), "closing-http-fixture");
            String priceVersion = prices.snapshot(new ModelRevisionRef(model.id(), publication.publishedRevision())).version()
                    + "@fx-cny-identity-v1";
            report.put("pricing_snapshot_version", priceVersion);
            TenantContext.setTenantId(tenant);
            try { models.select(tenant, new ModelSelections(model.id(), null, null)); provisioner.provision(tenant); }
            finally { TenantContext.clear(); }
            requireRunningRuntimeWorkers(application, runtime);
            String agent = request("POST", "/agents", token, Map.of("agent_key", "closing-" + marker,
                    "name", "预算收尾回归", "system_prompt", "生成一个明确模拟的报告候选，冻结正式文件后回复。"), 200).path("id").asText();
            request("PATCH", "/agents/update", token, Map.of("agent_id", agent, "payload", Map.of(
                    "execution_config", Map.of("max_business_model_calls", 3, "max_tool_calls", 4,
                            "max_total_tokens", 1_000_000, "max_wall_time_seconds", 600),
                    "config", Map.of("memory", Map.of("automatic_extraction", false)))), 200);
            mount(token, agent, "code_execute", marker); mount(token, agent, "finalize_task_outputs", marker);
            request("POST", "/agents/publish", token, Map.of("agent_id", agent), 200);
            String session = request("POST", "/chat/sessions", token,
                    Map.of("agent_id", agent, "end_user_id", user.getId()), 200).path("session_id").asText();
            String input = "closing-input-" + marker;
            var accepted = request("POST", "/chat", token, Map.of("session_id", session, "agent_id", agent,
                    "client_message_id", input, "content", List.of(Map.of("type", "text", "text", "生成并正式交付一份明确模拟的预算回归报告。")),
                    "context", Map.of("end_user_id", user.getId())), 202);
            String run = accepted.path("execution_id").asText(); assertFalse(run.isBlank());
            observedTenant = tenant; observedRun = run;
            report.put("run_id", run); report.put("session_id", session); report.put("input_id", input);
            TenantContext.setTenantId(tenant);
            try {
                var frozen = runSettings.requireFrozen(tenant, run);
                assertEquals(model.id(), frozen.modelId());
                assertEquals(publication.publishedRevision().longValue(), frozen.modelRevision());
                assertEquals(changedRevision, frozen.groups().get("sandbox"));
                var sandbox = runSettings.read(frozen, "sandbox");
                var artifacts = runSettings.read(frozen, "artifacts");
                assertTrue(sandbox.bool("enabled")); assertTrue(sandbox.integer("execution_timeout_seconds") > 0);
                assertTrue(sandbox.integer("max_artifact_files") > 0); assertTrue(artifacts.longValue("transfer_timeout_seconds") > 0);
                report.put("frozen_config", frozen); report.put("sandbox_policy", sandbox); report.put("artifact_policy", artifacts);
            } finally { TenantContext.clear(); }
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(75)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                healthy();
                var rows = queryRows(jdbc, tenant, "SELECT status,phase,lease_claim_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenant, run);
                assertEquals(1, rows.size());
                assertEquals("COMPLETED", rows.getFirst().get("status")); assertEquals("FINISHED", rows.getFirst().get("phase"));
                assertNull(rows.getFirst().get("lease_claim_id"));
            });
            healthy(); assertEquals(3, calls.get()); assertEquals(1, SANDBOX.submissions.get()); assertEquals(1, SANDBOX.downloads.get());
            assertTrue(admissions.stream().anyMatch(value -> value.run().equals(run) && !value.before() && value.after()),
                    "必须由真实admitOutput从普通阶段切换；不能仅验证已处于CLOSING的请求");
            var reference = new RunRef(new TenantId(tenant), run);
            var snapshot = budgets.snapshot(reference);
            assertEquals(ExecutionBudgetPort.Phase.CLOSING, snapshot.phase());
            assertEquals(3, snapshot.runConsumed().businessModels()); assertEquals(2, snapshot.runConsumed().tools());
            assertEquals(0, snapshot.runReserved().businessModels());
            assertEquals(3, snapshot.registration().policy().requireEnforced().run().businessModels());
            report.put("budget", snapshot); report.put("admissions", admissions);
            verifyPersistedRequests(tenant, run, report);
            assertEquals(5, queryCount(jdbc, tenant, "SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED' AND result_ref IS NOT NULL", tenant, run));
            var outputs = request("GET", "/chat/sessions/deliverables?sessionId=" + session + "&includeHistory=true", token, null, 200);
            assertEquals(1, outputs.size()); var deliverable = outputs.get(0);
            assertEquals(run, deliverable.path("run_id").asText()); assertEquals("ready", deliverable.path("status").asText());
            assertEquals(1, deliverable.path("files").size()); var formalFile = deliverable.path("files").get(0);
            assertEquals(FILE, formalFile.path("path").asText()); report.put("deliverables", outputs);
            var ticket = request("POST", "/chat/deliverables/download-ticket", token,
                    Map.of("id", deliverable.path("id").asText(), "file_id", formalFile.path("file_id").asText()), 200);
            URI url = URI.create(ticket.path("url").asText());
            assertEquals("127.0.0.1", url.getHost()); assertEquals(port, url.getPort());
            assertEquals("/api/v1/chat/deliverable-content/detail", url.getPath());
            var download = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode()); assertEquals(CONTENT, new String(download.body(), StandardCharsets.UTF_8));
            assertEquals(sha(CONTENT.getBytes(StandardCharsets.UTF_8)), sha(download.body()));
            Files.write(EVIDENCE.resolve("downloaded-report.md"), download.body());
            assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_deliverable_revisions WHERE tenant_id=? AND run_id=?", Long.valueOf(tenant), run));
            var files = queryRows(jdbc, tenant, "SELECT artifact_id,path,size,sha256 FROM nw_execution_artifact_files WHERE tenant_id=? AND artifact_id IN (SELECT id FROM nw_execution_artifacts WHERE tenant_id=? AND run_id=?)", Long.valueOf(tenant), Long.valueOf(tenant), run);
            assertEquals(1, files.size()); var file = files.getFirst(); assertEquals(FILE, file.get("path"));
            assertEquals(sha(CONTENT.getBytes(StandardCharsets.UTF_8)), file.get("sha256"));
            Path actualFile = EVIDENCE.resolve("artifacts/ready").resolve(tenant).resolve(file.get("artifact_id").toString()).resolve(FILE);
            assertEquals(CONTENT, Files.readString(actualFile)); report.put("file_manifest", files);
            var history = request("GET", "/chat/history?session_id=" + session, token, null, 200);
            assertTrue(history.path("messages").toString().contains(ANSWER)); report.put("history", history);
            var monthly = request("GET", "/billing/monthly?page_size=200", token, null, 200);
            assertEquals(3, monthly.path("model_calls").asLong()); assertEquals(0, monthly.path("missing_cost_count").asLong());
            assertEquals("36", monthly.path("prompt_tokens").asText()); assertEquals("24", monthly.path("completion_tokens").asText());
            assertEquals(0, new BigDecimal("0.000039").compareTo(new BigDecimal(monthly.path("total_amount").asText())));
            for (var item : monthly.path("items")) {
                assertEquals(priceVersion, item.path("pricing_snapshot_version").asText());
                assertTrue(item.path("pricing_model_id").isNull());
                assertTrue(item.path("pricing_model_revision").isNull());
                assertEquals(run, item.path("run_id").asText()); assertEquals("priced", item.path("cost_status").asText());
            }
            report.put("monthly", monthly);
            var measured = queryRows(jdbc, tenant, "SELECT invocation_id,attempt_id,root_input_id,run_id,usage_source,prompt_tokens,completion_tokens,pricing_snapshot_version,total_amount_cents FROM nw_usage_events WHERE tenant_id=? AND run_id=? AND event_type='llm.chat' AND usage_event_kind='measured'", Long.valueOf(tenant), run);
            assertEquals(3, measured.size());
            for (var usage : measured) {
                assertEquals("PROVIDER_REPORTED", usage.get("usage_source")); assertEquals(input, usage.get("root_input_id"));
                assertEquals(priceVersion, usage.get("pricing_snapshot_version"));
                assertEquals(12L, ((Number) usage.get("prompt_tokens")).longValue()); assertEquals(8L, ((Number) usage.get("completion_tokens")).longValue());
                assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.action_id=? AND t.attempt_id=? AND t.adopted", tenant, run, usage.get("invocation_id"), usage.get("attempt_id")));
            }
            report.put("measured_usage", measured);
            var trace = request("GET", "/billing/traces/detail?root_input_id=" + input, token, null, 200);
            assertFalse(trace.path("timeline").isEmpty()); assertEquals(input, trace.path("root_input_id").asText());
            report.put("trace", trace);
            assertEquals(3, calls.get(), "只读历史、月费用和轨迹不得重发模型");
            report.put("status", "PASSED");
        } finally {
            report.put("model_calls", calls.get()); report.put("sandbox_submissions", SANDBOX.submissions.get());
            report.put("admissions", admissions);
            Files.writeString(EVIDENCE.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            if (changedRevision != null) settings.update("sandbox", changedRevision, original.values(), actor);
            if (observedRun != null) persistReadback("final-readback.json");
        }
    }

    private record Admission(String run, boolean before, boolean after) { }

    private void verifyPersistedRequests(String tenant, String run, Map<String, Object> report) throws Exception {
        var rows = queryRows(jdbc, tenant, "SELECT a.action_ordinal,m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' ORDER BY a.action_ordinal", tenant, run);
        assertEquals(3, rows.size());
        var codec = new AgentExecutionCodec(new CodecLimits(runtime.getMaxPayloadCodeUnits(), runtime.getMaxPayloadNodes(), runtime.getMaxPayloadDepth()));
        var policies = new ArrayList<Boolean>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i); long ordinal = ((Number) row.get("action_ordinal")).longValue();
            var decoded = ModelRequestTestMaterials.decode(codec, new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenant);
            var request = decoded.request();
            AgentExecutionTime.frozen(request.messages(), ordinal);
            assertEquals("time-context:" + ordinal, request.messages().getLast().messageId());
            if (i == 0) assertTrue(request.messages().stream().noneMatch(message -> message.messageId().startsWith("execution-output-policy:")));
            else assertEquals("execution-output-policy:" + ordinal, request.messages().get(request.messages().size() - 2).messageId());
            assertEquals(1, request.messages().stream().filter(message -> message.messageId().startsWith("time-context:")).count());
            assertEquals(request.messages().getLast().textContent(), requests.get(i).messages().getLast().textContent());
            policies.add(decoded.output().orElseThrow().policy().closing());
        }
        assertEquals(List.of(false, true, true), policies);
        report.put("frozen_model_requests", rows);
    }

    private void installControlledModel() {
        doAnswer(invocation -> {
            ModelProvider.InvocationRequest request = invocation.getArgument(1);
            return (Flow.Publisher<ModelProvider.StreamEvent>) subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean sent;
                @Override public void request(long count) {
                    if (sent || count <= 0) return; sent = true;
                    try {
                        int round = calls.incrementAndGet(); requests.add(request);
                        Files.writeString(EVIDENCE.resolve("model-tool-schemas-" + round + ".json"),
                                json.writerWithDefaultPrettyPrinter().writeValueAsString(request.tools()));
                        Files.writeString(EVIDENCE.resolve("model-safe-messages-" + round + ".json"),
                                json.writerWithDefaultPrettyPrinter().writeValueAsString(request.messages()));
                        var toolCalls = new ArrayList<ModelProvider.ToolCall>();
                        if (round == 1) {
                            var args = Map.of("mode", "inline", "language", "python", "code", CODE,
                                    "dependencies", List.of(), "output_files", List.of(FILE));
                            toolCalls.add(call(request, "output_files", "closing-code", args));
                        } else if (round == 2) {
                            String artifact = artifactId(request);
                            var representation = Map.of("role", "source", "format", "md", "primary_preview", true,
                                    "files", List.of(Map.of("artifact_id", artifact, "path", FILE)));
                            var deliverable = Map.of("key", "closing-report", "title", "模拟预算回归报告", "kind", "document",
                                    "representations", List.of(representation));
                            toolCalls.add(call(request, "deliverables", "closing-finalize", Map.of("deliverables", List.of(deliverable), "evidence", List.of())));
                        } else if (round != 3) throw new IllegalStateException("unexpected_model_round");
                        var usage = new ModelPort.ModelUsage(OptionalLong.of(12), OptionalLong.of(8));
                        subscriber.onNext(new ModelProvider.StreamEvent.Completed(new ModelProvider.InvocationResult.Completed(
                                round == 3 ? ANSWER : null, toolCalls, request.modelId(), usage, round == 3 ? "stop" : "tool_calls")));
                        subscriber.onComplete();
                    } catch (Throwable problem) { failure.compareAndSet(null, problem); subscriber.onError(problem); }
                }
                @Override public void cancel() { sent = true; }
            });
        }).when(provider).stream(eq("deepseek"), any());
    }

    private ModelProvider.ToolCall call(ModelProvider.InvocationRequest request, String property, String id, Map<String, ?> args) throws Exception {
        var selected = request.tools().stream().filter(tool -> {
            try { return tool.kind() == ModelPort.ModelToolKind.TOOL_CALL
                    && json.readTree(tool.inputSchema().canonicalJson()).path("properties").has(property); }
            catch (IOException invalid) { throw new IllegalArgumentException(invalid); }
        }).toList();
        if (selected.size() != 1) throw new IllegalStateException("exact_registered_tool_required:" + property);
        String arguments = json.writeValueAsString(args);
        SessionRuntimePayloads.validateAgainstSchema(selected.getFirst().inputSchema(), new TypedPayload("application/json", arguments),
                new CodecLimits(runtime.getMaxPayloadCodeUnits(), runtime.getMaxPayloadNodes(), runtime.getMaxPayloadDepth()));
        return new ModelProvider.ToolCall(id, selected.getFirst().name(), arguments);
    }

    private String artifactId(ModelProvider.InvocationRequest request) throws Exception {
        var paired = request.messages().stream().filter(message -> message.role() == ModelProvider.Role.TOOL
                && "closing-code".equals(message.toolCallId())).toList();
        if (paired.size() != 1 || paired.getFirst().contents().isEmpty()
                || !(paired.getFirst().contents().getFirst() instanceof ModelContent.Text structured))
            throw new IllegalStateException("actual_artifact_receipt_required");
        // AgentExecutionCodec.toolContents 将安全 structuredContent 作为首条Text交给模型，不携带工具结果信封。
        var output = json.readTree(structured.text());
        var value = output.path("artifacts");
        if (!output.path("success").asBoolean() || !"ready".equals(output.path("delivery_status").asText())
                || !value.isArray() || value.size() != 1 || value.get(0).path("id").asText().isBlank())
            throw new IllegalStateException("actual_artifact_receipt_required");
        return value.get(0).path("id").asText();
    }

    private void mount(String token, String agent, String name, String marker) throws Exception {
        JsonNode definition = null;
        for (var entry : request("GET", "/capabilities/definitions?capability_type=TOOL&status=PUBLISHED", token, null, 200))
            if (("tool:" + name).equals(entry.path("capability_key").asText())) definition = entry;
        assertNotNull(definition); String definitionId = definition.path("id").asText();
        String publishedKey = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor())
                .entries().stream().filter(entry -> name.equals(entry.name())).findFirst().orElseThrow().publishedVersionKey();
        String version = null;
        for (var entry : request("GET", "/capabilities/definitions/versions?status=PUBLISHED&definitionId=" + definitionId, token, null, 200))
            if (publishedKey.equals(entry.path("version_key").asText())) version = entry.path("id").asText();
        assertNotNull(version); String deployment = "closing-" + name.replace('_', '-') + "-" + marker;
        var created = request("POST", "/capabilities/deployments", token, Map.of("deployment_key", deployment,
                "tool_version_id", Long.valueOf(version), "provider_type", "PLATFORM", "provider_ref", name, "environment", "TEST", "status", "ACTIVE"), 201);
        request("POST", "/agents/capability-mounts", token, Map.of("agent_id", agent, "payload", Map.of("capability_type", "TOOL",
                "capability_definition_id", definitionId, "capability_version_id", version, "priority", 0,
                "tool_config", Map.of("tool_deployment_id", deployment, "tool_deployment_revision", created.path("revision").asLong()))), 201);
    }

    private JsonNode request(String method, String path, String token, Object body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        var envelope = json.readTree(response.body()); assertEquals("OK", envelope.path("code").asText(), response.body());
        return envelope.path("data");
    }

    private void healthy() throws IOException {
        Throwable problem = failure.get() == null ? SANDBOX.failure.get() : failure.get();
        if (problem != null) {
            persistReadback("first-failure-readback.json");
            throw new IllegalStateException("controlled_fixture_failed", problem);
        }
        if (observedRun != null) {
            var runs = queryRows(jdbc, observedTenant, "SELECT status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", observedTenant, observedRun);
            if (runs.size() == 1) {
                String status = runs.getFirst().get("status").toString(), phase = runs.getFirst().get("phase").toString();
                if (List.of("SUSPENDED", "UNKNOWN", "UNCERTAIN", "FAILED", "TERMINATED", "CANCELLED").contains(status) || "UNCERTAIN".equals(phase)) {
                    persistReadback("first-failure-readback.json");
                    throw new IllegalStateException("run_cannot_complete_without_external_input:" + status + ":" + phase);
                }
            }
        }
    }

    /** 原Run的准确只读快照；不查询最新Run、不恢复或重放任何未知动作。 */
    private void persistReadback(String name) throws IOException {
        if (observedRun == null) return;
        var facts = new LinkedHashMap<String, Object>();
        facts.put("run", queryRows(jdbc, observedTenant, "SELECT run_id,status,phase,revision,fence,decisions,tool_calls,current_action_id,lease_claim_id,context_ref FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", observedTenant, observedRun));
        facts.put("actions", queryRows(jdbc, observedTenant, "SELECT action_id,action_ordinal,kind,status,request_ref,current_attempt,selected_attempt FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal LIMIT 10", observedTenant, observedRun));
        facts.put("attempts", queryRows(jdbc, observedTenant, "SELECT attempt_id,action_id,result_kind,result_ref,uncertainty_ref,feedback_ref,external_operation,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? ORDER BY attempt_id LIMIT 10", observedTenant, observedRun));
        facts.put("materials", queryRows(jdbc, observedTenant, "SELECT reference,schema_id,canonical_json FROM agent_runtime_execution_materials WHERE tenant_id=? AND reference IN (SELECT request_ref FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? UNION SELECT result_ref FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? UNION SELECT uncertainty_ref FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? UNION SELECT feedback_ref FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?) ORDER BY reference LIMIT 30", observedTenant, observedTenant, observedRun, observedTenant, observedRun, observedTenant, observedRun, observedTenant, observedRun));
        facts.put("events", queryRows(jdbc, observedTenant, "SELECT event_sequence,type,canonical_json FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? ORDER BY event_sequence LIMIT 40", observedTenant, observedRun));
        facts.put("run_settings", queryRows(jdbc, observedTenant, "SELECT snapshot::text AS snapshot FROM nw_host_run_settings WHERE tenant_id=? AND run_id=?", observedTenant, observedRun));
        facts.put("budget", budgets.snapshot(new RunRef(new TenantId(observedTenant), observedRun)));
        facts.put("admissions", admissions);
        Files.writeString(EVIDENCE.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(facts));
    }

    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    /** 临时目录由测试进程java.io.tmpdir拥有；本地lane由父执行者统一指定E盘。 */
    private static Path evidenceDirectory() {
        try { return Files.createTempDirectory("closing-output-http-"); }
        catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Sandbox sandbox() {
        try { return new Sandbox(); } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    /** 只替代外部Runner：核对正式执行manifest，返回明确模拟文件与相符摘要；宿主仍真实收集/扫描/发布。 */
    private static final class Sandbox implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final HttpServer server;
        private final AtomicInteger submissions = new AtomicInteger();
        private final AtomicInteger downloads = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile ExecutionManifest manifest;
        Sandbox() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/executions", this::serve); server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private void serve(HttpExchange exchange) throws IOException {
            try {
                if (!("Bearer " + SANDBOX_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) throw new IOException("sandbox_fixture_auth_invalid");
                byte[] result;
                if (exchange.getRequestURI().getPath().endsWith("/artifact-inputs") && "POST".equals(exchange.getRequestMethod())) {
                    if (submissions.incrementAndGet() != 1) throw new IOException("duplicate_sandbox_submission");
                    try (var zip = new ZipInputStream(exchange.getRequestBody())) {
                        var first = zip.getNextEntry();
                        if (first == null || !ExecutionLimits.REQUEST_ENTRY.equals(first.getName())) throw new IOException("request_manifest_missing");
                        manifest = json.readValue(zip.readAllBytes(), ExecutionManifest.class);
                        if (zip.getNextEntry() != null || !manifest.artifacts().isEmpty()
                                || !CODE.equals(manifest.execution().code()) || !List.of(FILE).equals(manifest.execution().outputFiles())) throw new IOException("request_manifest_mismatch");
                    }
                    Files.writeString(EVIDENCE.resolve("runner-request-manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
                    var file = new ArtifactFile(FILE, CONTENT.getBytes(StandardCharsets.UTF_8).length, sha(CONTENT.getBytes(StandardCharsets.UTF_8)));
                    result = json.writeValueAsBytes(new ExecutionResponse(manifest.execution().taskId(), ExecutionState.COMPLETED, "completed", 0,
                            false, false, null, null, List.of(file), List.of(), null,
                            new PublicResults(List.of(new PublicResults.Text("plain", "受控 Sandbox 返回明确模拟的报告文件。")))));
                } else if (exchange.getRequestURI().getPath().endsWith("/files") && "GET".equals(exchange.getRequestMethod())) {
                    if (manifest == null || !exchange.getRequestURI().getRawQuery().equals("taskId=" + manifest.execution().taskId())) throw new IOException("exact_task_required");
                    downloads.incrementAndGet();
                    var bytes = new ByteArrayOutputStream();
                    try (var zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(FILE)); zip.write(CONTENT.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
                    result = bytes.toByteArray(); Files.write(EVIDENCE.resolve("runner-output.zip"), result);
                } else throw new IOException("unexpected_sandbox_route");
                exchange.sendResponseHeaders(200, result.length); exchange.getResponseBody().write(result);
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
                byte[] message = "controlled_sandbox_failed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, message.length); exchange.getResponseBody().write(message);
            } finally { exchange.close(); }
        }
        @Override public void close() { server.stop(0); }
    }
}
