package com.skillforge.runtime.adapter.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真 PG 冻结路由与真实 MCP HTTP；测试派发上下文只描述原身份，发送权仍由当前授权guard核验。 */
@Testcontainers(disabledWithoutDocker = false)
class McpToolPortalTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("mcp_admin").withPassword("mcp_admin_password");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TenantId TENANT = new TenantId("1");
    private static final String RESULT = "{\"resultType\":\"complete\",\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"AQID\"},{\"type\":\"resource\",\"resource\":{\"uri\":\"test://item\",\"text\":\"resource body\"}}],\"structuredContent\":{\"answer\":42},\"isError\":false}";
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService workers;
    private JdbcMcpBindings bindings;
    private FrozenToolTarget target;
    private volatile Responder business;
    private volatile Responder handshake;

    @BeforeAll static void schema() throws Exception {
        sql("CREATE TABLE nw_tool_deployments(tenant_id bigint,deployment_key text,revision bigint,tool_version_id bigint,provider_type text,provider_ref text,status text)");
        sql("CREATE TABLE nw_capability_versions(id bigint,definition_id bigint,capability_type text,status text,tenant_id bigint,descriptor jsonb,content_hash text)");
        sql("CREATE TABLE nw_connector_instances(tenant_id bigint,instance_id text,base_url text,auth_type text,auth_credential_encrypted text,status text,executor_type text,vault_secret_path text,headers jsonb,capabilities jsonb)");
        try (var input = McpToolPortalTest.class.getClassLoader().getResourceAsStream("db/migration/V107__agent_runtime_mcp_bindings.sql")) {
            assertNotNull(input); sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var input = McpToolPortalTest.class.getClassLoader().getResourceAsStream("db/migration/V152__allow_readonly_mcp_idempotence.sql")) {
            assertNotNull(input); sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        sql("ALTER TABLE nw_runtime_mcp_bindings DROP CONSTRAINT nw_runtime_mcp_bindings_protocol_version_check");
        sql("ALTER TABLE nw_runtime_mcp_bindings ADD CONSTRAINT skillforge_runtime_mcp_bindings_protocol_version_check CHECK (protocol_version='2026-07-28')");
    }

    @BeforeEach void setup() throws Exception {
        sql("TRUNCATE nw_runtime_mcp_bindings,nw_tool_deployments,nw_capability_versions,nw_connector_instances");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        business = (exchange, request) -> respond(exchange, 200, "application/json",
                envelope(request.path("id").textValue(), RESULT));
        server.createContext("/mcp", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody()); String method = body.path("method").asText();
            requests.add(new Request(method, body, exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                    exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"),
                    exchange.getRequestHeaders().getFirst("Mcp-Method"),
                    exchange.getRequestHeaders().getFirst("Mcp-Name"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            try {
                if (handshake != null && !"tools/call".equals(method)) handshake.respond(exchange, body);
                else if ("tools/call".equals(method)) business.respond(exchange, body);
                else respond(exchange, 400, "application/json", "{}");
            } finally { exchange.close(); }
        });
        server.start();
        sql("INSERT INTO nw_capability_versions VALUES(101,100,'TOOL','PUBLISHED',1,'{\"model_invocable\":true,\"user_invocable\":false,\"tool_name\":\"lookup\",\"input_schema\":{},\"output_schema\":{},\"effect\":\"NON_IDEMPOTENT_WRITE\",\"idempotent\":false,\"confirmation_policy\":\"POLICY\",\"timeout_ms\":5000,\"sensitive_fields\":[],\"provider_requirements\":[]}', 'descriptor-hash')");
        sql("INSERT INTO nw_tool_deployments VALUES(1,'deployment',1,101,'MCP','connector','ACTIVE')");
        sql("INSERT INTO nw_connector_instances VALUES(1,'connector','http://127.0.0.1:" + server.getAddress().getPort()
                + "/mcp','bearer','encrypted-key','active','mcp',NULL,'{}','[\"search\"]')");
        var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        bindings = new JdbcMcpBindings(source);
        target = new FrozenToolTarget(new ToolHandle("tool:search"), new CapabilityRef(CapabilityType.TOOL, "100", "101"), "deployment", 1,
                new TypedPayload("input.v1", "{}"), new TypedPayload("output.v1", "{}"), ToolEffect.NON_IDEMPOTENT_WRITE, false, Duration.ofSeconds(5),
                true, ToolConfirmationPolicy.POLICY, Set.of(), Set.of(), true);
        bindings.publish(TENANT, target, new JdbcMcpBindings.Profile(
                "search", McpProtocol.PROTOCOL_VERSION));
    }

    @AfterEach void cleanup() { release.countDown(); if (server != null) server.stop(0); if (workers != null) workers.shutdownNow(); }

    private void acceptHandshake() {
        handshake = (exchange, request) -> {
            if ("initialize".equals(request.path("method").asText())) {
                assertNull(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                exchange.getResponseHeaders().set("Mcp-Session-Id", "session-one");
                respond(exchange, 200, "application/json", envelope(request.path("id").asText(),
                        "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}}}"));
            } else exchange.sendResponseHeaders(202, -1);
        };
    }
    @Test void handshakesBeforeBusinessAndCarriesSessionWithoutModernMetadata() throws Exception {
        acceptHandshake();
        business = (exchange, request) -> respond(exchange, 200, "application/json",
                envelope(request.path("id").asText(), "{\"content\":[{\"type\":\"text\",\"text\":\"real result\"}],\"isError\":false}"));
        assertInstanceOf(ToolOutcome.Succeeded.class, portal("search", McpHandshake.VERSION).invoke(call()));
        assertEquals(List.of("initialize", "notifications/initialized", "tools/call"), methods());
        assertEquals("session-one", requests.getLast().session());
        assertEquals(McpHandshake.VERSION, requests.getLast().version());
        assertFalse(requests.getLast().body().path("params").has("_meta"));
        assertEquals(1, toolCalls());
    }
    @Test void rejectsUnsupportedNegotiatedVersionBeforeBusinessDispatch() throws Exception {
        handshake = (exchange, request) -> respond(exchange, 200, "application/json",
                envelope(request.path("id").asText(), "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}}}"));
        assertInstanceOf(ToolOutcome.Failed.class, portal("search", McpHandshake.VERSION).invoke(call()));
        assertEquals(List.of("initialize"), methods());
        assertEquals(0, toolCalls());
    }
    @Test void rechecksAuthorizationAfterHandshakeAndNeverDispatchesRevokedBusiness() throws Exception {
        var attempts = new AtomicInteger();
        acceptHandshake();
        assertInstanceOf(ToolOutcome.Failed.class, portal(c -> attempts.incrementAndGet() == 1, c -> true, (c, o) -> true,
                "search", McpHandshake.VERSION).invoke(call()));
        assertEquals(List.of("initialize", "notifications/initialized"), methods());
        assertEquals(0, toolCalls());
    }
    @Test void unknownHandshakeBusinessIsNeverRetried() throws Exception {
        acceptHandshake();
        business = (exchange, request) -> respond(exchange, 503, "application/json", "{}");
        assertUnknown(portal("search", McpHandshake.VERSION).invoke(call()));
        assertEquals(1, toolCalls());
    }

    @Test void sendsOneStatelessPostWithRequiredMetadataAndPreservesNontextResult() throws Exception {
        var success = assertInstanceOf(ToolOutcome.Succeeded.class, portal().invoke(call()));
        assertEquals(List.of("tools/call"), methods());
        var request = requests.getFirst();
        assertEquals("mcp-tools-call:attempt", request.body().path("id").textValue());
        assertEquals("search", request.body().path("params").path("name").textValue());
        assertEquals(1, request.body().path("params").path("arguments").path("x").intValue());
        assertModernRequest(request);
        assertEquals(McpProtocol.OBSERVATION_SCHEMA, success.observation().schemaId());
        assertEquals(JSON.readTree(RESULT), JSON.readTree(success.observation().canonicalJson()));
    }

    @Test void parsesSseNotificationThenCompleteNontextRpcResult() throws Exception {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            requests.clear();
            business = (exchange, request) -> {
                String sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                        + "event: message\ndata: " + envelope(request.path("id").textValue(), RESULT) + "\n\n";
                respond(exchange, 200, "text/event-stream", sse.replace("\n", newline));
            };
            var success = assertInstanceOf(ToolOutcome.Succeeded.class, portal().invoke(call()));
            assertEquals(JSON.readTree(RESULT), JSON.readTree(success.observation().canonicalJson())); assertEquals(1, toolCalls());
            assertEquals(List.of("tools/call"), methods());
        }
    }

    @Test void sourceMutationCannotRerouteFrozenDeploymentOrChangeEncryptedCredential() throws Exception {
        sql("UPDATE nw_connector_instances SET base_url='http://127.0.0.1:1/other',auth_credential_encrypted='new-secret',status='disabled'");
        sql("UPDATE nw_tool_deployments SET revision=2,status='DISABLED'");
        assertInstanceOf(ToolOutcome.Succeeded.class, portal().invoke(call())); assertEquals(1, toolCalls());
        assertEquals(List.of("tools/call"), methods());
    }

    @Test void responseBodyDoesNotQueryOwnerPolicyForEveryByte() throws Exception {
        var owner = Thread.currentThread();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        String result = "{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\"" + "x".repeat(16_384) + "\"}]}";
        for (boolean sse : List.of(false, true)) {
            reads.set(0);
            business = (exchange, request) -> respond(exchange, 200,
                    sse ? "text/event-stream" : "application/json",
                    sse ? "data: " + envelope(request.path("id").textValue(), result) + "\n\n"
                            : envelope(request.path("id").textValue(), result));
            assertInstanceOf(ToolOutcome.Succeeded.class, portal().invoke(call(
                    Instant.now().plusSeconds(5), () -> {
                        if (Thread.currentThread() == owner) reads.incrementAndGet();
                        return false;
                    }, PortalCallKind.TOOL_INVOKE)));
            assertTrue(reads.get() < 100, "Owner policy reads must scale with chunks, not bytes: " + reads);
        }
    }

    @Test void guardInputSchemaWrongDispatchKindAndPreCancellationRejectBeforeAnyHttp() {
        assertInstanceOf(ToolOutcome.Failed.class, portal(value -> false, value -> true, (value, output) -> true).invoke(call()));
        assertInstanceOf(ToolOutcome.Failed.class, portal(value -> true, value -> false, (value, output) -> true).invoke(call()));
        assertInstanceOf(ToolOutcome.Failed.class, portal().invoke(call(Instant.now().plusSeconds(5), () -> false, PortalCallKind.MODEL_GENERATE)));
        assertInstanceOf(ToolOutcome.Failed.class, portal().invoke(call(Instant.now().plusSeconds(5), () -> true, PortalCallKind.TOOL_INVOKE)));
        var original = call();
        var unsupportedIdempotency = new FrozenToolTarget(target.handle(), target.capability(), target.deploymentId(), target.deploymentRevision(),
                target.inputSchema(), target.outputSchema(), target.effect(), true, target.timeout(), target.modelInvocable(),
                target.confirmation(), target.sensitiveFields(), target.providerRequirements(), target.outputSchemaDeclared());
        var unsupported = new ToolCall(original.context(), original.binding(), unsupportedIdempotency,
                original.arguments(), original.idempotencyKey());
        assertInstanceOf(ToolOutcome.Failed.class, portal().invoke(unsupported));
        assertTrue(requests.isEmpty());
    }

    @Test void rejectsLegacyBindingBeforeAnyHttp() {
        assertInstanceOf(ToolOutcome.Failed.class,
                portal("search", "2025-03-26").invoke(call()));
        assertTrue(requests.isEmpty());
    }

    @Test void wrongIdTruncationAndServerFailureAfterCallRemainUnknownWithoutReplay() {
        List<Responder> cases = List.of(
                (exchange, request) -> respond(exchange, 200, "application/json", envelope("wrong-id", RESULT)),
                (exchange, request) -> respond(exchange, 200, "text/event-stream", "data: " + envelope("wrong-id", RESULT) + "\n\n"),
                (exchange, request) -> respond(exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":\"mcp-tools-call:attempt\""),
                (exchange, request) -> respond(exchange, 503, "application/json", "private-provider-secret"));
        for (var responder : cases) {
            business = responder; requests.clear();
            assertUnknown(portal().invoke(call())); assertEquals(1, toolCalls());
            assertEquals(List.of("tools/call"), methods());
        }
    }

    @Test void rejectedOutputSchemaIsUnknownWhileConfirmedRpcAndToolErrorsAreSafeFailures() {
        assertUnknown(portal(value -> true, value -> true, (value, output) -> false).invoke(call())); assertEquals(1, toolCalls());
        requests.clear(); business = (exchange, request) -> respond(exchange, 200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + request.path("id").textValue()
                        + "\",\"error\":{\"code\":-1,\"message\":\"private-provider-secret\"}}");
        var rpc = assertInstanceOf(ToolOutcome.Failed.class, portal().invoke(call())); assertEquals("mcp_rpc_rejected", rpc.failure().reasonCode());
        assertFalse(rpc.toString().contains("private-provider-secret")); assertEquals(1, toolCalls());
        requests.clear(); business = (exchange, request) -> respond(exchange, 200, "application/json",
                envelope(request.path("id").textValue(),
                        "{\"resultType\":\"complete\",\"content\":[],\"isError\":true}"));
        var tool = assertInstanceOf(ToolOutcome.Failed.class, portal().invoke(call())); assertEquals("mcp_tool_rejected", tool.failure().reasonCode()); assertEquals(1, toolCalls());
    }

    @Test void structuredErrorHintsRemainBoundedAndNeverAuthorizeTransportReplay() throws Exception {
        for (String mode : List.of("write", "read", "safeRead")) {
            boolean safeRead = mode.equals("safeRead");
            if (!mode.equals("write")) {
                sql("TRUNCATE nw_runtime_mcp_bindings");
                sql("UPDATE nw_capability_versions SET descriptor=descriptor || '{\"effect\":\"READ_ONLY\",\"idempotent\":" + safeRead + "}'::jsonb");
                target = new FrozenToolTarget(target.handle(), target.capability(), target.deploymentId(), target.deploymentRevision(),
                        target.inputSchema(), target.outputSchema(), ToolEffect.READ_ONLY, safeRead, target.timeout(),
                        target.modelInvocable(), target.confirmation(), target.sensitiveFields(), target.providerRequirements(), true);
                bindings.publish(TENANT, target, new JdbcMcpBindings.Profile("search", McpProtocol.PROTOCOL_VERSION));
            }
            for (String status : List.of("408", "504", "429", "401", "403", "422", "400", "503", "\"422\"", "422.0", "\"503\"", "503.0", "\"504\"", "null")) {
                requests.clear();
                business = (exchange, request) -> respond(exchange, 200, "application/json",
                        envelope(request.path("id").textValue(), "{\"resultType\":\"complete\",\"content\":[{\"type\":\"text\",\"text\":\"arbitrary-private-error-body\"}],\"isError\":true,"
                                + "\"structuredContent\":{\"status\":" + status + ",\"error\":\"private-provider-secret\"}}"));
                var invocation=call();
                var failed = assertInstanceOf(ToolOutcome.Failed.class, portal().invoke(invocation));
                assertEquals(new ResultRef("mcp-result:"+invocation.context().dispatch().attemptId()),failed.failure().resultRef());
                String expected = switch (status) {
                    case "401" -> "mcp_tool_authentication_required";
                    case "403" -> "mcp_tool_permission_denied";
                    case "422" -> safeRead ? "mcp_tool_arguments_invalid" : "mcp_tool_arguments_invalid_after_dispatch";
                    case "503" -> "mcp_tool_unavailable";
                    case "408", "504" -> safeRead ? "mcp_tool_timeout" : "mcp_tool_rejected";
                    case "429" -> safeRead ? "mcp_tool_rate_limited" : "mcp_tool_rejected";
                    default -> "mcp_tool_rejected";
                };
                assertEquals(expected, failed.failure().reasonCode());
                assertEquals(RetryMode.NONE, failed.failure().retryMode());
                assertEquals(1, toolCalls(), "错误提示不能让 transport 自动重放");
                assertFalse(failed.toString().contains("private-provider-secret"));
                assertFalse(failed.toString().contains("arbitrary-private-error-body"));
            }
        }
    }

    @Test void mapsRepeatedInputRequiredAndUsesANewExactRpcIdForEveryRound() {
        String firstState = "opaque-state / 一";
        String secondState = "opaque-state / 二";
        business = (exchange, request) -> {
            long round = toolCalls();
            String result = switch ((int) round) {
                case 1 -> "{\"resultType\":\"input_required\",\"inputRequests\":{"
                        + "\"github_login\":{\"method\":\"elicitation/create\",\"params\":{"
                        + "\"mode\":\"form\",\"message\":\"GitHub login\",\"requestedSchema\":{"
                        + "\"type\":\"object\",\"properties\":{}}}}},\"requestState\":\"" + firstState + "\"}";
                case 2 -> "{\"resultType\":\"input_required\",\"inputRequests\":{"
                        + "\"workspace_roots\":{\"method\":\"roots/list\"}},\"requestState\":\""
                        + secondState + "\"}";
                default -> RESULT;
            };
            respond(exchange, 200, "application/json",
                    envelope(request.path("id").textValue(), result));
        };

        var first = assertInstanceOf(ToolOutcome.InputRequired.class,
                portal().invoke(call()));
        assertEquals(Optional.of(firstState), first.requestState());
        assertEquals(ToolInputMethod.ELICITATION_CREATE,
                first.inputRequests().get("github_login").method());

        var second = assertInstanceOf(ToolOutcome.InputRequired.class,
                portal().resume(continuation("continuation-1",
                        Map.of("github_login", new ToolInputResponse(
                                ToolInputMethod.ELICITATION_CREATE,
                                new TypedPayload("mcp.elicitation-result.v1",
                                        "{\"action\":\"accept\",\"content\":{\"name\":\"octocat\"}}"))),
                        Optional.of(firstState))));
        assertEquals(Optional.of(secondState), second.requestState());
        assertEquals(ToolInputMethod.ROOTS_LIST,
                second.inputRequests().get("workspace_roots").method());

        assertInstanceOf(ToolOutcome.Succeeded.class,
                portal().resume(continuation("continuation-2",
                        Map.of("workspace_roots", new ToolInputResponse(
                                ToolInputMethod.ROOTS_LIST,
                                new TypedPayload("mcp.roots-result.v1",
                                        "{\"roots\":[{\"uri\":\"file:///workspace\"}]}"))),
                        Optional.of(secondState))));

        var toolRequests = requests.stream()
                .filter(request -> request.method().equals("tools/call")).toList();
        assertEquals(List.of("mcp-tools-call:attempt", "mcp-tools-call:continuation-1",
                        "mcp-tools-call:continuation-2"),
                toolRequests.stream().map(request -> request.body().path("id").textValue()).toList());
        assertEquals(3, toolRequests.stream().map(request -> request.body().path("id").textValue())
                .distinct().count());
        assertFalse(toolRequests.get(0).body().path("params").has("inputResponses"));
        assertEquals("accept", toolRequests.get(1).body().path("params")
                .path("inputResponses").path("github_login").path("action").textValue());
        assertFalse(toolRequests.get(1).body().path("params")
                .path("inputResponses").path("github_login").has("method"));
        assertEquals(firstState,
                toolRequests.get(1).body().path("params").path("requestState").textValue());
        assertEquals("file:///workspace", toolRequests.get(2).body().path("params")
                .path("inputResponses").path("workspace_roots").path("roots").get(0)
                .path("uri").textValue());
        assertEquals(secondState,
                toolRequests.get(2).body().path("params").path("requestState").textValue());
        assertEquals(List.of("tools/call", "tools/call", "tools/call"), methods());
        toolRequests.forEach(McpToolPortalTest::assertModernRequest);
    }

    @Test void deadlineAfterToolDispatchClosesBlockedBodyAndDoesNotReplay() throws Exception {
        var received = stallBusiness();
        var result = workers.submit(() -> portal().invoke(call(Instant.now().plusMillis(900), () -> false, PortalCallKind.TOOL_INVOKE)));
        assertTrue(received.await(2, TimeUnit.SECONDS)); assertUnknown(result.get(3, TimeUnit.SECONDS));
        assertEquals(1, release.getCount()); assertEquals(1, toolCalls());
    }

    @Test void deadlineAfterContinuationDispatchIsOutcomeUnknownAndDoesNotReplay() throws Exception {
        var received = stallBusiness();
        var result = workers.submit(() -> portal().resume(continuation(
                "continuation-deadline",
                Map.of("workspace_roots", new ToolInputResponse(
                        ToolInputMethod.ROOTS_LIST,
                        new TypedPayload("mcp.roots-result.v1", "{\"roots\":[]}"))),
                Optional.of("opaque-state"), Instant.now().plusMillis(900), () -> false)));

        assertTrue(received.await(2, TimeUnit.SECONDS));
        assertUnknown(result.get(3, TimeUnit.SECONDS));
        assertEquals(1, release.getCount());
        assertEquals(1, toolCalls());
        assertEquals("mcp-tools-call:continuation-deadline",
                requests.getFirst().body().path("id").textValue());
    }

    @Test void cancellationAfterToolDispatchClosesBlockedBodyAndDoesNotReplay() throws Exception {
        var received = stallBusiness(); var cancelled = new AtomicBoolean();
        var result = workers.submit(() -> portal().invoke(call(Instant.now().plusSeconds(5), cancelled::get, PortalCallKind.TOOL_INVOKE)));
        assertTrue(received.await(2, TimeUnit.SECONDS)); cancelled.set(true); assertUnknown(result.get(2, TimeUnit.SECONDS));
        assertEquals(1, release.getCount()); assertEquals(1, toolCalls());
    }

    /** 真实 get 被中断后，在取消实际执行前完成响应，固定未登记正文的关闭竞争。 */
    @Test void responseCompletingDuringInterruptedCleanupClosesUnreadBodyExactlyOnce() throws Exception {
        var body = new TrackedBody("{}");
        HttpResponse<java.io.InputStream> response = new TestResponse(body);
        var responseFuture = new CancellationCompletionFuture<HttpResponse<java.io.InputStream>>();
        var dispatched = new CountDownLatch(1);
        var sent = new AtomicInteger();
        var client = new ResponseClient(request -> {
            sent.incrementAndGet(); dispatched.countDown(); return responseFuture;
        });
        var portal = new McpToolPortal((tenant, frozen) -> bindings.find(tenant, frozen).map(value -> new McpToolPortal.Binding(
                value.target(), binding("search"), value.endpoint(), value.encryptedCredential(), "search",
                McpProtocol.PROTOCOL_VERSION)), cipher -> "test-key".getBytes(StandardCharsets.UTF_8),
                value -> true, value -> true, value -> true, value -> true, (value, output) -> true,
                client, Clock.fixed(Instant.now(), ZoneOffset.UTC), 100_000);
        var outcome = new AtomicReference<ToolOutcome>();
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                outcome.set(portal.invoke(call()));
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable unexpected) { failure.set(unexpected); }
        }, "mcp-response-completion-interrupt-test");
        try {
            caller.start();
            assertTrue(dispatched.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(responseFuture.cancelling.await(2, TimeUnit.SECONDS), "真实 get 中断后必须进入原 send Future 清理");
            assertSame(caller, responseFuture.cancellingThread.get());
            assertTrue(responseFuture.complete(response), "让响应在实际取消前正常完成");
            assertTrue(responseFuture.isDone());
            assertFalse(responseFuture.isCancelled());
            responseFuture.allowCancellation.countDown();
            caller.join(5_000);
            assertFalse(caller.isAlive());
            assertNull(failure.get());
            assertUnknown(outcome.get());
            assertTrue(interrupted.get(), "Portal 保留调用线程的中断语义");
            assertEquals(1, sent.get(), "写操作结果未知时不重放");
            assertEquals(0, body.reads.get(), "退出等待后不解码未登记正文");
            assertEquals(1, body.closes.get(), "已完成响应正文仍须接管并恰好关闭一次");
        } finally {
            responseFuture.allowCancellation.countDown();
            responseFuture.complete(response);
            caller.interrupt(); caller.join(5_000); assertFalse(caller.isAlive());
            if (body.closes.get() == 0) body.close();
        }
    }

    /** get 使用 JDK 原实现；只固定 cancel 与响应完成的合法并发顺序。 */
    private static final class CancellationCompletionFuture<T> extends CompletableFuture<T> {
        final CountDownLatch cancelling = new CountDownLatch(1), allowCancellation = new CountDownLatch(1);
        final AtomicReference<Thread> cancellingThread = new AtomicReference<>();
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            cancellingThread.set(Thread.currentThread()); cancelling.countDown();
            try { assertTrue(allowCancellation.await(5, TimeUnit.SECONDS), "测试必须放行 Future 取消"); }
            catch (InterruptedException unexpected) { Thread.currentThread().interrupt(); throw new AssertionError(unexpected); }
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class TrackedBody extends java.io.ByteArrayInputStream {
        final AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
        TrackedBody(String value) { super(value.getBytes(StandardCharsets.UTF_8)); }
        @Override public synchronized int read(byte[] bytes, int offset, int length) {
            reads.incrementAndGet(); return super.read(bytes, offset, length);
        }
        @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
    }

    private record TestResponse(java.io.InputStream body) implements HttpResponse<java.io.InputStream> {
        @Override public int statusCode() { return 200; }
        @Override public HttpRequest request() { return HttpRequest.newBuilder(uri()).build(); }
        @Override public Optional<HttpResponse<java.io.InputStream>> previousResponse() { return Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (name, value) -> true);
        }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return java.net.URI.create("http://response.fixture/mcp"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /** 仅承载可控响应 Future，不发送真实网络请求或引入额外测试依赖。 */
    private static final class ResponseClient extends HttpClient {
        private final java.util.function.Function<HttpRequest, CompletableFuture<HttpResponse<java.io.InputStream>>> response;
        ResponseClient(java.util.function.Function<HttpRequest, CompletableFuture<HttpResponse<java.io.InputStream>>> response) {
            this.response = response;
        }
        @SuppressWarnings("unchecked")
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) response.apply(request);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    /** 服务端在测试结束前保持body打开，客户端只能通过取消/期限关闭来结束读取。 */
    private CountDownLatch stallBusiness() {
        var received = new CountDownLatch(1);
        business = (exchange, request) -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); received.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        return received;
    }
    private McpToolPortal portal() { return portal(call -> true, call -> true, (call, output) -> true); }
    private McpToolPortal portal(Predicate<ToolCall> authorize, Predicate<ToolCall> input, BiPredicate<ToolCall, TypedPayload> output) {
        return portal(authorize, input, output, "search", McpProtocol.PROTOCOL_VERSION);
    }
    private McpToolPortal portal(String toolName, String protocolVersion) {
        return portal(call -> true, call -> true, (call, output) -> true,
                toolName, protocolVersion);
    }
    private McpToolPortal portal(Predicate<ToolCall> authorize, Predicate<ToolCall> input,
            BiPredicate<ToolCall, TypedPayload> output, String toolName,
            String protocolVersion) {
        return new McpToolPortal((tenant, frozen) -> bindings.find(tenant, frozen).map(value -> new McpToolPortal.Binding(
                value.target(), binding(toolName), value.endpoint(), value.encryptedCredential(), toolName,
                protocolVersion)),
                cipher -> { assertEquals("encrypted-key", cipher); return "test-key".getBytes(StandardCharsets.UTF_8); },
                authorize, call -> true, input, call -> true, output,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                Clock.systemUTC(), 100_000);
    }
    private ToolCall call() { return call(Instant.now().plusSeconds(5), () -> false, PortalCallKind.TOOL_INVOKE); }
    private ToolContinuationCall continuation(String attemptId,
            Map<String, ToolInputResponse> responses, Optional<String> requestState) {
        return continuation(attemptId, responses, requestState,
                Instant.now().plusSeconds(5), () -> false);
    }
    private ToolContinuationCall continuation(String attemptId,
            Map<String, ToolInputResponse> responses, Optional<String> requestState,
            Instant deadline, CancellationSignal cancel) {
        var base = call(deadline, cancel,
                PortalCallKind.TOOL_INVOKE, attemptId);
        return new ToolContinuationCall(base.context(), base.binding(), base.target(),
                base.arguments(), base.idempotencyKey(), responses, requestState);
    }
    /** 明确冻结测试Action/Attempt派发身份；此上下文本身不能替代真实Store登记和发送前IAM。 */
    private ToolCall call(Instant deadline, CancellationSignal cancel, PortalCallKind kind) {
        return call(deadline, cancel, kind, "attempt");
    }
    private ToolCall call(Instant deadline, CancellationSignal cancel, PortalCallKind kind,
            String attemptId) {
        var input = new InputId("input"); var session = new SessionRef(TENANT, "session");
        var owner = SessionOwner.user(session, new AgentId("11"), new EndUserId(1));
        var run = new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var dispatch = new ProviderDispatch(run.runRef(), new ActionRef(TENANT, "action"), "invocation", attemptId, kind,
                new ResultRef("request:" + attemptId), new ResultRef("frozen-mcp-capability"), "fingerprint:" + attemptId, 1);
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, owner.requireUser()), owner, Set.of("runtime:invoke"), 1, new ResultRef("verified"));
        return new ToolCall(ProviderCallContext.dispatched(new PortCallContext(run, identity, new InvocationControl(deadline, cancel)), dispatch),
                binding("search"), target, new TypedPayload("arguments.v1", "{\"x\":1}"),
                new IdempotencyKey("action"));
    }
    private static ToolBinding binding(String toolName) {
        return new ToolBinding(ToolBinding.ProviderType.MCP, "connector", toolName);
    }
    private List<String> methods() { return requests.stream().map(Request::method).toList(); }
    private long toolCalls() { return requests.stream().filter(value -> value.method().equals("tools/call")).count(); }
    private static void assertUnknown(ToolOutcome value) {
        var unknown = assertInstanceOf(ToolOutcome.OutcomeUnknown.class, value);
        assertEquals("mcp_outcome_unknown", unknown.failure().reasonCode()); assertEquals(RetryMode.NONE, unknown.failure().retryMode());
        assertFalse(unknown.toString().contains("private-provider-secret"));
    }
    private static void assertModernRequest(Request request) {
        assertNull(request.session());
        assertEquals(McpProtocol.PROTOCOL_VERSION, request.version());
        assertEquals("tools/call", request.mirroredMethod());
        assertEquals(request.body().path("params").path("name").textValue(),
                request.mirroredName());
        assertEquals("application/json", request.contentType());
        assertEquals("application/json, text/event-stream", request.accept());
        assertEquals("Bearer test-key", request.authorization());
        var metadata = request.body().path("params").path("_meta");
        assertEquals(McpProtocol.PROTOCOL_VERSION,
                metadata.path("io.modelcontextprotocol/protocolVersion").textValue());
        assertTrue(metadata.path("io.modelcontextprotocol/clientCapabilities").isObject());
    }
    private static String envelope(String id, String result) { return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":" + result + "}"; }
    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static void sql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private record Request(String method, JsonNode body, String session, String version,
                           String mirroredMethod, String mirroredName, String contentType,
                           String accept, String authorization) { }
    @FunctionalInterface private interface Responder { void respond(HttpExchange exchange, JsonNode request) throws IOException; }
}
