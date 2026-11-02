package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.access.AccessCommands;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.memory.MemoryGovernanceService;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** 隔离 PostgreSQL/Redis 和真实治理 HTTP 的 MemoryPage 浏览器验收，不调用模型。 */
@Tag("memory-browser")
@TestPropertySource(properties = "agent-runtime.workers.auto-startup=false")
class MemoryGovernanceBrowserAcceptance extends AbstractIntegrationTest {
    /** 只用于独立合成验收账号，禁止引用本地真实成员凭据。 */
    private static final String PASSWORD = "MemoryBrowser123";
    private static MemoryBrowserServer frontend;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired TenantMemberMapper members;
    @Autowired AgentMapper agents;
    @Autowired PasswordEncoder passwords;
    @Autowired FoundationAccess access;
    @Autowired FoundationOrganizationService organization;
    @Autowired EndUserService endUsers;
    @Autowired SessionLogPort sessionLog;
    @Autowired MemoryRuntime runtime;
    @Autowired MemoryGovernanceService governance;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    /** 服务实际监听前端 port 0 后，将精确来源交给正式 CORS 配置。 */
    @DynamicPropertySource
    static synchronized void isolatedBrowserOrigin(DynamicPropertyRegistry registry) {
        if (frontend == null) frontend = MemoryBrowserServer.start();
        registry.add("skillforge.web.allowed-origins", frontend::baseUrl);
    }

    /** Spring 初始化或断言失败仍由 JVM 钩子兜底；正常路径立即等待 Vite 静止。 */
    @AfterAll
    static void closeFrontend() {
        if (frontend != null) frontend.close();
    }

    /** 真实登录、浏览器交互、API 回执与持久事实分别形成证据。 */
    @Test
    void memoryGovernanceThroughRealBrowserAndAuthenticatedApi() throws Exception {
        String run = Long.toString(SnowflakeIds.nextId());
        String slug = "memory-browser-" + run;
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        Throwable primary = null;
        try {
            long administrator = createAdministrator(tenant, "memory-browser-admin");
            var subject = new FoundationAccess.Subject(false, tenant, Long.toString(administrator));
            var fixture = access.withSubject(subject, () -> createFixture(tenant, run));
            Path evidence = frontend.evidence();
            Files.writeString(evidence.resolve("fixture.json"), json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(fixture), StandardCharsets.UTF_8);
            frontend.bindBackend("http://127.0.0.1:" + port);
            runPlaywright(slug, fixture, evidence);
            verifyPersistedFacts(tenant, fixture, evidence);
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            // 失败同样读取本租户实际当前事实，不将截图或HTTP成功当作落库证明。
            try {
                Files.writeString(frontend.evidence().resolve("database-current-facts.json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(governance.list(tenant, null, 100)),
                        StandardCharsets.UTF_8);
                captureDatabaseResources(tenant, frontend.evidence(), primary == null);
            } catch (Exception | Error evidenceFailure) {
                if (primary != null) primary.addSuppressed(evidenceFailure); else throw evidenceFailure;
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 即使浏览器违反契约也保存本租户的实际调用计数；零调用断言不得被前置失败跳过。 */
    private void captureDatabaseResources(String tenant, Path evidence, boolean browserPassed) throws Exception {
        long modelOperations = queryCount(jdbc, tenant,
                "select count(*) from nw_runtime_session_model_operations where tenant_id=?", tenant);
        long externalInvocations = queryCount(jdbc, tenant,
                "select count(*) from nw_external_invocations where tenant_id=?", Long.valueOf(tenant));
        long extractionJobs = queryCount(jdbc, tenant,
                "select count(*) from agent_runtime_memory_extraction_jobs where tenant_id=?", tenant);
        Files.writeString(evidence.resolve("database-resource-receipt.json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("browserPassed", browserPassed,
                        "modelOperations", modelOperations, "externalInvocations", externalInvocations,
                        "extractionJobs", extractionJobs)), StandardCharsets.UTF_8);
        assertEquals(0, modelOperations, "治理浏览器验收不得创建模型准备或调用");
        assertEquals(0, externalInvocations, "治理浏览器验收不得创建外部供应商调用");
        assertEquals(0, extractionJobs, "预置来源助手关闭自动提取，不得触发提取模型");
    }

    /** 建立正式管理员建制，登录在浏览器中通过真实密码及安全会话入口进行。 */
    private long createAdministrator(String tenant, String username) {
        var member = new TenantMemberDO();
        member.setId(SnowflakeIds.nextId());
        member.setTenantId(Long.valueOf(tenant));
        member.setUsername(username);
        member.setRole("admin");
        member.setPermissions(List.of());
        member.setStatus("active");
        member.setPasswordHash(passwords.encode(PASSWORD));
        member.setCreatedAt(Instant.now());
        members.insert(member);
        organization.initializeTenant(tenant, member.getId().toString());
        return member.getId();
    }

    /** 来源通过 SessionLog 提交，所有记忆由生产 MemoryRuntime 创建，治理 API 不使用替身。 */
    private Map<String, Object> createFixture(String tenant, String run) {
        String agentA = createAgent(tenant, "记忆验收助手 A " + run, "a-" + run);
        String agentB = createAgent(tenant, "记忆验收助手 B " + run, "b-" + run);
        var userA = endUsers.create(tenant, "记忆验收用户 A " + run);
        var userB = endUsers.create(tenant, "记忆验收用户 B " + run);
        var ownerA = owner(tenant, agentA, userA.getId(), "memory-browser-a-" + run);
        var ownerAgentB = owner(tenant, agentB, userA.getId(), "memory-browser-agent-b-" + run);
        var ownerUserB = owner(tenant, agentA, userB.getId(), "memory-browser-user-b-" + run);
        Map<String, MemoryFact> facts = new LinkedHashMap<>();
        facts.put("session", createFact(ownerA, MemoryScope.SESSION, "session-" + run, false));
        facts.put("main", createFact(ownerA, MemoryScope.USER_AGENT, "main-" + run, false));
        facts.put("user", createFact(ownerA, MemoryScope.USER, "user-" + run, false));
        facts.put("agentDistractor", createFact(ownerAgentB, MemoryScope.USER_AGENT, "agent-distractor-" + run, false));
        facts.put("userDistractor", createFact(ownerUserB, MemoryScope.USER_AGENT, "user-distractor-" + run, false));
        facts.put("approve", createFact(ownerA, MemoryScope.USER_AGENT, "approve-" + run, true));
        facts.put("reject", createFact(ownerA, MemoryScope.USER_AGENT, "reject-" + run, true));
        facts.put("conflict", createFact(ownerA, MemoryScope.USER_AGENT, "conflict-" + run, false));
        var role = organization.createRole(new AccessCommands.RoleCreate("memory_reader", "记忆只读验收角色", "all",
                List.of("foundation.access.read", "foundation.memory.read", "foundation.agents.read",
                        "foundation.resource.agent.read", "foundation.sessions.read"), List.of()));
        organization.createMember(new AccessCommands.MemberCreate("memory-browser-reader", "记忆只读验收成员", null,
                PASSWORD, null, null, List.of(role.id())));
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("runId", run);
        fixture.put("tenantId", tenant);
        fixture.put("agentA", agentA);
        fixture.put("agentNameA", "记忆验收助手 A " + run);
        fixture.put("userA", userA.getId().toString());
        fixture.put("userNameA", "记忆验收用户 A " + run);
        fixture.put("facts", facts);
        return fixture;
    }

    /** 合成助手只提供合法来源归属，不开启自动提取或模型执行。 */
    private String createAgent(String tenant, String name, String key) {
        var agent = new AgentDO();
        agent.setId(SnowflakeIds.nextId());
        agent.setTenantId(Long.valueOf(tenant));
        agent.setAgentKey("memory-browser-" + key);
        agent.setName(name);
        agent.setStatus("draft");
        agent.setSystemPrompt("记忆治理浏览器验收合成助手，不调用模型");
        agent.setConfig(Map.of("memory", Map.of("automatic_extraction", false)));
        agents.insert(agent);
        organization.registerResource("agent", agent.getId().toString());
        return agent.getId().toString();
    }

    /** 创建作用域封闭的会话所有者，供正式 SessionLog 校验。 */
    private static SessionOwner owner(String tenant, String agent, long user, String session) {
        return SessionOwner.user(new SessionRef(new TenantId(tenant), session), new AgentId(agent), new EndUserId(user));
    }

    /** 写入一条合法来源后提交 Runtime Mutation；只有完整 Recorded 回执才进入浏览器夹具。 */
    private MemoryFact createFact(SessionOwner owner, MemoryScope scope, String predicate, boolean review) {
        String eventId = "memory-browser-source-" + predicate;
        InputId input = new InputId("memory-browser-input-" + predicate);
        Instant occurredAt = Instant.now();
        var trace = new TraceEnvelope("memory-browser-trace-" + predicate, "memory-browser-span-" + predicate,
                input, Optional.empty());
        var target = new SessionProjectionTarget(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var source = new SessionEventDraft(new EventId(eventId), "inbound.message", "v1", eventId,
                trace, new TypedPayload("input.v1", "{\"extraParameters\":{},\"text\":\"合成记忆治理样本\"}"), occurredAt);
        assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                sessionLog.append(new SessionLogPort.AppendSessionEvent(target, source)));
        var draft = new MemoryFactDraft(
                switch (scope) { case SESSION -> new MemoryOwner.Session(owner.sessionRef()); case USER -> new MemoryOwner.User(owner.sessionRef().tenantId(), owner.requireUser()); case USER_AGENT -> new MemoryOwner.UserAgent(owner.sessionRef().tenantId(), owner.requireUser(), owner.target().requireAgent()); default -> throw new IllegalArgumentException("personal fixture scope"); },
                new MemoryTypeRef("preference"),
                new RetentionClassRef("standard"),
                "v2",
                "user:" + owner.requireUser().value(),
                predicate,
                new TypedPayload("skillforge.memory.preference.v2", com.skillforge.core.agent.memory.MemoryContent.explicit(
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("value",predicate),"preference").toString()),
                List.of(new EventId(eventId)),
                owner.sessionRef(),
                Optional.empty(),
                Optional.empty(),
                "memory-browser-fixture-v1",
                occurredAt,
                Optional.empty(),
                Optional.empty(),
                0.95,
                0.6,
                review,
                Optional.empty(),
                review ? FactStatus.PENDING_CONFIRM : FactStatus.ACTIVE);
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(owner.sessionRef().tenantId(), "memory-browser-run-" + predicate), trace, new ResultRef("memory-browser-cause-" + predicate), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(),"execution-input:"+(new RunRef(owner.sessionRef().tenantId(), "memory-browser-run-" + predicate)).runId()));
        var identity = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(owner.sessionRef().tenantId(), "memory-browser-fixture"),
                owner, Set.of("memory:extract"), 1, new ResultRef("memory-browser-verified-" + predicate));
        var context = new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(30), () -> false));
        return assertInstanceOf(MemoryRuntime.MutationResult.Recorded.class,
                runtime.mutate(new MemoryRuntime.MutationCall(context,
                        new MemoryRuntime.Mutation.Create(new IdempotencyKey("memory-browser-create-" + predicate), draft), occurredAt))).fact();
    }

    /** 将本次真实 fixture 交给单 worker Playwright；失败日志和子进程清理都有明确归属。 */
    private void runPlaywright(String slug, Map<String, Object> fixture, Path evidence) throws Exception {
        Path admin = MemoryBrowserServer.repoRoot().resolve("frontend-admin");
        Path cli = admin.resolve("node_modules/@playwright/test/cli.js");
        assertTrue(Files.isRegularFile(cli), "验收仅使用现有 Playwright，不安装依赖");
        var command = new ProcessBuilder(MemoryBrowserServer.nodeExecutable(), cli.toString(), "test", "--config=playwright.memory.config.ts");
        command.directory(admin.toFile());
        command.redirectErrorStream(true);
        Path log = evidence.resolve("playwright.log");
        command.redirectOutput(log.toFile());
        var environment = command.environment();
        environment.remove("E2E_BASE_URL");
        environment.remove("SDK4_LIVE_MODEL_KEY");
        environment.remove("E2E_LIVE_MODEL");
        environment.put("E2E_MEMORY_BASE_URL", frontend.baseUrl());
        environment.put("E2E_TENANT_SLUG", slug);
        environment.put("E2E_USERNAME", "memory-browser-admin");
        environment.put("E2E_READER_USERNAME", "memory-browser-reader");
        environment.put("E2E_PASSWORD", PASSWORD);
        environment.put("E2E_ARTIFACT_DIR", evidence.toString());
        environment.put("E2E_MEMORY_FIXTURE", evidence.resolve("fixture.json").toString());
        Process process = command.start();
        try (var owned = new MemoryBrowserProcesses(process.toHandle())) {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
            boolean finished = false;
            while (System.nanoTime() < deadline) {
                owned.capture();
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) { finished = true; break; }
            }
            owned.capture();
            assertTrue(finished, "记忆浏览器验收超出8分钟；证据=" + evidence);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "记忆浏览器验收失败；证据=" + evidence + "\n"
                    + output.substring(Math.max(0, output.length() - 8_000)));
        }
    }

    /** API 回执之外，再核对真实当前事实，证明审核、取代、遗忘恢复及 CAS 保护落库。 */
    private void verifyPersistedFacts(String tenant, Map<String, Object> fixture, Path evidence) throws Exception {
        var fixtureJson = json.valueToTree(fixture);
        var all = governance.list(tenant, null, 100);
        for (String name : List.of("approve", "reject", "conflict")) {
            String id = fixtureJson.path("facts").path(name).path("ref").path("fact_id").asText();
            MemoryFact fact = all.stream().filter(value -> value.ref().factId().equals(id)).findFirst().orElseThrow();
            assertEquals(switch (name) { case "approve" -> FactStatus.ACTIVE; case "reject" -> FactStatus.REJECTED;
                default -> FactStatus.FORGOTTEN; }, fact.status());
            assertEquals(2, fact.revision());
        }
        String originalMain = fixtureJson.path("facts").path("main").path("ref").path("fact_id").asText();
        var corrected = all.stream().filter(value -> value.derivedFrom()
                .map(reference -> reference.factId().equals(originalMain)).orElse(false)).findFirst().orElseThrow();
        assertEquals(FactStatus.ACTIVE, corrected.status());
        assertEquals(3, corrected.revision());
        assertEquals("corrected-" + fixture.get("runId"), corrected.predicate());
        assertTrue(corrected.object().canonicalJson().contains("corrected-" + fixture.get("runId")));
        assertTrue(all.stream().noneMatch(value -> value.predicate().equals("draft-conflict-" + fixture.get("runId"))),
                "冲突纠正不得写入草稿内容");
        var report = json.readTree(evidence.resolve("playwright-results.json").toFile());
        assertEquals(5, report.path("stats").path("expected").asInt(), "必须实际运行全部5个浏览器用例");
        assertEquals(0, report.path("stats").path("unexpected").asInt());
        assertEquals(0, report.path("stats").path("skipped").asInt());
        long modelOperations = queryCount(jdbc, tenant,
                "select count(*) from nw_runtime_session_model_operations where tenant_id=?", tenant);
        long externalInvocations = queryCount(jdbc, tenant,
                "select count(*) from nw_external_invocations where tenant_id=?", Long.valueOf(tenant));
        long extractionJobs = queryCount(jdbc, tenant,
                "select count(*) from agent_runtime_memory_extraction_jobs where tenant_id=?", tenant);
        assertEquals(0, modelOperations, "治理浏览器验收不得创建模型准备或调用");
        assertEquals(0, externalInvocations, "治理浏览器验收不得创建外部供应商调用");
        assertEquals(0, extractionJobs, "预置来源助手关闭自动提取，不得触发提取模型");
        Files.writeString(evidence.resolve("database-receipt.json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("assertedBrowserTests", 5, "correctedFactId", corrected.ref().factId(),
                        "correctedRevision", corrected.revision(), "correctedStatus", corrected.status(),
                        "modelOperations", modelOperations, "externalInvocations", externalInvocations,
                        "extractionJobs", extractionJobs)), StandardCharsets.UTF_8);
    }
}
