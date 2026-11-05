package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 同一浏览器会话跨标签发布技能，再由真实 Agent 运行时消费该版本。 */
class AgentSkillCrossTabBrowserE2ETest extends ChatExecutionChainE2ETest {
    private SkillActivationModel activationModel;
    private Path activationEvidence;
    @org.junit.jupiter.api.AfterEach void closeActivationModel() throws Exception {
        if (activationModel != null) {
            try {
                if (activationEvidence != null) Files.writeString(activationEvidence.resolve("actual-model-requests.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(activationModel.requests));
            } finally { activationModel.close(); }
        }
    }
    private static final KnowledgeBrowserServer FRONTEND =
            KnowledgeBrowserServer.startIsolated("agent-skill-cross-tab-");

    @DynamicPropertySource
    static void browserOrigin(DynamicPropertyRegistry registry) {
        registry.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }

    @AfterAll
    static void closeFrontend() {
        FRONTEND.close();
    }

    @Test
    void publishedSkillAppearsInOriginalMountDrawerAndRuntime() throws Exception {
        login();
        String marker = "CROSS_TAB_SKILL_" + SnowflakeIds.nextId();
        boolean resourceBudget = System.getenv("E2E_SKILL_RESOURCE_BUDGET") != null;
        boolean onDemand = resourceBudget;
        if (onDemand) {
            activationModel = new SkillActivationModel(marker); activationModel.start();
            com.skillforge.common.context.TenantContext.setTenantId(tenantId);
            try { configureTenantModel(activationModel.endpoint()); } finally { com.skillforge.common.context.TenantContext.clear(); }
        }
        var revised = send("/api/v1/agents/revise", "POST", Map.of("agent_id", agentId), accessToken);
        assertEquals(200, revised.statusCode(), revised.body());
        agentId = successData(revised).path("id").asText();
        Path evidence = Path.of("build", "runtime-e2e", "agent-skill-cross-tab-" + SnowflakeIds.nextId()).toAbsolutePath();
        activationEvidence = evidence;
        Path browser = evidence.resolve("playwright");
        Files.createDirectories(browser);
        Path admin = KnowledgeBrowserServer.repoRoot().resolve("frontend-admin");
        Path playwright = admin.resolve("node_modules/.bin").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "playwright.cmd" : "playwright");
        assertTrue(Files.isRegularFile(playwright));
        FRONTEND.bindBackend(baseUrl());
        var command = new ProcessBuilder(playwright.toString(), "test", "e2e/agent-skill-cross-tab-real.spec.ts");
        command.directory(admin.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(evidence.resolve("playwright.log").toFile());
        var env = command.environment();
        env.put("E2E_BASE_URL", FRONTEND.baseUrl());
        env.put("E2E_ARTIFACT_DIR", browser.toString());
        env.put("E2E_TENANT_SLUG", fixtureTenantSlug());
        env.put("E2E_USERNAME", "runtime-e2e-admin");
        env.put("E2E_PASSWORD", PASSWORD);
        env.put("E2E_AGENT_ID", agentId);
        env.put("E2E_SKILL_MARKER", marker);
        env.put("E2E_EXPECTED_REPLY", "fixture-answer-" + runId + (resourceBudget ? " RESOURCE_BUDGET_EXCEEDED" : ""));
        if (resourceBudget) env.put("E2E_RECOVERED_REPLY", "fixture-answer-" + runId + " RESOURCE_" + marker);
        env.remove("SDK4_LIVE_MODEL_KEY");
        env.remove("E2E_LIVE_MODEL");
        Process process = command.start();
        try (var owned = new KnowledgeBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
            while (process.isAlive() && System.nanoTime() < deadline) {
                owned.capture();
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
            owned.capture();
            assertFalse(process.isAlive(), "浏览器验收超时：" + evidence);
            String output = Files.readString(evidence.resolve("playwright.log"));
            if (process.exitValue() != 0) {
                var state = Map.of(
                        "mounts", queryRows(jdbc, tenantId,
                                "SELECT id,capability_definition_id,capability_version_id FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint)",
                                tenantId, agentId),
                        "runs", queryRows(jdbc, tenantId,
                                "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?",
                                tenantId, agentId));
                Files.writeString(evidence.resolve("skill-browser-failure-state.json"),
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
                Files.writeString(evidence.resolve("skill-browser-failure-materials.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "contexts", queryRows(jdbc, tenantId, "SELECT r.run_id,r.status,r.phase,m.schema_id,m.canonical_json FROM agent_runtime_execution_runs r LEFT JOIN agent_runtime_execution_materials m ON m.tenant_id=r.tenant_id AND m.reference=r.context_ref WHERE r.tenant_id=? AND r.agent_id=?", tenantId, agentId),
                        "actions", queryRows(jdbc, tenantId, "SELECT a.action_id,a.kind,a.status,t.result_kind,t.adopted,req.schema_id AS request_schema,req.canonical_json AS request_json,res.schema_id AS result_schema,res.canonical_json AS result_json FROM agent_runtime_execution_actions a LEFT JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id LEFT JOIN agent_runtime_execution_materials req ON req.tenant_id=a.tenant_id AND req.reference=a.request_ref LEFT JOIN agent_runtime_execution_materials res ON res.tenant_id=t.tenant_id AND res.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id IN (SELECT run_id FROM agent_runtime_execution_runs WHERE tenant_id=? AND agent_id=?) ORDER BY a.action_ordinal", tenantId, tenantId, agentId))));
            }
            assertEquals(0, process.exitValue(), "浏览器验收失败：" + evidence + "\n" + output);
        }
        var facts = objectMapper.readTree(Files.readString(browser.resolve("skill-cross-tab.json")));
        String definition = facts.path("definition").asText();
        String version = facts.path("version").asText();
        String mount = facts.path("mount").path("mount_id").asText();
        assertEquals(agentId, facts.path("agentId").asText());
        assertFalse(definition.isBlank());
        assertFalse(version.isBlank());
        assertFalse(mount.isBlank());
        assertEquals(1, facts.path("skillPublishes").asInt());
        assertEquals(1, facts.path("mountPosts").asInt());
        if ("candidate".equals(System.getenv("E2E_AGENT_PUBLISH_LOSS"))) {
            var receipt = objectMapper.readTree(Files.readString(browser.resolve("agent-publish-recovered-receipt.json"))).path("originalReceipt");
            var publications = queryRows(jdbc, tenantId, "SELECT id::text,revision,request_id::text,request_actor_ref FROM nw_agent_publications WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) ORDER BY revision", tenantId, agentId);
            assertEquals(2, publications.size(), "原初始发布加本次发布各一份，不新增重复修订");
            var publication = publications.getLast();
            assertEquals(receipt.path("publication_id").asText(), publication.get("id").toString());
            assertEquals(receipt.path("request_id").asText(), publication.get("request_id").toString());
            assertEquals(receipt.path("revision").asInt(), ((Number) publication.get("revision")).intValue());
            Files.writeString(evidence.resolve("agent-publication-receipt-database.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(publications));
        }
        String mode = System.getenv().getOrDefault("E2E_AGENT_SKILL_REFRESH_MODE", "candidate");
        assertEquals(mode, facts.path("mode").asText());
        assertEquals("baseline".equals(mode) ? 1 : 0, facts.path("manualRefreshClicks").asInt());
        assertEquals(1, countRuntime(
                "SELECT count(*) FROM nw_agent_capability_mounts WHERE tenant_id=CAST(? AS bigint) AND agent_id=CAST(? AS bigint) AND id=CAST(? AS bigint) AND capability_definition_id=CAST(? AS bigint) AND capability_version_id=CAST(? AS bigint) AND activation_mode='" + (onDemand ? "ON_DEMAND" : "ALWAYS") + "'",
                tenantId, agentId, mount, definition, version));
        String budgetMode = System.getenv("E2E_SKILL_BUDGET");
        boolean budgetExperiment = java.util.Set.of("baseline", "candidate").contains(budgetMode == null ? "" : budgetMode);
        String actualRun;
        if (budgetExperiment || onDemand) {
            sessionId = facts.path("trialSession").asText(); actualRun = facts.path("trialRun").asText();
            assertFalse(sessionId.isBlank()); assertFalse(actualRun.isBlank());
            if (budgetExperiment) assertEquals("baseline".equals(budgetMode) ? 1 : facts.path("skillEstimate").asInt(), facts.path("mount").path("skill_config").path("token_budget").asInt());
        } else {
            var publication = send("/api/v1/agents/publish", "POST", Map.of("agent_id", agentId), accessToken);
            assertEquals(200, publication.statusCode(), publication.body());
            var session = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
            assertEquals(200, session.statusCode(), session.body());
            sessionId = successData(session).path("session_id").asText(); actualRun = "run:" + runId;
        }
        try {
            var capture = budgetExperiment || onDemand ? observeSseUntil(sessionId, null, actualRun, java.util.Set.of("execution.completed", "error"))
                    : postSse(chatPayload("请简短说明当前系统能力。"));
            assertEquals(200, capture.statusCode());
            assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertFalse(capture.events().contains("error"), capture.raw());
            assertEquals(1, countRuntime(
                    "SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND agent_id=? AND session_id=? AND status='COMPLETED' AND phase='FINISHED'",
                    tenantId, actualRun, agentId, sessionId));
            var rows = queryRows(jdbc, tenantId,
                    "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' AND a.status='SUCCEEDED' ORDER BY a.action_ordinal",
                    tenantId, actualRun);
            assertEquals(onDemand ? 3 : 1, rows.size());
            var row = onDemand ? rows.getLast() : rows.getFirst();
            var properties = applicationContext.getBean(AgentRuntimeProperties.class);
            var codec = new AgentExecutionCodec(new CodecLimits(
                    properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
            var request = ModelRequestTestMaterials.decode(codec,
                    new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
            boolean hasSkillBody = request.request().messages().stream().anyMatch(message ->
                    message.textContent().contains(marker) && message.textContent().contains("Frozen capability: " + definition + " @ " + version)
                            && message.textContent().contains("skill:" + mount));
            if (budgetExperiment) {
                assertEquals("candidate".equals(budgetMode), hasSkillBody, "冻结预算决定是否注入技能正文");
                var publishedSkill = successData(get("/api/v1/capabilities/versions/detail?versionId=" + version));
                long estimatedTokens = publishedSkill.path("descriptor").path("estimated_tokens").asLong();
                assertTrue(estimatedTokens > 1, publishedSkill.toString());
                Files.writeString(evidence.resolve("skill-budget-experiment.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "agentId", agentId, "runId", actualRun, "mountId", mount, "versionId", version, "tokenBudget", facts.path("mount").path("skill_config").path("token_budget").asInt(),
                        "estimatedTokens", estimatedTokens, "skillBodyInModel", hasSkillBody,
                        "userBudgetExplanationVisible", facts.path("userBudgetExplanationVisible").asBoolean(),
                        "modelMessages", request.request().messages())));
            } else assertTrue(hasSkillBody, "实际模型上下文必须包含同一挂载的精确版本与包入口正文");
            assertEquals(onDemand ? 0 : 1, provider.requestCount());
            if (onDemand) {
                assertEquals("candidate".equals(System.getenv("E2E_SKILL_RESOURCE_BUDGET")) ? 6 : 3, activationModel.calls.get());
                assertEquals("skill:" + mount, activationModel.handle);
            }
            Files.writeString(evidence.resolve("skill-runtime.json"), objectMapper.writeValueAsString(Map.of(
                    "agentId", agentId, "runId", actualRun, "mountId", mount, "definitionId", definition,
                    "versionId", version, "skillContextVerified", hasSkillBody, "modelCalls", resourceBudget ? 3 : onDemand ? activationModel.calls.get() : provider.requestCount())));
            if (resourceBudget && "candidate".equals(System.getenv("E2E_SKILL_RESOURCE_BUDGET"))) {
                var recovered = objectMapper.readTree(Files.readString(browser.resolve("resource-budget-recovery-options.json"))).path("recovery");
                String recoveredRun = recovered.path("nextRun").path("execution_id").asText();
                assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED'", tenantId, recoveredRun));
                assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, recoveredRun));
                assertTrue(activationModel.loadedResourceRequest.path("messages").toString().contains("RESOURCE_" + marker));
                assertTrue(activationModel.loadedResourceRequest.path("messages").toString().contains("Frozen capability: " + definition + " @ " + version));
                var configurations = queryRows(jdbc, tenantId, "SELECT r.run_id,m.schema_id,m.canonical_json FROM agent_runtime_execution_runs r JOIN agent_runtime_execution_materials m ON m.tenant_id=r.tenant_id AND m.reference='execution-capabilities:' || r.run_id WHERE r.tenant_id=? AND r.run_id IN (?,?)", tenantId, actualRun, recoveredRun);
                assertEquals(2, configurations.size());
                Files.writeString(evidence.resolve("resource-budget-runtime-configurations.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(configurations));
                for (var configuration : configurations) {
                    var skills = objectMapper.readTree(configuration.get("canonical_json").toString()).path("skills");
                    assertEquals(1, skills.size());
                    assertEquals(configuration.get("run_id").toString().equals(actualRun) ? 64 : 2048, skills.get(0).path("token_budget").asInt());
                }
            }
        } finally {
            var state = Map.of(
                    "runs", queryRows(jdbc, tenantId,
                            "SELECT run_id,status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun),
                    "actions", queryRows(jdbc, tenantId,
                            "SELECT action_id,kind,status FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? ORDER BY action_ordinal", tenantId, actualRun),
                    "attempts", queryRows(jdbc, tenantId,
                            "SELECT action_id,attempt_id,result_kind,adopted FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
            Files.writeString(evidence.resolve("skill-runtime-state.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
        }
    }
    private final class SkillActivationModel implements AutoCloseable {
        final com.fasterxml.jackson.databind.ObjectMapper json;
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final List<com.fasterxml.jackson.databind.JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final String marker;
        volatile String handle;
        volatile com.fasterxml.jackson.databind.JsonNode firstRequest, secondRequest;
        volatile com.fasterxml.jackson.databind.JsonNode missingFeedbackRequest, loadedResourceRequest;
        volatile String resourcePath;

        SkillActivationModel(String marker) throws Exception {
            this.json = objectMapper; this.marker = marker;
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::respond);

        }

        void start() { server.start(); }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void respond(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            var request = json.readTree(exchange.getRequestBody());
            requests.add(request.deepCopy());
            int ordinal = calls.incrementAndGet();
            boolean recovered = ordinal > 3;
            int phase = (ordinal - 1) % 3 + 1;
            if (ordinal > ("candidate".equals(System.getenv("E2E_SKILL_RESOURCE_BUDGET")) ? 6 : 3)) throw new java.io.IOException("unexpected_model_call");
            if (phase == 1) {
                firstRequest = request.deepCopy();
                if (request.path("messages").toString().contains(marker))
                    throw new java.io.IOException("on_demand_body_loaded_before_activation");
                for (var tool : request.path("tools")) {
                    if ("use_skill".equals(tool.path("function").path("name").asText()))
                        handle = tool.path("function").path("parameters").path("properties").path("skill_handle").path("enum").path(0).asText();
                }
                if (handle == null || handle.isBlank()) throw new java.io.IOException("on_demand_directory_missing");
            } else if (phase == 2) {
                secondRequest = request.deepCopy();
                if (!request.path("messages").toString().contains(marker) || !request.path("messages").toString().contains(handle))
                    throw new java.io.IOException("activated_body_not_returned");
                if (request.path("messages").toString().contains("RESOURCE_" + marker))
                    throw new java.io.IOException("reference_loaded_before_read");
                for (var input : request.path("messages")) {
                    if ("tool".equals(input.path("role").asText())) {
                        var listed = java.util.regex.Pattern.compile("(?m)^- (references/[^\\r\\n]+?) \\| ").matcher(input.path("content").asText());
                        if (listed.find()) resourcePath = listed.group(1);
                    }
                }
            } else if (phase == 3) {
                missingFeedbackRequest = request.deepCopy();
                    if (recovered) {
                        loadedResourceRequest = request.deepCopy();
                        if (!request.path("messages").toString().contains("LOADED") || !request.path("messages").toString().contains("RESOURCE_" + marker)) throw new java.io.IOException("recovered_resource_missing");
                    } else if (!request.path("messages").toString().contains("BUDGET_EXCEEDED") || request.path("messages").toString().contains("RESOURCE_" + marker)) throw new java.io.IOException("resource_budget_feedback_invalid");
            }
            boolean action = phase < 3;
            String function = phase == 1 ? "use_skill" : "read_skill_resource";
            var args = json.createObjectNode().put("skill_handle", handle).putNull("commentary");
            if (phase == 2) args.put("path", resourcePath);
            String arguments = json.writeValueAsString(args);
            var invocation = Map.of("name", function, "arguments", arguments);
            Object message = action
                    ? Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                            "id", "skill-call-" + ordinal, "type", "function", "function", invocation)))
                    : Map.of("role", "assistant", "content",
                            "fixture-answer-" + runId + (!recovered ? " RESOURCE_BUDGET_EXCEEDED" : " RESOURCE_" + marker));
            String finish = action ? "tool_calls" : "stop";
            String model = request.path("model").asText();
            var usage = Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18);
            String body, type;
            if (!request.path("stream").asBoolean()) {
                type = "application/json";
                body = json.writeValueAsString(Map.of("id", "skill-model", "object", "chat.completion",
                        "model", model, "choices", List.of(Map.of(
                                "index", 0, "message", message, "finish_reason", finish)), "usage", usage));
            } else {
                type = "text/event-stream";
                Object delta = action
                        ? Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                                "index", 0, "id", "skill-call-" + ordinal, "type", "function", "function", invocation)))
                        : message;
                String first = json.writeValueAsString(Map.of("id", "skill-model", "object", "chat.completion.chunk",
                        "model", model, "choices", List.of(Map.of("index", 0, "delta", delta))));
                String last = json.writeValueAsString(Map.of("id", "skill-model", "object", "chat.completion.chunk",
                        "model", model, "choices", List.of(Map.of(
                                "index", 0, "delta", Map.of(), "finish_reason", finish)), "usage", usage));
                body = "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        }

        @Override public void close() { server.stop(0); }
    }
}
