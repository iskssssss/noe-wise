package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.security.HmacSignatureVerifier;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.runtime.adapter.jdbc.callback.JdbcCallbackRegistrationPort;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackSigningKeys;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.session.spi.SessionWorkflowWakeups;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimePlatformProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实HMAC HTTP回调的typed等待接受/交付原子闭包，再由唯一Host Worker恢复原Workflow。 */
@org.springframework.context.annotation.Import(DeterministicModelCatalogConfiguration.class)
class RuntimeCallbackHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "RuntimeCallbackE2e123";
    private static final String REQUEST_TEXT = "请执行异步工作流并等待签名回调";
    private static final String RESULT_SENTINEL = "callback-http-original-result-中文-20261012";
    private static final String FINAL_TEXT = "签名回调工作流已完成：" + RESULT_SENTINEL;
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantMapper tenants;
    @Autowired private PlanMapper plans;
    @Autowired private PlatformModelCatalogService models;
    @Autowired private AgentRuntimeHostProvisioner provisioner;
    @Autowired private ApplicationContext application;
    @Autowired private AgentRuntimePlatformProperties properties;
    @Autowired private com.skillforge.runtime.starter.AgentRuntimeProperties runtimeProperties;
    @Autowired private JdbcCallbackRegistrationPort callbacks;
    @Autowired private JdbcExecutionLedgerPort executions;
    @Autowired private JdbcIdentityPolicyPort identities;
    @Autowired private ExecutionStore store;
    // 本例只验收Workflow回调主链，独立任务认知不占用同一个HTTP模型夹具。
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.skillforge.institution.tasks.ConversationTaskCoordinator taskContext;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private WorkflowModel provider;
    private String tenantId, slug, namespace, token, agentId, sessionId, inputId, runId;
    private long endUserId;
    private Key key;
    private SessionOwner owner;
    private RunRef runRef;

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup", () -> "true");
    }

    @BeforeEach
    void isolatedFoundationAndModel() throws Exception {
        namespace = "callback-http-" + SnowflakeIds.nextId();
        slug = namespace;
        provider = new WorkflowModel(mapper);
        provider.server.start();
        tenantId = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenantId);
        try {
            // 直接夹具仅创建Foundation账号与拥有的loopback模型。
            createTestTenantAdministrator(tenantId, "callback-admin", PASSWORD);
            PlatformModelFixtures.selectLlm(models, tenantId,
                    "http://127.0.0.1:" + provider.server.getAddress().getPort(),
                    "workflow-fixture", "workflow-fixture-key", "custom", "custom-openai-compatible", "custom-v1");
            provisioner.provision(tenantId);
        } finally { TenantContext.clear(); }
        requireRunningRuntimeWorkers(application, runtimeProperties);
    }

    @AfterEach
    void stopOwnedModel() throws Exception {
        try { if (provider != null) provider.close(); }
        finally { TenantContext.clear(); }
    }
    @Test
    void signedCallbackHttpPersistsReadyAndNormalWorkerResumesExactOriginalWorkflow() throws Exception {
        JsonNode login = admin("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "callback-admin", "password", PASSWORD), null, 200);
        token = required(login, "access_token");
        JsonNode issued = admin("POST", "/api/v1/service-principals", Map.of("integration_id", namespace,
                "scopes", List.of("sessions.write", "sessions.read", "sessions.events.read", "end_users.write")), null, 201);
        key = new Key(required(issued, "api_key"), required(issued, "signing_secret"));
        String workflowId = namespace + "-workflow";
        JsonNode draft = admin("POST", "/api/v1/builder/workflows", Map.of("workflow_id", workflowId,
                "definition", definition()), null, 200);
        assertEquals(workflowId, draft.path("workflow_id").asText());
        assertTrue(draft.path("revision").asLong() > 0);
        JsonNode published = admin("POST", "/api/v1/builder/workflows/publish", Map.of("workflow_id", workflowId),
                draft.path("revision").asText(), 200);
        JsonNode installed = admin("POST", "/api/v1/builder/workflows/install", Map.of("workflow_id", workflowId,
                "payload", Map.of("config", Map.of())), null, 200);
        JsonNode instanceList = admin("GET", "/api/v1/builder/workflows/instances", null, null, 200);
        var matches = new ArrayList<JsonNode>();
        for (JsonNode item : instanceList.path("items"))
            if (required(installed, "instance_id").equals(item.path("instance_id").asText())) matches.add(item);
        assertEquals(1, matches.size(), "formal HTTP installation must resolve exactly one instance");
        JsonNode instance = matches.getFirst();
        assertEquals(published.path("definition_version_id"), instance.path("definition_version_id"));
        JsonNode version = admin("GET", "/api/v1/capabilities/versions/detail?versionId="
                + required(published, "capability_version_id"), null, null, 200);
        JsonNode agent = admin("POST", "/api/v1/agents", Map.of("agent_key", namespace + "-agent", "name", "签名回调HTTP",
                "system_prompt", "调用已挂载工作流，使用异步结果完成最终回答。", "config", Map.of()), null, 200);
        agentId = required(agent, "id");
        JsonNode mounted = admin("POST", "/api/v1/agents/capability-mounts", Map.of("agent_id", agentId,
                "payload", Map.of("capability_type", "WORKFLOW", "capability_definition_id", required(version, "definition_id"),
                        "capability_version_id", required(published, "capability_version_id"), "priority", 100,
                        "workflow_config", Map.of("workflow_instance_id", required(instance, "id"),
                                "workflow_instance_revision", required(published, "definition_version_id")))), null, 201);
        assertEquals(1, mounted.path("mounts").size());
        admin("POST", "/api/v1/agents/publish", Map.of("agent_id", agentId), null, 200);
        JsonNode user = service("POST", "/api/v1/open-platform/end-users/ensure",
                Map.of("external_user_ref", namespace + "-user", "display_name", "签名回调用户"), null, 200);
        endUserId = Long.parseLong(required(user, "end_user_id"));
        JsonNode session = service("POST", "/api/v1/open-platform/sessions", Map.of("external_user_ref", namespace + "-user",
                "external_conversation_ref", namespace + "-room", "agent_id", agentId,
                "end_user_id", Long.toString(endUserId)), null, 200);
        sessionId = required(session, "session_id");
        owner = SessionOwner.user(new SessionRef(new TenantId(tenantId), sessionId), new AgentId(agentId), new EndUserId(endUserId));
        var message = Map.of("session_id", sessionId, "payload", Map.of("content", List.of(Map.of("type", "text",
                "text", REQUEST_TEXT)), "context", Map.of(), "references", List.of()));
        JsonNode receipt = service("POST", "/api/v1/open-platform/sessions/messages", message, namespace + "-input", 200);
        inputId = required(receipt, "input_id");
        runId = required(receipt, "run_id");
        assertEquals(inputId, required(receipt, "root_input_id"));
        assertFalse(receipt.path("replayed").asBoolean());
        runRef = new RunRef(new TenantId(tenantId), runId);
        await("typed Workflow wait", () -> store.load(runRef)
                .filter(run -> run.status() == RunStatus.SUSPENDED && run.phase() == ExecutionStore.Phase.WAITING && run.waiting().isPresent()).isPresent());
        var suspended = store.load(runRef).orElseThrow();
        assertEquals(owner, suspended.identity().owner());
        var waiting = store.waiting(runRef, suspended.waiting().orElseThrow()).orElseThrow();
        assertEquals(ExecutionStore.WaitKind.WORKFLOW, waiting.kind());
        var exact = assertInstanceOf(ExecutionStore.WorkflowWait.class, waiting.internal().orElseThrow());
        var originalCaller = identities.originalVerification(owner, suspended.identity().causationIdentityRef()).orElseThrow();
        ExecutionRecord execution = executions.find(new ExecutionLookup(originalCaller, exact.execution())).orElseThrow();
        assertEquals(WorkflowExecutionState.WAITING_EXTERNAL, execution.state());
        assertEquals(suspended.identity(), execution.owner());
        assertEquals(exact.executionRevision(), execution.executionRevision());
        assertEquals(exact.actionKey(), execution.waiting().orElseThrow().actionKey());
        assertEquals(exact.suspendRef(), execution.waiting().orElseThrow().suspendRef());
        assertEquals(List.of("dispatch"), provider.kinds);

        JsonNode registration = service("POST", "/api/v1/open-platform/sessions/workflow-callbacks",
                Map.of("session_id", sessionId, "run_id", runId), null, 200);
        var callback = new CallbackRef(new TenantId(tenantId), required(registration, "callback_id"));
        CallbackRegistration originalRegistration = callbacks.find(callback).orElseThrow();
        assertEquals(owner, originalRegistration.draft().owner());
        assertEquals(new AgentId(agentId), originalRegistration.draft().agentId());
        assertEquals(suspended.identity().causationIdentityRef(), originalRegistration.causationIdentityRef());
        var principal = assertInstanceOf(PrincipalRef.ServicePrincipal.class, originalCaller.principal());
        assertEquals(namespace, principal.integrationId());
        assertEquals(JdbcCallbackSigningKeys.reference(principal), originalRegistration.draft().signingCredentialRef());
        var target = assertInstanceOf(CallbackTarget.ExecutionTarget.class, originalRegistration.draft().target());
        assertEquals(exact.execution(), target.executionRef());
        assertEquals(runRef, target.owningRunRef());
        assertEquals(exact.executionRevision(), target.expectedExecutionRevision());
        assertEquals(exact.actionKey(), target.actionKey());
        assertEquals(CallbackRegistrationStatus.ACTIVE, originalRegistration.status());
        assertEquals(1, originalRegistration.revision());
        assertEquals("workflow-async-task", originalRegistration.draft().source());
        assertEquals(CallbackPayloads.EXECUTION_SCHEMA, originalRegistration.draft().payloadSchemaId());
        assertEquals(CallbackPayloads.SIGNATURE_POLICY, originalRegistration.draft().signaturePolicyId());
        assertEquals(tenantId, required(registration, "tenant_id"));
        assertEquals(exact.execution().executionId(), required(registration, "execution_id"));
        assertEquals(runId, required(registration, "run_id"));
        assertEquals(exact.actionKey(), required(registration, "action_key"));
        assertEquals(exact.suspendRef(), required(registration, "suspend_ref"));
        assertEquals(exact.executionRevision(), registration.path("execution_revision").asLong());
        assertEquals(suspended.revision(), registration.path("run_revision").asLong());
        assertEquals(waiting.reference().action().actionId(), required(registration, "action_id"));
        assertEquals(waiting.reference().generation(), registration.path("generation").asLong());
        assertEquals(CallbackPayloads.EXECUTION_SCHEMA, required(registration, "payload_schema"));
        assertEquals(SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA, required(registration, "response_schema"));
        assertEquals(CallbackPayloads.SIGNATURE_POLICY, required(registration, "signature_policy"));
        assertEquals("/api/v1/runtime/callbacks/deliveries", required(registration, "delivery_path"));
        assertTrue(Instant.parse(required(registration, "deadline_at")).isAfter(Instant.now()));
        assertEquals(registration, service("POST", "/api/v1/open-platform/sessions/workflow-callbacks",
                Map.of("session_id", sessionId, "run_id", runId), null, 200));
        assertEquals(originalRegistration, callbacks.find(callback).orElseThrow());
        assertTrue(waiting.acceptedResponse().isEmpty());

        var result = Map.of("status", "completed", "result", Map.of("sentinel", RESULT_SENTINEL,
                "中文列表", List.of("第一项", "第二项"), "nested", Map.of("enabled", true, "count", 7)));
        String resultJson = mapper.writeValueAsString(result);
        byte[] body = callbackBody(registration, resultJson);
        String delivery = namespace + "-delivery";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        assertEquals(new TypedPayload(SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA, resultJson),
                CallbackPayloads.businessPayload(originalRegistration, body), "真实原字节必须满足已注册载荷协议");
        assertTrue(properties.getCallbackPayloadSchemaIds().contains(SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA),
                "宿主必须显式启用正式Workflow异步响应schema");
        JsonNode ready = deliver(callback, delivery, timestamp, body, false, 200);
        assertEquals("ready", required(ready, "status"));
        assertEquals(delivery, required(ready, "delivery_id"));
        var proof = one("agent_runtime_callback_ingress_proofs", "delivery_id", delivery);
        var delivered = one("agent_runtime_callback_deliveries", "delivery_id", delivery);
        assertEquals("DELIVERED", delivered.get("status"), "HTTP delivery confirms the accepted typed response in the same Store transaction");
        assertEquals(1, ((Number) delivered.get("attempt")).intValue());
        assertNull(delivered.get("lease_claim"));
        assertEquals(originalRegistration.revision(), ((Number) proof.get("registration_revision")).longValue());
        var callbackInput = new InputId(delivered.get("input_id").toString());
        assertEquals("callback:" + proof.get("fingerprint"), callbackInput.value());
        assertNotEquals(inputId, callbackInput.value());
        var deliveredReceipt = callbacks.delivery(new CallbackDeliveryRef(callback.tenantId(), delivery)).orElseThrow();
        var envelope = deliveredReceipt.envelope().orElseThrow();
        assertEquals(CallbackDeliveryStatus.DELIVERED, deliveredReceipt.status());
        assertEquals(owner, new SessionOwner(envelope.sessionRef(), envelope.subject(), envelope.sessionTarget()));
        assertEquals(new AgentId(agentId), envelope.agentId());
        assertEquals(callbackInput, envelope.inputId());
        assertEquals(new EventId(callbackInput.value()), envelope.eventId());
        assertEquals(InboundKind.CALLBACK, envelope.kind());
        assertEquals(new RunTarget.ExistingRun(runRef), envelope.runTarget());
        assertEquals(originalRegistration.causationIdentityRef(), envelope.causationIdentityRef().orElseThrow());
        assertEquals(proof.get("authentication_ref"), envelope.authenticationRef().value());
        assertEquals("callback", envelope.channel());
        assertEquals("workflow-async-task", envelope.source());
        assertEquals(CallbackPayloads.EXECUTION_SCHEMA, envelope.payload().schemaId());
        assertEquals(new String(body, StandardCharsets.UTF_8), envelope.payload().canonicalJson());

        var acceptedWait = store.response(runRef, callbackInput).orElseThrow();
        assertEquals(waiting.reference(), acceptedWait.reference());
        assertEquals(waiting.sourceAttempt(), acceptedWait.sourceAttempt());
        assertEquals(waiting.internal(), acceptedWait.internal());
        var accepted = acceptedWait.acceptedResponse().orElseThrow();
        assertEquals(callbackInput, accepted.input());
        assertEquals(new TypedPayload(SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA, resultJson),
                store.material(new TenantId(tenantId), accepted.response()).orElseThrow().content());
        var closure = queryRows(jdbc, tenantId, """
                SELECT b.run_id,b.action_id,b.generation,b.input_id,w.response_ref,w.accepted_revision,
                    d.status,d.command_digest,p.command_digest AS proof_digest
                FROM agent_runtime_execution_workflow_callback_deliveries b
                JOIN agent_runtime_execution_waits w ON w.tenant_id=b.tenant_id AND w.run_id=b.run_id
                    AND w.action_id=b.action_id AND w.generation=b.generation
                JOIN agent_runtime_callback_deliveries d ON d.tenant_id=b.tenant_id AND d.delivery_id=b.delivery_id
                JOIN agent_runtime_callback_ingress_proofs p ON p.tenant_id=b.tenant_id AND p.delivery_id=b.delivery_id
                WHERE b.tenant_id=? AND b.delivery_id=?
                """, tenantId, delivery);
        assertEquals(1, closure.size());
        var closed = closure.getFirst();
        assertEquals(runId, closed.get("run_id"));
        assertEquals(waiting.reference().action().actionId(), closed.get("action_id"));
        assertEquals(waiting.reference().generation(), ((Number) closed.get("generation")).longValue());
        assertEquals(callbackInput.value(), closed.get("input_id"));
        assertEquals(accepted.response().value(), closed.get("response_ref"));
        assertEquals(accepted.acceptedRevision(), ((Number) closed.get("accepted_revision")).longValue());
        assertEquals("DELIVERED", closed.get("status"));
        assertEquals(closed.get("proof_digest"), closed.get("command_digest"));

        // 刷新签名时间仍重放原delivery；变更正文、签名、租户和过期时间均不能替换原证明。
        String freshTimestamp = Long.toString(Long.parseLong(timestamp) + 1);
        assertEquals("delivered", required(deliver(callback, delivery, freshTimestamp, body, false, 200), "status"));
        assertEquals(proof, one("agent_runtime_callback_ingress_proofs", "delivery_id", delivery));
        assertEquals(delivered, one("agent_runtime_callback_deliveries", "delivery_id", delivery));
        assertEquals(envelope, callbacks.delivery(deliveredReceipt.deliveryRef()).orElseThrow().envelope().orElseThrow());
        byte[] changed = callbackBody(registration, mapper.writeValueAsString(Map.of("status", "completed", "result", Map.of("sentinel", "changed-result"))));
        deliver(callback, delivery, freshTimestamp, changed, false, 401);
        deliver(callback, namespace + "-bad-signature", timestamp, body, true, 401);
        String staleTimestamp = Long.toString(Instant.now().minus(properties.getCallbackSignatureWindow()).minusSeconds(60).getEpochSecond());
        deliver(callback, namespace + "-expired", staleTimestamp, body, false, 401);
        deliver(new CallbackRef(new TenantId(Long.toString(Long.parseLong(tenantId) + 1)), callback.callbackId()), namespace + "-foreign-tenant", timestamp, body, false, 401);
        assertEquals(1, tenantCount("agent_runtime_callback_ingress_proofs"));
        assertEquals(1, tenantCount("agent_runtime_callback_deliveries"));

        await("original typed Run and Workflow completion", () -> store.load(runRef).filter(run -> run.status() == RunStatus.COMPLETED).isPresent()
                && executions.find(new ExecutionLookup(originalCaller, exact.execution())).filter(value -> value.state() == WorkflowExecutionState.COMPLETED).isPresent());
        await("public final history for original Run", () -> {
            try { return finalMessages(history()).size() == 1; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        assertEquals(List.of("dispatch", "respond"), provider.kinds);
        assertTrue(provider.sawWorkflowCompletion);
        assertEquals(1, provider.completionRequests.size());
        assertTrue(provider.completionRequests.getFirst().path("messages").toString().contains(RESULT_SENTINEL));
        var finalWait = store.response(runRef, callbackInput).orElseThrow();
        assertEquals(accepted, finalWait.acceptedResponse().orElseThrow());
        var attempt = store.attempt(runRef, finalWait.continuationAttempt().orElseThrow()).orElseThrow();
        assertEquals(Optional.of(waiting.reference()), attempt.continuation());
        assertEquals(Optional.of(ExecutionStore.ResultKind.SUCCEEDED), attempt.resultKind());
        assertTrue(attempt.adopted());
        var action = store.action(waiting.reference().action()).orElseThrow();
        assertEquals(ExecutionStore.Kind.WORKFLOW, action.kind());
        assertEquals(Optional.of(attempt.id()), action.selectedAttempt());
        assertEquals(1, store.actions(runRef, 0, 100).stream().filter(value -> value.kind() == ExecutionStore.Kind.WORKFLOW).count());
        var resume = mapper.readTree(store.material(new TenantId(tenantId), attempt.request()).orElseThrow().content().canonicalJson());
        assertEquals("resume", resume.path("operation").asText());
        assertEquals(exact.execution().executionId(), resume.path("executionRef").path("executionId").asText());
        assertEquals(exact.actionKey(), resume.path("actionKey").asText());
        assertEquals(exact.suspendRef(), resume.path("suspendRef").asText());
        assertEquals(accepted.response().value(), resume.path("responseRef").asText());
        var completed = executions.find(new ExecutionLookup(originalCaller, exact.execution())).orElseThrow();
        assertEquals(execution.owner(), completed.owner());
        assertTrue(completed.executionRevision() > execution.executionRevision());
        assertTrue(completed.waiting().isEmpty()); assertTrue(completed.lease().isEmpty()); assertTrue(completed.resultRef().isPresent());
        assertEquals(mapper.valueToTree(result.get("result")), mapper.readTree(completed.checkpoint().canonicalJson()).path("engine").path("extracted").path("callback"));
        assertEquals(mapper.readTree(execution.checkpoint().canonicalJson()).path("engine").path("cursor").path("visits").path("async"),
                mapper.readTree(completed.checkpoint().canonicalJson()).path("engine").path("cursor").path("visits").path("async"));
        var events = events();
        assertEquals(1, events.stream().filter(value -> value.run().equals(runRef) && value.event().type().equals("execution.completed")).count());
        assertEquals(1, events.stream().filter(value -> value.run().equals(runRef) && value.event().type().equals("interaction.resolved")).count());
        long previous = 0;
        for (var event : events) { assertTrue(event.sequence() > previous); previous = event.sequence(); }
        var finalRun = store.load(runRef).orElseThrow();
        assertEquals(suspended.identity(), finalRun.identity());
        JsonNode history = history();
        JsonNode finalMessage = finalMessages(history).getFirst();
        assertEquals(FINAL_TEXT, finalMessage.path("content").asText());
        assertEquals(runId, finalMessage.path("metadata").path("root_run_id").asText());
        assertEquals(inputId, finalMessage.path("metadata").path("root_input_id").asText());

        assertEquals("delivered", required(deliver(callback, delivery, Long.toString(Instant.now().getEpochSecond()), body, false, 200), "status"));
        assertEquals(delivered, one("agent_runtime_callback_deliveries", "delivery_id", delivery));
        assertEquals(proof, one("agent_runtime_callback_ingress_proofs", "delivery_id", delivery));
        assertEquals(finalWait, store.response(runRef, callbackInput).orElseThrow());
        assertEquals(attempt, store.attempt(runRef, attempt.id()).orElseThrow());
        assertEquals(events, events());
        assertEquals(finalRun, store.load(runRef).orElseThrow());
        assertEquals(history.path("messages"), history().path("messages"));
        JsonNode inputReplay = service("POST", "/api/v1/open-platform/sessions/messages", message, namespace + "-input", 200);
        assertEquals(inputId, required(inputReplay, "input_id")); assertEquals(runId, required(inputReplay, "run_id")); assertTrue(inputReplay.path("replayed").asBoolean());
        assertEquals(List.of("dispatch", "respond"), provider.kinds);
        assertEquals(1, tenantCount("agent_runtime_callback_deliveries"));
    }

    private byte[] callbackBody(JsonNode registration, String resultJson) throws Exception {
        return mapper.writeValueAsBytes(List.of(tenantId, required(registration, "execution_id"), runId,
                required(registration, "action_key"), registration.path("execution_revision").asText(),
                SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA, resultJson));
    }
    private JsonNode deliver(CallbackRef callback, String delivery, String timestamp, byte[] body,
            boolean corruptSignature, int expected) throws Exception {
        String signature = signature(callback, delivery, timestamp, body);
        if (corruptSignature) signature = (signature.charAt(0) == '0' ? "1" : "0") + signature.substring(1);
        var request = HttpRequest.newBuilder(URI.create(base() + "/api/v1/runtime/callbacks/deliveries"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .header("X-Tenant-Id", callback.tenantId().value()).header("X-Callback-Id", callback.callbackId())
                .header("X-Delivery-Id", delivery).header("X-Timestamp", timestamp).header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode responseBody = mapper.readTree(response.body());
        assertEquals(expected, response.statusCode(), () -> "callback delivery HTTP status; code=" + responseBody.path("code").asText()
                + "; message=" + responseBody.path("message").asText());
        return responseBody;
    }
    private String signature(CallbackRef callback, String delivery, String timestamp, byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(CallbackPayloads.signingBytes(callback,
                new CallbackDeliveryRef(callback.tenantId(), delivery), timestamp, body)));
    }

    private JsonNode service(String method, String target, Object value, String idempotency, int expected) throws Exception {
        String body = value == null ? "" : mapper.writeValueAsString(value);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        var request = HttpRequest.newBuilder(URI.create(base() + target)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("X-Api-Key", key.apiKey()).header("X-Timestamp", timestamp)
                .header("X-Signature", HmacSignatureVerifier.sign(key.secret(), HmacSignatureVerifier.canonical(timestamp, method, target, body)));
        if (idempotency != null) request.header("Idempotency-Key", idempotency);
        var response = http.send(request.method(method, value == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode envelope = mapper.readTree(response.body());
        assertEquals(expected, response.statusCode(), method + " " + target + "; code=" + envelope.path("code").asText()
                + "; message=" + envelope.path("message").asText());
        return envelope.path("data");
    }

    private JsonNode admin(String method, String target, Object value, String revision, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + target)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (revision != null) request.header("If-Match", revision);
        var response = http.send(request.method(method, value == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode envelope = mapper.readTree(response.body());
        assertEquals(expected, response.statusCode(), method + " " + target + "; code=" + envelope.path("code").asText()
                + "; message=" + envelope.path("message").asText());
        return envelope.path("data");
    }

    private JsonNode history() throws Exception {
        return service("GET", "/api/v1/open-platform/sessions/history?sessionId=" + sessionId, null, null, 200);
    }

    private List<JsonNode> finalMessages(JsonNode history) {
        var found = new ArrayList<JsonNode>();
        for (JsonNode message : history.path("messages")) if ("assistant".equals(message.path("role").asText())
                && runId.equals(message.path("metadata").path("run_id").asText())
                && "final".equals(message.path("metadata").path("phase").asText())) found.add(message);
        return found;
    }

    private String base() { return "http://127.0.0.1:" + port; }
    private static String required(JsonNode value, String field) {
        String result = value.path(field).asText();
        assertFalse(result.isBlank(), "required HTTP field missing: " + field);
        return result;
    }
    private Map<String, Object> one(String table, String keyColumn, String value) {
        var rows = queryRows(jdbc, tenantId, "select * from " + table + " where tenant_id=? and " + keyColumn + "=?", tenantId, value);
        assertEquals(1, rows.size(), "exact tenant-scoped " + table + " " + keyColumn);
        return rows.getFirst();
    }
    private long tenantCount(String table) {
        return queryCount(jdbc, tenantId, "select count(*) from " + table + " where tenant_id=?", tenantId);
    }
    private List<ExecutionStore.SavedEvent> events() {
        var events = new ArrayList<ExecutionStore.SavedEvent>();
        long after = 0;
        while (true) {
            var page = store.events(owner.sessionRef(), after, 100);
            if (page.isEmpty()) return List.copyOf(events);
            events.addAll(page);
            after = page.getLast().sequence();
        }
    }
    private void await(String label, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            var current = store.load(runRef);
            if (!provider.failures.isEmpty() || current.filter(run -> run.phase() == ExecutionStore.Phase.UNCERTAIN).isPresent())
                fail(diagnostic(label, current));
            Thread.sleep(100);
        }
        fail(diagnostic(label, store.load(runRef)));
    }
    private String diagnostic(String label, Optional<ExecutionStore.Run> current) {
        return label + "; models=" + provider.kinds
                + "; fixtureFailures=" + provider.failures + "; run=" + current
                + "; phase=" + current.map(ExecutionStore.Run::phase)
                + "; waiting=" + current.flatMap(ExecutionStore.Run::waiting)
                + "; actions=" + store.actions(runRef, 0, 100)
                + "; callbacks=" + queryRows(jdbc, tenantId,
                        "select delivery_id,status,attempt from agent_runtime_callback_deliveries where tenant_id=?", tenantId);
    }
    private static Map<String, Object> definition() {
        return Map.of("version", "1.0", "entry", "start", "meta", Map.of("display_name", "Signed callback HTTP",
                "capability", Map.of("model_invocable", true, "user_invocable", true, "input_schema", Map.of(),
                        "output_schema", Map.of(), "supports_hitl", false, "export_fields", List.of("messages", "callback"),
                        "timeout_ms", 300_000, "concurrency_safe", true)),
                "nodes", List.of(Map.of("id", "start", "type", "start", "name", "开始"),
                        Map.of("id", "async", "type", "async_task", "name", "异步等待", "result_to", "extracted.callback", "timeout_seconds", 300),
                        Map.of("id", "done", "type", "output", "name", "完成", "message", "workflow-fixture-completed"),
                        Map.of("id", "end", "type", "end", "name", "结束")),
                "edges", List.of(Map.of("from", "start", "to", "async"), Map.of("from", "async", "to", "done"), Map.of("from", "done", "to", "end")));
    }
    private record Key(String apiKey, String secret) { @Override public String toString() { return "test-key[redacted]"; } }
    /** 独立 HTTP 模型只按真实注入的能力与完成 Observation 作答，不读取数据库或操纵 Runtime。 */
    private static final class WorkflowModel {
        private final ObjectMapper mapper;
        private final HttpServer server;
        private final CopyOnWriteArrayList<String> kinds = new CopyOnWriteArrayList<>();
        private volatile boolean sawWorkflowCompletion;
        private final CopyOnWriteArrayList<JsonNode> completionRequests = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> failures = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        private WorkflowModel(ObjectMapper mapper) throws IOException {
            this.mapper = mapper;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/chat/completions", this::respond);
        }
        void close() throws InterruptedException {
            server.stop(0);
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS),
                    "owned model handlers must terminate before fixture cleanup completes");
        }
        private void respond(HttpExchange exchange) throws IOException {
            try { respondModel(exchange); }
            catch (IOException | RuntimeException rejected) {
                failures.add(rejected.getClass().getSimpleName() + ":" + rejected.getMessage());
                byte[] body = mapper.writeValueAsBytes(Map.of("error", Map.of("message", "callback_fixture_rejected", "type", "fixture_error")));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            } finally { exchange.close(); }
        }
        private void respondModel(HttpExchange exchange) throws IOException {
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
                completionRequests.add(request.deepCopy());
                if (!messages.contains(RESULT_SENTINEL)) throw new IOException("actual callback result absent from Workflow observation");
                kinds.add("respond");
                assistant = Map.of("role", "assistant", "content", FINAL_TEXT);
            } else {
                String handle = workflowHandle(request);
                kinds.add("dispatch");
                var decision = Map.of("handle", handle, "commentary", "正在发起异步工作流");
                assistant = Map.of("role", "assistant", "content", "正在发起异步工作流", "tool_calls", List.of(Map.of(
                        "id", "workflow-fixture-call", "type", "function", "function", Map.of("name", "run_workflow",
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

        /** 原生工具目录是唯一模型路由来源，完整句柄含原Run输入身份，不能从正文截短。 */
        private static String workflowHandle(JsonNode request) throws IOException {
            JsonNode handles = null;
            for (var tool : request.path("tools")) {
                if (!"function".equals(tool.path("type").asText()) || !"run_workflow".equals(tool.path("function").path("name").asText())) continue;
                if (handles != null) throw new IOException("callback_workflow_tool_ambiguous");
                handles = tool.path("function").path("parameters").path("properties").path("handle").path("enum");
            }
            if (handles == null || !handles.isArray() || handles.size() != 1 || !handles.get(0).isTextual()
                    || handles.get(0).textValue().isBlank()) throw new IOException("callback_workflow_handle_missing_or_ambiguous");
            return handles.get(0).textValue();
        }

    }
}
