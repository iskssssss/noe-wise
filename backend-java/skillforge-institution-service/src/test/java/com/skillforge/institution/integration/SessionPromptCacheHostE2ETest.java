package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.dto.request.ToolMountConfigRequest;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.service.CapabilityMountManagementService;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 Spring Chat 与宿主装配的 HTTP 录制验收；仅外部模型响应由 loopback 夹具决定。 */
@Import(DeterministicModelCatalogConfiguration.class)
class SessionPromptCacheHostE2ETest extends AbstractIntegrationTest {
    private static final String PASSWORD = "PrefixFixture123";
    /** Spring 创建诊断 Sink 前冻结本用例的隔离租户和八个 Run，不能启用全租户或永久采集。 */
    private static final String CAPTURE_TENANT_ID = Long.toString(SnowflakeIds.nextId());
    private static final List<String> CAPTURE_INPUT_IDS = java.util.stream.IntStream.range(0, 8)
            .mapToObj(ignored -> "prefix-input-" + SnowflakeIds.nextId()).toList();
    private static final Instant CAPTURE_UNTIL = Instant.now().plus(Duration.ofMinutes(15));
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private TenantMemberMapper members;
    @Autowired private EndUserMapper users;
    @Autowired private AgentMapper agents;
    @Autowired private SessionMapper sessions;
    @Autowired private PasswordEncoder passwords;
    @Autowired private PlatformModelCatalogService modelCatalog;
    @Autowired private AgentRuntimeHostProvisioner provisioner;
    @Autowired private AgentPublicationService publications;
    @Autowired private CapabilityCatalogService capabilities;
    @Autowired private CapabilityMountManagementService mounts;
    @Autowired private CryptoService crypto;
    @Autowired private ApplicationContext application;
    @Autowired private AgentRuntimeProperties properties;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private RecordingModel model;

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
        registry.add("skillforge.agent-runtime.diagnostic-capture.enabled", () -> "true");
        registry.add("skillforge.agent-runtime.diagnostic-capture.tenant-id", () -> CAPTURE_TENANT_ID);
        registry.add("skillforge.agent-runtime.diagnostic-capture.until", () -> CAPTURE_UNTIL.toString());
        for (int index = 0; index < CAPTURE_INPUT_IDS.size(); index++) {
            String runId = "run:" + CAPTURE_INPUT_IDS.get(index);
            registry.add("skillforge.agent-runtime.diagnostic-capture.run-ids[" + index + "]", () -> runId);
        }
    }

    /** 模型监听使用系统分配 loopback 端口，失败也由 AfterEach 收口。 */
    @BeforeEach void resources() throws IOException {
        model = new RecordingModel(json);
        model.server.start();
        requireRunningRuntimeWorkers(application, properties);
    }

    @AfterEach void close() {
        if (model != null) model.server.stop(0);
        TenantContext.clear();
    }

    /** 两个工具收尾 Run 与六个直接回复 Run；不重写实际宿主提示词或工具 Schema。 */
    @Test void eightRunsRecordTenCallsWithStableHostInstructionsAndAuthorizedToolNames() throws Exception {
        var owner = createOwner("primary");
        var inputs = new ArrayList<String>();
        var firstRequests = new ArrayList<JsonNode>();
        var payloads = new ArrayList<Map<String, Object>>();
        var prefixTransitions = new ArrayList<Map<String, Object>>();
        JsonNode previousFinalRequest = null;
        String stableSystem = null;
        JsonNode stableTools = null;
        for (int index = 0; index < 8; index++) {
            String input = CAPTURE_INPUT_IDS.get(index);
            inputs.add(input);
            int offset = model.records.size();
            model.arm(input, index < 2);
            var payload = chat(owner, input);
            payloads.add(payload);
            var response = post("/api/v1/chat/sync", payload, owner.token());
            assertEquals(200, response.statusCode(), response.body());
            awaitCompleted(owner, input);
            var calls = List.copyOf(model.records.subList(offset, model.records.size()));
            assertEquals(index < 2 ? 2 : 1, calls.size(), model.kinds.toString());
            var first = calls.getFirst();
            firstRequests.add(first);
            String system = first.path("messages").get(0).path("content").asText();
            assertEquals("system", first.path("messages").get(0).path("role").asText());
            assertFalse(system.contains(input), "当前输入身份不能进入固定 system");
            if (stableSystem == null) {
                stableSystem = system;
                stableTools = first.path("tools").deepCopy();
            } else {
                assertEquals(stableSystem, system, "同一冻结配置的跨 Run 固定 system 必须相同");
                assertEquals(stableTools, first.path("tools"), "跨 Run 精确 target 变化不能改变 wire name 或 Schema");
            }
            assertCurrentInputIsTrustedTail(first, input);
            if (previousFinalRequest != null) {
                prefixTransitions.add(assertMessagesAppend(previousFinalRequest, first, "run-" + index + "-inheritance"));
                assertInheritedFinalReply(first, inputs.get(index - 1), input);
            }
            for (var call : calls) assertEquals(stableSystem,
                    call.path("messages").get(0).path("content").asText(), "收尾阶段规则必须后置");
            if (index < 2) {
                prefixTransitions.add(assertMessagesAppend(calls.get(0), calls.get(1), "run-" + index + "-tool-result"));
                assertEquals(stableTools, calls.get(1).path("tools"), "工具结果之后继续使用同一冻结的独立工具 Schema");
            }
            previousFinalRequest = calls.getLast();
        }
        assertEquals(10, model.records.size());
        assertEquals(9, prefixTransitions.size(), "覆盖七个跨 Run 和两个能力迭代的完整 messages 前缀");
        assertTrue(model.failures.isEmpty(), model.failures.toString());
        assertEquals(List.of("tool", "final", "tool", "final",
                "direct", "direct", "direct", "direct", "direct", "direct"), model.kinds);
        var prefixDiagnostics = assertPersistedPrefixDiagnostics(owner, inputs);

        int beforeReplay = model.records.size();
        assertEquals(200, post("/api/v1/chat/sync", payloads.getFirst(), owner.token()).statusCode());
        assertEquals(beforeReplay, model.records.size(), "已提交输入重放不能再次发送模型 HTTP");

        var other = createOwner("other-tenant");
        String otherInput = "other-prefix-input-" + SnowflakeIds.nextId();
        model.arm(otherInput, false);
        assertEquals(200, post("/api/v1/chat/sync", chat(other, otherInput), other.token()).statusCode());
        awaitCompleted(other, otherInput);
        String otherBody = model.records.getLast().toString();
        for (String input : inputs) assertFalse(otherBody.contains(input), "异租户不得采用其他会话历史");
        assertEquals(0, queryCount(jdbc, other.tenant(),
                "select count(*) from agent_runtime_runs where tenant_id=? and session_id=?",
                other.tenant(), owner.session()));

        var report = Path.of("build", "reports", "session-prompt-cache-host", owner.session() + ".json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "mainRuns", 8, "mainCalls", 10, "providerBoundary", "loopback; no paid supplier cache claims",
                "prefixTransitions", prefixTransitions, "firstRequests", firstRequests,
                "prefixDiagnostics", prefixDiagnostics, "requests", model.records, "responseKinds", model.kinds)), StandardCharsets.UTF_8);
    }

    /** 真实诊断旁路必须落入原 SessionLog；只读摘要与计数，不保存另一份提示词。 */
    private List<Map<String, Object>> assertPersistedPrefixDiagnostics(Owner owner, List<String> inputs) throws Exception {
        List<Map<String, Object>> rows;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            rows = queryRows(jdbc, owner.tenant(), "select run_id,payload_schema_id,payload_canonical_json "
                    + "from agent_runtime_session_events where tenant_id=? and session_id=? and agent_id=? "
                    + "and event_type='agent.runtime-diagnostic' and payload_canonical_json::jsonb->>'kind'='PROMPT_PREFIX' "
                    + "order by session_sequence", owner.tenant(), owner.session(), owner.agent());
            if (rows.size() >= 10) break;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertEquals(10, rows.size(), "10 次真实主模型准备都应持久记录 PROMPT_PREFIX，诊断失败不能由 unit 通过掩盖");
        var result = new ArrayList<Map<String, Object>>();
        var operations = new HashSet<String>();
        var runs = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            assertEquals("skillforge.runtime.agent.runtime-diagnostic.v1", row.get("payload_schema_id"));
            var payload = json.readTree(row.get("payload_canonical_json").toString());
            assertEquals("PROMPT_PREFIX", payload.path("kind").asText());
            assertFalse(payload.path("operationKey").asText().isBlank());
            assertTrue(operations.add(payload.path("operationKey").asText()), "每次准备只持久记录一个独立诊断操作");
            var metrics = payload.path("metrics");
            var fields = new HashSet<String>(); metrics.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("systemHash", "toolsHash", "generation", "resetReason", "commonMessages",
                    "commonMessageBytes", "measurementKind"), fields, "持久诊断只含摘要、原因与计数");
            for (String hash : List.of("systemHash", "toolsHash", "generation"))
                assertTrue(metrics.path(hash).asText().matches("[0-9a-f]{64}"), hash + " 应为安全 SHA-256 摘要");
            assertEquals("CANONICAL_MESSAGES_NOT_PROVIDER_TOKENS", metrics.path("measurementKind").asText());
            if (index == 0) {
                assertEquals("BASELINE", metrics.path("resetReason").asText());
                assertEquals(0, metrics.path("commonMessages").asInt(-1));
                assertEquals(0, metrics.path("commonMessageBytes").asInt(-1));
            } else {
                assertNotEquals("BASELINE", metrics.path("resetReason").asText());
                assertTrue(metrics.path("commonMessages").asInt(-1) > 0, "后续准备应报告实际继承的消息前缀");
                assertTrue(metrics.path("commonMessageBytes").asInt(-1) > 0);
            }
            String run = row.get("run_id").toString(); runs.merge(run, 1, Integer::sum);
            result.add(Map.of("runId", run, "metrics", metrics.deepCopy()));
        }
        assertEquals(8, runs.size());
        for (int index = 0; index < inputs.size(); index++)
            assertEquals(Integer.valueOf(index < 2 ? 2 : 1), runs.get("run:" + inputs.get(index)));
        return result;
    }

    /** 检查完整 wire 消息树，包括角色、媒体、调用参数、callId 与 reasoning；tools 清空不放宽消息断言。 */
    private static Map<String, Object> assertMessagesAppend(JsonNode prior, JsonNode next, String boundary) {
        var previous = prior.path("messages");
        var current = next.path("messages");
        assertTrue(previous.isArray() && current.isArray(), boundary);
        assertTrue(current.size() > previous.size(), boundary + " 必须在完整原前缀之后增加本轮事实");
        for (int index = 0; index < previous.size(); index++)
            assertEquals(previous.get(index), current.get(index), boundary + " 第 " + index + " 条 wire 消息不得重排或改写");
        return Map.of("boundary", boundary, "previousMessages", previous.size(),
                "currentMessages", current.size(), "identicalPrefixMessages", previous.size());
    }

    /** 新 Run 必须继承真实已采用的最终正文，位于新的用户输入之前且不重复。 */
    private static void assertInheritedFinalReply(JsonNode request, String previousInput, String currentInput) {
        int reply = -1;
        int matches = 0;
        int current = -1;
        for (int index = 0; index < request.path("messages").size(); index++) {
            var message = request.path("messages").get(index);
            if ("assistant".equals(message.path("role").asText())
                    && message.path("content").asText().equals("本轮确认完成 " + previousInput)) {
                reply = index; matches++;
            }
            if ("user".equals(message.path("role").asText())
                    && message.path("content").asText().equals("请给出本轮确认 " + currentInput)) current = index;
        }
        assertEquals(1, matches, "上一 Run 的最终回复必须仅采用一次");
        assertTrue(reply > 0 && current > reply, "已采用正文在新的用户输入之前连续继承");
    }

    /** 保留真实输入 UUID 所在可信消息与位置，不用测试模板替代出站正文。 */
    private static void assertCurrentInputIsTrustedTail(JsonNode request, String input) {
        int current = -1;
        int userInput = -1;
        for (int index = 0; index < request.path("messages").size(); index++) {
            var message = request.path("messages").get(index);
            if ("user".equals(message.path("role").asText())
                    && message.path("content").asText().contains("请给出本轮确认 " + input)) userInput = index;
            if (!message.path("content").asText().contains("当前 Run 输出上下文")) continue;
            if (!message.path("content").asText().contains(input)) continue;
            assertEquals("system", message.path("role").asText());
            current = index;
        }
        assertTrue(userInput > 0, "真实用户输入必须出现在冻结请求中");
        assertTrue(current > userInput, "当前输入 ID 必须由本轮输入之后的可信宿主事实提供");
    }

    /** 只在 Testcontainers 私有库构造真实成员、发布 Agent、正式挂载与会话，不改开发库。 */
    private Owner createOwner(String suffix) throws Exception {
        String slug = "prefix-" + suffix + "-" + SnowflakeIds.nextId();
        String tenantId = "primary".equals(suffix) ? createCapturedTenant(slug) : createTenant(tenants, plans, slug);
        String sessionId = "prefix-session-" + SnowflakeIds.nextId();
        String agentId;
        long userId;
        TenantContext.setTenantId(tenantId);
        try {
            long tenant = Long.parseLong(tenantId);
            PlatformModelFixtures.selectLlm(modelCatalog, tenantId, model.endpoint(),
                    "prefix-fixture", "prefix-fixture-key", "custom", "custom-openai-compatible", "custom-v1");
            provisioner.provision(tenantId);
            var member = new TenantMemberDO();
            member.setId(SnowflakeIds.nextId()); member.setTenantId(tenant); member.setUsername("prefix-admin");
            member.setRole("admin"); member.setPermissions(List.of()); member.setStatus("active");
            member.setPasswordHash(passwords.encode(PASSWORD)); member.setCreatedAt(Instant.now()); members.insert(member);
            initializeTenantAdministrator(tenantId, member.getId());
            var user = new EndUserDO(); userId = SnowflakeIds.nextId();
            user.setId(userId); user.setTenantId(tenant); user.setDisplayName("Prefix fixture"); user.setStatus("active");
            user.setFirstSeenAt(Instant.now()); user.setLastActiveAt(Instant.now()); user.setCreatedAt(Instant.now()); users.insert(user);
            var agent = new AgentDO(); agent.setId(SnowflakeIds.nextId()); agentId = agent.getId().toString();
            agent.setTenantId(tenant); agent.setAgentKey("prefix-agent-" + agentId); agent.setName("Prefix fixture");
            agent.setSystemPrompt("根据真实能力结果回答当前问题。"); agent.setRoleDescription("前缀连续性验收助手");
            agent.setConfig(Map.of()); agent.setStatus("draft"); agent.setCreatedAt(Instant.now()); agent.setUpdatedAt(Instant.now());
            agents.insert(agent);
            withAuthenticatedMember(member.getId(), () -> { mountGoal(tenant, agent.getId()); return null; });
            publications.publish(agent);
            var session = new SessionDO(); session.setId(SnowflakeIds.nextId()); session.setSessionId(sessionId);
            session.setTenantId(tenant); session.setEndUserId(userId); session.setAgentId(agent.getId());
            session.setMessageCount(0); session.setLastEventId(0L); session.setMetadata(Map.of());
            session.setCreatedAt(Instant.now()); session.setUpdatedAt(Instant.now()); sessions.insert(session);
        } finally { TenantContext.clear(); }
        var response = post("/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "prefix-admin", "password", PASSWORD), null);
        assertEquals(200, response.statusCode(), response.body());
        String token = json.readTree(response.body()).path("data").path("access_token").asText();
        assertFalse(token.isBlank());
        return new Owner(tenantId, sessionId, agentId, userId, token);
    }

    /** 使用已冻结采集身份创建真实隔离租户，其套餐与资源仍按正式 Mapper 和后续宿主入口建立。 */
    private String createCapturedTenant(String slug) {
        var plan = ensureFreePlan(plans);
        var tenant = new TenantDO();
        tenant.setId(Long.parseLong(CAPTURE_TENANT_ID));
        tenant.setName("前缀诊断采集测试租户");
        tenant.setSlug(slug);
        tenant.setPlanId(plan.getId());
        tenant.setStatus("active");
        tenant.setDeploymentMode("cloud");
        tenant.setSettings(Map.of());
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        tenants.insert(tenant);
        return CAPTURE_TENANT_ID;
    }

    /** 使用已发布平台 Tool 的真实 Deployment 与挂载，不把工具访问权塞进提示词。 */
    private void mountGoal(long tenant, long agent) {
        var scope = CapabilityCatalogScope.tenant(tenant);
        var definition = capabilities.listDefinitions(scope, CapabilityType.TOOL, "PUBLISHED").stream()
                .filter(value -> "tool:get_goal".equals(value.capabilityKey())).findFirst().orElseThrow();
        String versionKey = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(value -> "get_goal".equals(value.name()))
                .findFirst().orElseThrow().publishedVersionKey();
        var version = capabilities.listVersions(scope, definition.id(), "PUBLISHED").stream()
                .filter(value -> versionKey.equals(value.versionKey())).findFirst().orElseThrow();
        var deployment = capabilities.createDeployment(scope, "prefix-goal-" + agent, version.id(),
                ToolDeployment.ProviderType.PLATFORM, "get_goal", ToolDeployment.Environment.TEST, "ACTIVE");
        mounts.create(tenant, agent, new CapabilityMountRequest(CapabilityType.TOOL, definition.id(), version.id(),
                null, new ToolMountConfigRequest(deployment.deploymentKey(), deployment.revision()), null, null, 100));
    }

    private static Map<String, Object> chat(Owner owner, String input) {
        var body = new LinkedHashMap<String, Object>();
        body.put("content", List.of(Map.of("type", "text", "text", "请给出本轮确认 " + input)));
        body.put("session_id", owner.session()); body.put("agent_id", owner.agent());
        body.put("client_message_id", input); body.put("context", Map.of("end_user_id", owner.user()));
        return body;
    }

    /** HTTP 鉴权与正式渠道由真实 Spring 处理；异常保留响应供定位。 */
    private HttpResponse<String> post(String path, Map<String, Object> body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 同时等 Run 和 SessionLog 终态，保证下一 Run 读取正式采用事实。 */
    private void awaitCompleted(Owner owner, String input) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (queryCount(jdbc, owner.tenant(), "select count(*) from agent_runtime_runs where tenant_id=? "
                    + "and run_id=? and status='COMPLETED'", owner.tenant(), "run:" + input) == 1
                    && queryCount(jdbc, owner.tenant(), "select count(*) from agent_runtime_session_events where tenant_id=? "
                    + "and run_id=? and event_type='run.completed'", owner.tenant(), "run:" + input) == 1) return;
            Thread.sleep(100);
        }
        fail("Run 未完成；响应种类=" + model.kinds + ";模型失败=" + model.failures + ";事实=" + queryRows(jdbc, owner.tenant(),
                "select status from agent_runtime_runs where tenant_id=? and run_id=?", owner.tenant(), "run:" + input));
    }

    private record Owner(String tenant, String session, String agent, long user, String token) { }

    /** 模型只读实际 HTTP 参数，按本轮阶段返回合法决策；不会读取数据库或生成正确提示词。 */
    private static final class RecordingModel {
        private final ObjectMapper json;
        private final HttpServer server;
        private final CopyOnWriteArrayList<JsonNode> records = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> kinds = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> failures = new CopyOnWriteArrayList<>();
        private volatile Scenario scenario;
        private record Scenario(String input, boolean tools, AtomicInteger step) { }

        RecordingModel(ObjectMapper json) throws IOException {
            this.json = json;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", this::respond);
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void arm(String input, boolean tools) { scenario = new Scenario(input, tools, new AtomicInteger()); }

        /** 决策使用真实声明的 wire name；原始出站 body 只保留本测试合成数据。 */
        private void respond(HttpExchange exchange) throws IOException {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                records.add(request);
                var current = scenario;
                if (current == null) throw new IOException("model_fixture_not_armed");
                int step = current.step().incrementAndGet();
                var message = new LinkedHashMap<String, Object>(); message.put("role", "assistant");
                String finish = "stop";
                if (current.tools() && step == 1) {
                    String wireName = null;
                    for (var tool : request.path("tools")) {
                        if (tool.path("function").path("description").asText().contains("读取用户显式创建的持续 Goal"))
                            wireName = tool.path("function").path("name").asText();
                    }
                    if (wireName == null) throw new IOException("authorized_get_goal_missing");
                    kinds.add("tool"); finish = "tool_calls";
                    message.put("tool_calls", call(current.input(), wireName,
                            Map.of("commentary", "读取当前目标", "arguments", Map.of())));
                } else {
                    kinds.add(current.tools() ? "final" : "direct");
                    finish = "stop";
                    message.put("content", "本轮确认完成 " + current.input());
                }
                String modelId = request.path("model").asText();
                var usage = Map.of("prompt_tokens", 100, "completion_tokens", 5, "total_tokens", 105,
                        "prompt_cache_hit_tokens", 40, "prompt_cache_miss_tokens", 60);
                String body;
                String contentType;
                if (request.path("stream").asBoolean()) {
                    if (message.get("tool_calls") instanceof List<?> tools) {
                        var indexed = new ArrayList<Map<String, Object>>();
                        for (Object raw : tools) {
                            var item = new LinkedHashMap<String, Object>();
                            ((Map<?, ?>) raw).forEach((key, value) -> item.put(key.toString(), value));
                            item.put("index", 0); indexed.add(item);
                        }
                        message.put("tool_calls", indexed);
                    }
                    String first = json.writeValueAsString(Map.of("id", "prefix-response-" + current.input(), "model", modelId,
                            "object", "chat.completion.chunk", "choices", List.of(Map.of("index", 0, "delta", message))));
                    String last = json.writeValueAsString(Map.of("id", "prefix-response-" + current.input(), "model", modelId,
                            "object", "chat.completion.chunk", "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)),
                            "usage", usage));
                    body = "data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n";
                    contentType = "text/event-stream";
                } else {
                    body = json.writeValueAsString(Map.of("id", "prefix-response-" + current.input(), "model", modelId,
                            "object", "chat.completion", "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)),
                            "usage", usage));
                    contentType = "application/json";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (IOException | RuntimeException failure) {
                failures.add(failure.getClass().getSimpleName() + ":" + failure.getMessage());
                byte[] bytes = failure.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        }

        private List<Map<String, Object>> call(String id, String name, Map<String, Object> arguments) throws IOException {
            return List.of(Map.of("id", "prefix-call-" + id, "type", "function",
                    "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments))));
        }
    }
}
