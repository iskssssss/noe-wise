package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.dto.request.WorkflowMountConfigRequest;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.workflow.WorkflowBuilderInstallationService;
import com.skillforge.core.workflow.WorkflowDraftLifecycleService;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.workflow.mapper.WorkflowInstanceMapper;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.service.CapabilityMountManagementService;
import com.skillforge.institution.service.AgentManagementService;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 发布工作流经真实 Chat、模型 HTTP、SdkWorkflowPortal 与 Session 恢复闭合人工确认链。 */
@org.springframework.context.annotation.Import(DeterministicModelCatalogConfiguration.class)
class ChatWorkflowRuntimeE2ETest extends AbstractIntegrationTest {
    private static final String PASSWORD = "RuntimeE2e123";
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExecutionStore executionStore;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private TenantMemberMapper members;
    @Autowired private EndUserMapper users;
    @Autowired private AgentMapper agents;
    @Autowired private SessionMapper sessions;
    @Autowired private PasswordEncoder passwords;
    @Autowired private PlatformModelCatalogService modelCatalog;
    @Autowired private AgentRuntimeHostProvisioner provisioner;
    @Autowired private ApplicationContext application;
    @Autowired private AgentRuntimeProperties properties;
    @Autowired private WorkflowDraftLifecycleService drafts;
    @Autowired private WorkflowBuilderInstallationService installations;
    @Autowired private WorkflowInstanceMapper instances;
    @Autowired private CapabilityCatalogService catalog;
    @Autowired private CapabilityMountManagementService mounts;
    @Autowired private AgentManagementService agentManagement;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private WorkflowModel provider;
    private String tenantId, slug, sessionId, inputId, agentId, token;
    private long userId;

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
    }

    @BeforeEach
    void resources() throws Exception {
        inputId = "workflow-e2e-" + SnowflakeIds.nextId();
        slug = "workflow-" + SnowflakeIds.nextId();
        sessionId = "sess_" + inputId;
        provider = new WorkflowModel(mapper);
        provider.server.start();
        tenantId = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenantId);
        try {
            long tenant = Long.parseLong(tenantId);
            PlatformModelFixtures.selectLlm(modelCatalog, tenantId,
                    "http://127.0.0.1:" + provider.server.getAddress().getPort(),
                    "workflow-fixture", "workflow-fixture-key",
                    "custom", "custom-openai-compatible", "custom-v1");
            provisioner.provision(tenantId);
            requireRunningRuntimeWorkers(application, properties);
            var member = new TenantMemberDO();
            member.setId(SnowflakeIds.nextId()); member.setTenantId(tenant);
            member.setUsername("workflow-admin"); member.setRole("admin");
            member.setPermissions(List.of()); member.setStatus("active");
            member.setPasswordHash(passwords.encode(PASSWORD)); member.setCreatedAt(Instant.now());
            members.insert(member);
            initializeTenantAdministrator(tenantId, member.getId());
            var user = new EndUserDO();
            userId = SnowflakeIds.nextId(); user.setId(userId); user.setTenantId(tenant);
            user.setDisplayName("Workflow E2E");
            user.setStatus("active"); user.setFirstSeenAt(Instant.now());
            user.setLastActiveAt(Instant.now()); user.setCreatedAt(Instant.now()); users.insert(user);
            var agent = new AgentDO();
            agent.setId(SnowflakeIds.nextId()); agentId = agent.getId().toString();
            agent.setTenantId(tenant); agent.setAgentKey("agent-" + inputId); agent.setName("Workflow E2E");
            agent.setSystemPrompt("请调用已挂载的工作流，完成确认后回答。");
            agent.setRoleDescription("Workflow E2E");
            agent.setConfig(Map.of()); agent.setStatus("draft");
            agent.setCreatedAt(Instant.now()); agent.setUpdatedAt(Instant.now()); agents.insert(agent);
            var session = new SessionDO();
            session.setId(SnowflakeIds.nextId()); session.setSessionId(sessionId); session.setTenantId(tenant);
            session.setEndUserId(userId); session.setAgentId(agent.getId()); session.setMessageCount(0);
            session.setLastEventId(0L); session.setMetadata(Map.of()); session.setCreatedAt(Instant.now());
            session.setUpdatedAt(Instant.now()); sessions.insert(session);
            String workflowId = "workflow-" + inputId;
            var draft = drafts.create(tenant, workflowId, definition());
            var published = drafts.publish(tenant, workflowId, draft.revision());
            var installation = installations.installOrUpgrade(tenant, workflowId, Map.of(), null,
                    WorkflowInstallationBindings.empty());
            var installed = instances.selectOne(new LambdaQueryWrapper<WorkflowInstanceDO>()
                    .eq(WorkflowInstanceDO::getTenantId, tenant)
                    .eq(WorkflowInstanceDO::getInstanceId, installation.instanceId()));
            assertNotNull(installed);
            var version = catalog.getVersion(CapabilityCatalogScope.tenant(tenant), published.capabilityVersionId());
            var mounted = withAuthenticatedMember(member.getId(), () -> mounts.create(tenant, agent.getId(),
                    new CapabilityMountRequest(CapabilityType.WORKFLOW, version.definitionId(), published.capabilityVersionId(),
                    null, null, new WorkflowMountConfigRequest(installed.getId().toString(), published.definitionVersionId()),
                    null, 100)));
            assertTrue(mounted.mounts().stream().anyMatch(m -> m.active() && m.handle().startsWith("workflow:")));
            withAuthenticatedMember(member.getId(), () -> { agentManagement.publish(agentId); return null; });
        } finally { TenantContext.clear(); }
    }

    @AfterEach
    void close() {
        if (provider != null) provider.server.stop(0);
        TenantContext.clear();
    }

    @Test
    void workflowHumanInputResumesThroughRealChatAndCompletesSecondModelTurn() throws Exception {
        var login = post("/api/v1/auth/login", Map.of("tenant_slug", slug, "username", "workflow-admin", "password", PASSWORD));
        assertEquals(200, login.statusCode(), login.body());
        token = mapper.readTree(login.body()).path("data").path("access_token").asText();
        assertFalse(token.isBlank());
        var first = post("/api/v1/chat/sync", chat(inputId));
        assertEquals(200, first.statusCode(), first.body());
        await("Workflow human_input and Session suspension", () -> count("agent_runtime_runs", "status = 'SUSPENDED'") == 1
                && count("agent_runtime_executions", "state = 'WAITING_HITL'") == 1
                && count("agent_runtime_session_events", "event_type = 'run.suspended'") == 1);
        var event = rows("select payload_canonical_json from agent_runtime_session_events where tenant_id = ? and run_id = ? "
                + "and event_type = 'run.suspended' order by session_sequence").getLast();
        JsonNode suspended = mapper.readTree(event.get("payload_canonical_json").toString()).path("outcome");
        assertEquals("WORKFLOW_HITL", suspended.path("reason").asText());
        var execution = rows("select execution_id, execution_revision, waiting_suspend_ref, waiting_action_key "
                + "from agent_runtime_executions where tenant_id = ? and run_id = ?").getFirst();
        assertEquals(suspended.path("suspendRef").asText(), execution.get("waiting_suspend_ref"));
        assertEquals(List.of("dispatch"), provider.kinds);
        var suspendedHistory = history();
        var commentary = nativeCommentary(suspendedHistory);
        var ordinary = chat("ordinary-" + inputId);
        ordinary.put("content", List.of(Map.of("type", "text", "text", "ordinary-chat-only")));
        assertEquals(200, post("/api/v1/chat/sync", ordinary).statusCode());
        await("ordinary chat completes while original Workflow waits", () -> queryCount(jdbc, tenantId,
                "select count(*) from agent_runtime_runs where tenant_id=? and run_id=? and status='COMPLETED'",
                tenantId, "run:ordinary-" + inputId) == 1);
        assertEquals(1, count("agent_runtime_runs", "status='SUSPENDED'"));
        var reply = chat("reply-" + inputId);
        reply.put("hitl_response", Map.of("execution_id", "run:" + inputId,
                "suspend_ref", suspended.path("suspendRef").asText(),
                "checkpoint_revision", suspended.path("checkpointRevision").asText(),
                "expected_revision", execution.get("execution_revision").toString(),
                "action", execution.get("waiting_action_key").toString(),
                "response", Map.of("schema_id", "application/json", "canonical_json", "{\"action\":\"confirm\"}")));
        var resumed = post("/api/v1/chat/sync", reply);
        assertEquals(200, resumed.statusCode(), resumed.body());
        // Run 终态 publication 的提交与 SessionLog 投影分属两个阶段，历史回答以已投影的终态事件为准。
        await("Workflow completion, final response and committed Session event", () -> count("agent_runtime_runs", "status = 'COMPLETED'") == 1
                && count("agent_runtime_executions", "state = 'COMPLETED'") == 1 && provider.kinds.size() == 3
                && count("agent_runtime_session_events", "event_type = 'run.resumed'") == 1
                && count("agent_runtime_session_events", "event_type = 'run.completed'") == 1);
        assertEquals(List.of("dispatch", "ordinary", "respond"), provider.kinds);
        assertTrue(provider.sawWorkflowCompletion, "final response must receive the committed Workflow completion observation");
        assertEquals(1, rows("select execution_id from agent_runtime_executions where tenant_id = ? and run_id = ?").size());
        assertEquals(1, rows("select event_id from agent_runtime_session_events where tenant_id = ? and run_id = ? "
                + "and event_type = 'run.resumed'").size());
        var completedHistory = history();
        var finalReply = finalReply(completedHistory);
        assertEquals("final", finalReply.path("metadata").path("phase").asText(), completedHistory.toString());
        assertEquals("工作流已确认完成", finalReply.path("content").asText());
        assertEquals("completed", finalReply.path("state").asText(), completedHistory.toString());
        assertEquals("completed", finalReply.path("metadata").path("state").asText(), completedHistory.toString());
        assertEquals("run:" + inputId, finalReply.path("metadata").path("root_run_id").asText());
        assertEquals(inputId, finalReply.path("metadata").path("root_input_id").asText());
        assertEquals(commentary, nativeCommentary(completedHistory));
        int modelCallsBeforeHistoryReplay = provider.kinds.size();
        long workflowResumesBeforeHistoryReplay = count("agent_runtime_actions", "action_kind = 'workflow.resume'");
        var rebuiltHistory = history();
        assertEquals(completedHistory.path("processes"), rebuiltHistory.path("processes"));
        assertEquals(completedHistory.path("messages"), rebuiltHistory.path("messages"));
        assertEquals(commentary, nativeCommentary(rebuiltHistory));
        assertEquals(finalReply, finalReply(rebuiltHistory));
        assertEquals(modelCallsBeforeHistoryReplay, provider.kinds.size());
        assertEquals(workflowResumesBeforeHistoryReplay, count("agent_runtime_actions", "action_kind = 'workflow.resume'"));
        var replay = post("/api/v1/chat/sync", reply);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(List.of("dispatch", "ordinary", "respond"), provider.kinds);
        assertEquals(1, count("agent_runtime_actions", "action_kind = 'workflow.resume'"));
    }

    private JsonNode history() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/chat/history?session_id=" + sessionId))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body()).path("data");
    }

    /** 核对原MODEL结果里的真实工作流调用；公开过程不暴露原参数或供应商私有正文。 */
    private JsonNode nativeCommentary(JsonNode history) throws Exception {
        assertTrue(java.util.stream.StreamSupport.stream(history.path("processes").spliterator(), false)
                .anyMatch(process -> "workflow".equals(process.path("type").asText())
                        && ("run:" + inputId).equals(process.path("run_id").asText())), history.toString());
        var reference = new RunRef(new TenantId(tenantId), "run:" + inputId);
        var action = executionStore.actions(reference, 0, 100).stream()
                .filter(value -> value.kind() == ExecutionStore.Kind.MODEL).findFirst().orElseThrow();
        var attempt = executionStore.attempt(reference, action.selectedAttempt().orElseThrow()).orElseThrow();
        var material = executionStore.material(reference.tenantId(), attempt.result().orElseThrow()).orElseThrow();
        var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(),
                properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
        var completed = assertInstanceOf(ModelPort.ModelResult.Completed.class, codec.decodeModelOutcome(material.content()));
        assertEquals(ModelPort.TEXT_OUTPUT_SCHEMA, completed.output().schemaId());
        var calls = completed.assistantTurn().orElseThrow().toolCalls();
        assertEquals(1, calls.size());
        var call = calls.getFirst();
        assertEquals("run_workflow", call.wireName());
        var arguments = mapper.readTree(call.argumentsJson());
        assertEquals("正在发起工作流确认", arguments.path("commentary").asText());
        return mapper.valueToTree(Map.of("callId", call.callId(), "wireName", call.wireName(), "arguments", arguments));
    }

    private JsonNode finalReply(JsonNode history) {
        var matches = new ArrayList<JsonNode>();
        for (JsonNode message : history.path("messages")) {
            if ("assistant".equals(message.path("role").asText())
                    && ("run:" + inputId).equals(message.path("metadata").path("run_id").asText())
                    && "final".equals(message.path("metadata").path("phase").asText())) matches.add(message);
        }
        assertEquals(1, matches.size(), history.toString());
        return matches.getFirst();
    }

    private Map<String, Object> chat(String id) {
        var body = new LinkedHashMap<String, Object>();
        body.put("content", List.of(Map.of("type", "text", "text", "请执行工作流并等待我的确认")));
        body.put("session_id", sessionId);
        body.put("agent_id", agentId); body.put("client_message_id", id);
        body.put("context", Map.of("end_user_id", userId));
        return body;
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long count(String table, String predicate) {
        return queryCount(jdbc, tenantId, "select count(*) from " + table + " where tenant_id = ? and run_id = ? and " + predicate,
                tenantId, "run:" + inputId);
    }

    private List<Map<String, Object>> rows(String sql) {
        return queryRows(jdbc, tenantId, sql, tenantId, "run:" + inputId);
    }

    private void await(String label, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail(label + "; models=" + provider.kinds + "; runs=" + rows("select status, run_revision from agent_runtime_runs where tenant_id = ? and run_id = ?")
                + "; executions=" + rows("select state, reason_code from agent_runtime_executions where tenant_id = ? and run_id = ?")
                + "; actions=" + rows("select action_kind, state, reason_code from agent_runtime_actions where tenant_id = ? and run_id = ?")
                + "; inbox=" + queryRows(jdbc, tenantId,
                        "select input_id,status,reason_code,attempts from agent_runtime_session_inbox where tenant_id = ?", tenantId));
    }

    private static Map<String, Object> definition() {
        return Map.of("version", "1.0", "entry", "start", "meta", Map.of("display_name", "Workflow HITL E2E",
                "capability", Map.of("model_invocable", true, "user_invocable", true, "input_schema", Map.of(),
                        "output_schema", Map.of(), "supports_hitl", true, "export_fields", List.of("messages"),
                        "timeout_ms", 300_000, "concurrency_safe", true)),
                "nodes", List.of(Map.of("id", "start", "type", "start", "name", "开始"),
                        Map.of("id", "ask", "type", "human_input", "name", "确认", "prompt", "是否继续？",
                                "result_to", "extracted", "timeout_seconds", 300,
                                "card", Map.of("card_id", "workflow_confirm", "type", "confirm", "title", "确认",
                                        "body", "请确认", "actions", List.of(Map.of("id", "confirm", "label", "确认", "style", "primary")))),
                        Map.of("id", "done", "type", "output", "name", "完成", "message", "workflow-fixture-completed"),
                        Map.of("id", "end", "type", "end", "name", "结束")),
                "edges", List.of(Map.of("from", "start", "to", "ask"), Map.of("from", "ask", "to", "done"),
                        Map.of("from", "done", "to", "end")));
    }

    /** 独立 HTTP 模型只按真实注入的能力与完成 Observation 作答，不读取数据库或操纵 Runtime。 */
    private static final class WorkflowModel {
        private final ObjectMapper mapper;
        private final HttpServer server;
        private final CopyOnWriteArrayList<String> kinds = new CopyOnWriteArrayList<>();
        private volatile boolean sawWorkflowCompletion;
        private WorkflowModel(ObjectMapper mapper) throws IOException {
            this.mapper = mapper;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::respond);
        }
        private void respond(HttpExchange exchange) throws IOException {
            JsonNode request;
            try (var input = exchange.getRequestBody()) { request = mapper.readTree(input); }
            String messages = request.path("messages").toString();
            boolean completed = false;
            for (JsonNode message : request.path("messages")) {
                String content = message.path("content").asText();
                if ("tool".equals(message.path("role").asText()) && content.contains("workflow-fixture-completed")) completed = true;
            }
            Map<String, Object> assistant;
            String finish = "stop";
            if (completed) {
                sawWorkflowCompletion = true;
                kinds.add("respond");
                assistant = Map.of("role", "assistant", "content", "工作流已确认完成");
            } else if (messages.contains("ordinary-chat-only")) {
                kinds.add("ordinary");
                assistant = Map.of("role", "assistant", "content", "你好");
            } else {
                var handle = Pattern.compile("workflow:[0-9]+").matcher(messages);
                if (!handle.find()) throw new IOException("published workflow handle missing from model context");
                kinds.add("dispatch");
                var decision = Map.of("handle", handle.group(), "commentary", "正在发起工作流确认");
                String wire = null;
                for (var tool : request.path("tools")) {
                    if (tool.path("function").path("name").asText().equals("run_workflow")) wire = "run_workflow";
                }
                if (wire == null) throw new IOException("native run_workflow tool missing");
                assistant = Map.of("role", "assistant", "content", "正在发起工作流确认", "tool_calls", List.of(Map.of(
                        "id", "workflow-fixture-call", "type", "function", "function", Map.of("name", wire,
                                "arguments", mapper.writeValueAsString(decision)))));
                finish = "tool_calls";
            }
            if (request.path("stream").asBoolean()) {
                var delta = new LinkedHashMap<>(assistant);
                if (assistant.containsKey("tool_calls")) {
                    var call = new LinkedHashMap<>((Map<String, Object>) ((List<?>) assistant.get("tool_calls")).getFirst());
                    call.put("index", 0);
                    delta.put("tool_calls", List.of(call));
                }
                String first = mapper.writeValueAsString(Map.of("id", "workflow-fixture", "object", "chat.completion.chunk",
                        "model", "workflow-fixture", "choices", List.of(Map.of("index", 0, "delta", delta))));
                String last = mapper.writeValueAsString(Map.of("id", "workflow-fixture", "object", "chat.completion.chunk",
                        "model", "workflow-fixture", "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)),
                        "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18)));
                byte[] events = ("data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, events.length);
                try (var output = exchange.getResponseBody()) { output.write(events); }
                return;
            }
            byte[] result = mapper.writeValueAsBytes(Map.of("id", "workflow-fixture", "object", "chat.completion",
                    "model", "workflow-fixture", "choices", List.of(Map.of("index", 0, "finish_reason", finish,
                            "message", assistant)),
                    "usage", Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            try (var output = exchange.getResponseBody()) { output.write(result); }
        }
    }
}
