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

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.runtime.AgentRuntimeInboundGateway;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实Host/JDBC/IAM/HTTP取消验收。模型HTTP中的latch给出确切在途边界；
 * 测试只建立租户、Agent与产品Session，执行状态始终由正式Host和typed Store提交。
 */
@org.springframework.context.annotation.Import(CodeExecutionChatIntegrationTest.VerifiedModelProviderConfiguration.class)
class RuntimeCancellationWorkerE2ETest extends AbstractIntegrationTest {
    @DynamicPropertySource
    static void controlledWorkerStart(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
    }

    @Autowired private ApplicationContext context;
    @Autowired private ExecutionStore store;
    @Autowired private ChatRuntimeGateway gateway;
    @Autowired private AgentRuntimeHostProvisioner provisioner;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private EndUserMapper users;
    @Autowired private TenantMemberMapper members;
    @Autowired private AgentMapper agents;
    @Autowired private com.skillforge.core.agent.AgentPublicationService publications;
    @Autowired private com.skillforge.core.foundation.access.FoundationOrganizationService organization;
    @Autowired private SessionMapper sessions;
    @Autowired private PlatformModelCatalogService modelCatalog;
    // 本用例验收取消与后续调度，不调用任务认知的独立系统模型。
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.skillforge.institution.tasks.ConversationTaskCoordinator taskContext;

    private HttpServer model;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final CountDownLatch modelStarted = new CountDownLatch(1);
    private final CountDownLatch releaseModel = new CountDownLatch(1);
    private final CountDownLatch responseClosed = new CountDownLatch(1);

    /** 直调Host时显式建立入口追踪上下文，业务inputId不充当traceId。 */
    @org.junit.jupiter.api.BeforeEach
    void requestTrace() {
        com.skillforge.common.context.RequestContext.setTraceId("cancellation-test-" + java.util.UUID.randomUUID());
    }

    @AfterEach
    void stopFixture() {
        releaseModel.countDown();
        if (model != null) model.stop(0);
        TenantContext.clear();
        com.skillforge.common.context.RequestContext.clear();
    }

    @Test
    void cancellingTheActualModelCallRejectsLateAdoptionAndTheNextMessageCompletes() throws Exception {
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", exchange -> {
            int call = modelCalls.incrementAndGet();
            var requestBody = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            boolean streaming = requestBody.path("stream").asBoolean();
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                if (call == 1) {
                    modelStarted.countDown();
                    if (!releaseModel.await(30, TimeUnit.SECONDS)) throw new AssertionError("未收到取消请求");
                }
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                var message = Map.of("role", "assistant", "content", "你好，新消息已完成");
                String body;
                if (streaming) {
                    body = "data: " + json.writeValueAsString(Map.of("id", "reply", "model", "cancel-local-fixture",
                            "choices", List.of(Map.of("index", 0, "delta", message)))) + "\n\ndata: "
                            + json.writeValueAsString(Map.of("id", "reply", "choices", List.of(Map.of(
                                    "index", 0, "delta", Map.of(), "finish_reason", "stop"))))
                            + "\n\ndata: [DONE]\n\n";
                } else {
                    body = json.writeValueAsString(Map.of("id", "reply", "model", "cancel-local-fixture", "choices", List.of(Map.of(
                            "index", 0, "message", message, "finish_reason", "stop"))));
                }
                exchange.getResponseBody().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
                if (call == 1) responseClosed.countDown();
            }
        });
        model.start();

        var host = provisionHost("runtime-cancel", "cancel-new");
        var chatGateway = context.getBean(AgentRuntimeInboundGateway.class);
        var reply = submitGreeting(host, host.input());
        var run = new RunRef(new TenantId(host.tenantId()), reply.executionId());
        assertTrue(modelStarted.await(30, TimeUnit.SECONDS), "正式Host应已进入真实模型HTTP调用");
        var before = store.load(run).orElseThrow();
        assertEquals(host.owner(), before.identity().owner());
        assertEquals(RunStatus.RUNNING, before.status());
        assertEquals(ExecutionStore.Phase.AWAITING_RESULT, before.phase());
        var action = store.action(before.currentAction().orElseThrow()).orElseThrow();
        assertEquals(ExecutionStore.Kind.MODEL, action.kind());
        var attempt = store.attempt(run, action.currentAttempt()).orElseThrow();
        assertTrue(attempt.resultKind().isEmpty());
        assertEquals(1, modelCalls.get());

        assertEquals("cancelled", chatGateway.cancelChat(host.session(), run.runId()).status());
        var cancelled = store.load(run).orElseThrow();
        assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertEquals(ExecutionStore.Phase.FINISHED, cancelled.phase());
        assertTrue(cancelled.cancelled()); assertTrue(cancelled.lease().isEmpty());
        var committedEvents = store.events(host.owner().sessionRef(), 0, 1000);
        assertEquals(1, committedEvents.stream().filter(event -> event.run().equals(run)
                && event.event().type().equals("execution.completed")).count());
        assertEquals("cancelled", chatGateway.cancelChat(host.session(), run.runId()).status());
        assertEquals(cancelled, store.load(run).orElseThrow(), "重复取消保持原终态版本与事件");
        assertEquals(committedEvents, store.events(host.owner().sessionRef(), 0, 1000));

        // 原HTTP已开始且被确定性阻挡；取消提交后才允许它返回，迟到结果不得推进Run或最终回答。
        releaseModel.countDown();
        assertTrue(responseClosed.await(30, TimeUnit.SECONDS));
        await("late physical Attempt result", () -> store.attempt(run, attempt.id()).orElseThrow().resultKind().isPresent());
        var late = store.attempt(run, attempt.id()).orElseThrow();
        assertFalse(late.adopted());
        assertEquals(attempt.request(), late.request());
        assertEquals(attempt.action(), late.action());
        assertEquals(attempt.fence(), late.fence());
        assertEquals(cancelled, store.load(run).orElseThrow());
        assertEquals(committedEvents, store.events(host.owner().sessionRef(), 0, 1000));
        assertEquals(1, store.actions(run, 0, 100).size());
        assertEquals(1, modelCalls.get(), "取消与迟到结果均不得重发原调用");
        assertEquals("cancelled", chatGateway.chatRunStatus(host.session(), run.runId()).status());

        var next = submitGreeting(host, "after-cancel-" + host.suffix());
        var nextRun = new RunRef(new TenantId(host.tenantId()), next.executionId());
        assertNotEquals(run, nextRun);
        await("new message completion", () -> store.load(nextRun).filter(value -> value.status() == RunStatus.COMPLETED).isPresent());
        assertEquals(2, modelCalls.get(), "取消后的新消息只调用模型一次");
        assertEquals(cancelled, store.load(run).orElseThrow());
        assertEquals(late, store.attempt(run, attempt.id()).orElseThrow());
    }

    private HostFixture provisionHost(String tenantPrefix, String inputPrefix) {
        String suffix = Long.toString(SnowflakeIds.nextId());
        String tenantId = createTenant(tenants, plans, tenantPrefix + "-" + suffix);
        TenantContext.setTenantId(tenantId);
        PlatformModelFixtures.selectLlm(modelCatalog, tenantId,
                "http://127.0.0.1:" + model.getAddress().getPort(),
                "cancel-local-fixture", "cancel-local-fixture-key",
                "custom", "custom-openai-compatible", "custom-v1");
        provisioner.provision(tenantId);

        // 内容冻结仍经过真实成员与会话权限核验，不模拟放行 ArtifactAccess。
        TenantMemberDO member = new TenantMemberDO();
        member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.parseLong(tenantId));
        member.setUsername("cancel-requester-" + suffix); member.setRole("admin");
        member.setPermissions(List.of()); member.setStatus("active"); member.setCreatedAt(Instant.now());
        members.insert(member);
        initializeTenantAdministrator(tenantId, member.getId());

        EndUserDO user = new EndUserDO();
        user.setId(SnowflakeIds.nextId()); user.setTenantId(Long.parseLong(tenantId));
        user.setDisplayName("Cancellation fixture");
        user.setStatus("active"); user.setFirstSeenAt(Instant.now());
        user.setLastActiveAt(Instant.now()); user.setCreatedAt(Instant.now()); users.insert(user);
        AgentDO agent = new AgentDO();
        agent.setId(SnowflakeIds.nextId()); agent.setTenantId(Long.parseLong(tenantId));
        agent.setAgentKey("cancel-agent-" + suffix); agent.setName("Cancellation fixture");
        agent.setSystemPrompt("请返回确定性答案。"); agent.setRoleDescription("取消流程测试夹具");
         agent.setConfig(Map.of());
        agent.setStatus("draft");
        agent.setCreatedAt(Instant.now()); agent.setUpdatedAt(Instant.now()); agents.insert(agent);
        var publication = withAuthenticatedMember(member.getId(), () -> {
            organization.registerResource("agent", agent.getId().toString());
            return publications.publish(agent);
        });
        assertNotNull(publication.revisionId());
        var publishedIdentity = agents.selectById(agent.getId());
        assertEquals("published", publishedIdentity.getStatus());
        assertEquals(publication.revisionId(), publishedIdentity.getPublishedRevisionId());
        assertEquals(Integer.valueOf(publication.revision()), publishedIdentity.getRevision());
        var frozenPublication = withAuthenticatedMember(member.getId(), () ->
                publications.requirePublished(Long.parseLong(tenantId), agent.getId()));
        assertEquals(publication.revisionId(), frozenPublication.revisionId());
        assertEquals(publication.revision(), frozenPublication.revision());
        assertEquals(agent.getSystemPrompt(), frozenPublication.agent().getSystemPrompt());
        assertTrue(frozenPublication.mountSet().mounts().isEmpty(), "取消夹具不附加外部能力");
        SessionDO session = new SessionDO();
        session.setId(SnowflakeIds.nextId()); session.setSessionId("cancel-session-" + suffix);
        session.setTenantId(Long.parseLong(tenantId)); session.setEndUserId(user.getId());
        session.setAgentId(agent.getId()); session.setMessageCount(0); session.setLastEventId(0L);
        session.setMetadata(Map.of()); session.setCreatedAt(Instant.now()); session.setUpdatedAt(Instant.now());
        sessions.insert(session);
        withAuthenticatedMember(member.getId(), () -> {
            organization.registerSessionOwnership(session.getSessionId());
            return null;
        });

        String input = inputPrefix + "-" + suffix;
        var owner = SessionOwner.user(new SessionRef(new TenantId(tenantId), session.getSessionId()),
                new AgentId(agent.getId().toString()), new EndUserId(user.getId()));
        return new HostFixture(suffix, tenantId, input, member, user, agent, com.skillforge.institution.TestSessions.view(session), owner);
    }

    private com.skillforge.core.chat.dto.response.ChatResult submitGreeting(HostFixture host, String input) {
        var request = new ChatRequest("你好", host.session().getSessionId(), host.agent().getId().toString(), null,
                null, null, null, null, input, Map.of(), Map.of("input_id", input, "end_user_id", host.user().getId(),
                        "delegation_requester_id", host.member().getId().toString()));
        return withAuthenticatedMember(host.member().getId(), () -> gateway.submit(
                new ChatRuntimeGateway.Submission(request, host.session(), host.agent().getId().toString(), host.user().getId(), null)));
    }

    private void await(String label, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail(label + "; actual model HTTP calls=" + modelCalls.get());
    }

    private record HostFixture(String suffix, String tenantId, String input,
            TenantMemberDO member, EndUserDO user, AgentDO agent, com.skillforge.core.chat.session.SessionView session, SessionOwner owner) { }
}
