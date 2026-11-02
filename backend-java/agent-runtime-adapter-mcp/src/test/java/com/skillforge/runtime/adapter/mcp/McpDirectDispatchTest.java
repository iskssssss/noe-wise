package com.skillforge.runtime.adapter.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 真实loopback MCP transport验证新派发入口，不启动数据库或构造旧permit。 */
class McpDirectDispatchTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TenantId TENANT = new TenantId("1");
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicBoolean authorized = new AtomicBoolean(true);
    private final AtomicInteger businessStarts = new AtomicInteger();
    private final McpToolPortal.BusinessSendAudit sendAudit = new McpToolPortal.BusinessSendAudit() {
        @Override public void beforeSend() { }
        @Override public void sendAttempted() { businessStarts.incrementAndGet(); }
    };
    private HttpServer server;
    private ExecutorService workers;
    private FrozenToolTarget target;
    private final ToolBinding binding = new ToolBinding(ToolBinding.ProviderType.MCP, "connector", "lookup");
    private volatile boolean revokeAfterHandshake;
    private volatile boolean waiting;
    private volatile String failureMode = "";

    /** 真HTTP接收器记录业务与握手；响应字节来自实际MCP envelope。 */
    @BeforeEach void open() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        server.createContext("/mcp", this::respond); server.start();
        target = new FrozenToolTarget(new ToolHandle("tool:lookup"), new CapabilityRef(CapabilityType.TOOL, "lookup", "v1"),
                "deployment", 1, new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"),
                ToolEffect.READ_ONLY, true, Duration.ofSeconds(5), true, ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), true);
    }

    /** 每个测试释放实际监听和执行器，响应完成后不残留后台工作。 */
    @AfterEach void close() {
        if (server != null) server.stop(0);
        if (workers != null) workers.close();
    }

    /** 只接受本次准确调用对象及一次执行；通用binding/policy/context来源均故障哨兵。 */
    @Test void directViewUsesFrozenRouteAndActualSseExactlyOnce() throws Exception {
        var call = call("attempt-1");
        assertEquals("attempt-1",call.context().dispatch().attemptId());
        assertEquals(call.context().callContext().runIdentity().runRef(),call.context().dispatch().run());
        assertEquals(PortalCallKind.TOOL_INVOKE,call.context().callKind());
        var authorizations = new AtomicInteger();
        var view = original().forDispatch(call, frozen(McpProtocol.PROTOCOL_VERSION),
                () -> { authorizations.incrementAndGet(); return authorized.get(); }, sendAudit, this::observation,
                Optional.of(new TypedPayload("application/json", "{\"device\":\"original-device\"}")));
        var substitute = new ToolCall(call.context(), call.binding(), call.target(), call.arguments(), call.idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> view.invoke(substitute));
        var result = assertInstanceOf(ToolOutcome.Succeeded.class, view.invoke(call));
        assertEquals("mcp-result:attempt-1", result.resultRef().value());
        assertEquals("actual-result", JSON.readTree(result.observation().canonicalJson()).path("structuredContent").path("answer").asText());
        assertEquals(3, authorizations.get(), "开始审计事务返回后仍须复验原当前授权");
        assertEquals(1, requests.size());
        assertEquals(1, businessStarts.get());
        assertEquals("mcp-tools-call:attempt-1", requests.getFirst().path("id").asText());
        assertEquals("original-device", requests.getFirst().path("params").path("_meta").path("com.skillforge/extraParameters").path("device").asText());
        assertThrows(IllegalArgumentException.class, () -> view.invoke(call));
        assertEquals(1, requests.size());
    }

    /** 握手后的撤权在真实tools/call之前生效；初始化本身不构成后续发送权限。 */
    @Test void revocationDuringHandshakePreventsBusinessSend() {
        revokeAfterHandshake = true;
        var call = call("handshake-attempt");
        var checks = new AtomicInteger();
        var view = original().forDispatch(call, frozen(McpHandshake.VERSION),
                () -> { checks.incrementAndGet(); return authorized.get(); }, sendAudit, this::observation, Optional.empty());
        var rejected = assertInstanceOf(ToolOutcome.Failed.class, view.invoke(call));
        assertEquals("mcp_pre_dispatch_rejected", rejected.failure().reasonCode());
        assertEquals(List.of("initialize", "notifications/initialized"), requests.stream().map(value -> value.path("method").asText()).toList());
        assertEquals(2, checks.get());
        assertEquals(0, businessStarts.get(), "握手后撤权不能登记工具调用");
    }

    /** 已登记不意味着握手可以忽略撤权；发送前拒绝不产生HTTP。 */
    @Test void deniedViewSendsNeitherHandshakeNorBusinessRequest() {
        authorized.set(false);
        var call = call("denied-attempt");
        var view = original().forDispatch(call, frozen(McpHandshake.VERSION), authorized::get, sendAudit, this::observation, Optional.empty());
        assertInstanceOf(ToolOutcome.Failed.class, view.invoke(call));
        assertTrue(requests.isEmpty());
        assertEquals(0, businessStarts.get());
    }

    /** 首次input_required与新Attempt续跑走真实HTTP，原参数/响应/opaque state原样保留。 */
    @Test void continuationUsesNewAttemptAndPreservesOriginalOpaqueState() throws Exception {
        waiting = true;
        var first = call("waiting-attempt");
        var required = assertInstanceOf(ToolOutcome.InputRequired.class, original().forDispatch(first,
                frozen(McpProtocol.PROTOCOL_VERSION), authorized::get, sendAudit, this::observation, Optional.empty()).invoke(first));
        assertEquals(Optional.of("opaque-exact-state"), required.requestState());
        var next = call("continuation-attempt");
        var responses = Map.of("browser-connection", new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload("application/json", "{\"device_id\":\"selected-device\"}")));
        var resumed = new ToolContinuationCall(next.context(), next.binding(), next.target(), next.arguments(),
                next.idempotencyKey(), responses, required.requestState());
        waiting = false;
        var view = original().forContinuation(resumed, frozen(McpProtocol.PROTOCOL_VERSION), authorized::get,
                sendAudit, this::observation, Optional.empty());
        var substituted = new ToolContinuationCall(resumed.context(),resumed.binding(),resumed.target(),resumed.arguments(),
                resumed.idempotencyKey(),resumed.inputResponses(),Optional.of("substituted-state"));
        assertThrows(IllegalArgumentException.class,()->view.resume(substituted));
        assertThrows(IllegalArgumentException.class,()->view.invoke(next));
        assertEquals(1,requests.size(),"替换opaque state或换入口不能触发续跑发送");
        assertInstanceOf(ToolOutcome.Succeeded.class, view.resume(resumed));
        var wire = requests.getLast();
        assertEquals(2, requests.size());
        assertEquals(2, businessStarts.get(), "input_required及continuation各登记原Attempt一次");
        assertEquals("mcp-tools-call:continuation-attempt", wire.path("id").asText());
        assertEquals(requests.getFirst().path("params").path("arguments"), wire.path("params").path("arguments"));
        assertEquals("opaque-exact-state", wire.path("params").path("requestState").asText());
        assertEquals("selected-device", wire.path("params").path("inputResponses").path("browser-connection").path("device_id").asText());
        assertThrows(IllegalArgumentException.class, () -> view.resume(resumed));
        assertEquals(2, requests.size());
    }

    /** 新Attempt的准确派发身份不能绕过当前撤权；续跑在任何握手或业务HTTP前失败。 */
    @Test void deniedContinuationDoesNotSendHandshakeOrBusinessRequest() {
        authorized.set(false);var next=call("denied-continuation");
        var continuation=new ToolContinuationCall(next.context(),next.binding(),next.target(),next.arguments(),next.idempotencyKey(),
                Map.of("roots",new ToolInputResponse(ToolInputMethod.ROOTS_LIST,new TypedPayload("application/json","{\"roots\":[]}"))),Optional.of("original-opaque-state"));
        var view=original().forContinuation(continuation,frozen(McpProtocol.PROTOCOL_VERSION),authorized::get,sendAudit, this::observation,Optional.empty());
        assertInstanceOf(ToolOutcome.Failed.class,view.resume(continuation));
        assertTrue(requests.isEmpty());
    }

    /** 2025-11-25握手协议未定义continuation；携带原响应和opaque state时在任何HTTP前确定拒绝。 */
    @Test void handshakeProtocolRejectsContinuationBeforeAnyHttp() {
        var next=call("unsupported-handshake-continuation");var checks=new AtomicInteger();
        var continuation=new ToolContinuationCall(next.context(),next.binding(),next.target(),next.arguments(),next.idempotencyKey(),
                Map.of("roots",new ToolInputResponse(ToolInputMethod.ROOTS_LIST,new TypedPayload("application/json","{\"roots\":[]}"))),Optional.of("original-opaque-state"));
        var view=original().forContinuation(continuation,frozen(McpHandshake.VERSION),()->{checks.incrementAndGet();return authorized.get();},sendAudit, this::observation,Optional.empty());
        var rejected=assertInstanceOf(ToolOutcome.Failed.class,view.resume(continuation));
        assertEquals("mcp_pre_dispatch_rejected",rejected.failure().reasonCode());
        assertTrue(requests.isEmpty(),"不支持continuation的协议不能先握手或发送tools/call");
        assertEquals(1,checks.get(),"已核首次当前授权；协议拒绝发生于握手和第二次发送授权之前");
        assertTrue(authorized.get(),"本次拒绝来自冻结协议约束，不能用撤权代替协议验证");
    }

    /** 完整 MCP 错误正文只保存在私有事实；不同失败不得压缩为同一个结果。 */
    @Test void preservesActualDeterminateFailureFactsWithoutResending() throws Exception {
        failureMode = "tool";
        var tool = failed("tool-failure");
        assertEquals("mcp_tool_rejected", tool.failure().reasonCode());
        assertEquals("private-provider-secret", JSON.readTree(tool.comparisonFact().orElseThrow().canonicalJson()).path("structuredContent").path("message").asText());
        assertFalse(tool.toString().contains("private-provider-secret"));

        failureMode = "rpc";
        var rpc = failed("rpc-failure");
        var rpcBody = JSON.readTree(rpc.comparisonFact().orElseThrow().canonicalJson());
        assertEquals(-32001, rpcBody.path("code").asInt());
        assertEquals("private-provider-secret", rpcBody.path("message").asText());
        assertEquals(7, rpcBody.path("data").path("detail").asInt());
        assertFalse(rpcBody.has("id"));

        failureMode = "http";
        var http = failed("http-failure");
        var httpBody = JSON.readTree(http.comparisonFact().orElseThrow().canonicalJson());
        assertEquals(403, httpBody.path("status").asInt());
        assertEquals("private-provider-secret", new String(Base64.getDecoder().decode(httpBody.path("bodyBase64").asText()), StandardCharsets.UTF_8));
        assertEquals(3, businessStarts.get());
        assertEquals(3, requests.size());
    }

    /** 收到确定 HTTP 状态后，正文超限只使比较事实不可得，不改变失败或再次发送。 */
    @Test void oversizedFailureBodyPreservesKnownFailureWithoutFact() {
        failureMode = "large-http";
        var failed = failed("large-http-failure");
        assertEquals("mcp_http_rejected", failed.failure().reasonCode());
        assertTrue(failed.comparisonFact().isEmpty());
        assertEquals(1, businessStarts.get());
    }

    private ToolOutcome.Failed failed(String attempt) {
        var call = call(attempt);
        return assertInstanceOf(ToolOutcome.Failed.class, original().forDispatch(call,
                frozen(McpProtocol.PROTOCOL_VERSION), authorized::get, sendAudit, this::observation, Optional.empty()).invoke(call));
    }

    /** 直接入口保留一次原始结果封套；业务schema采纳由宿主完成，transport不重写内容。 */
    private boolean observation(TypedPayload value) {
        return McpProtocol.OBSERVATION_SCHEMA.equals(value.schemaId()) && value.canonicalJson().contains("actual-result");
    }

    /** 通用生产来源均为失败哨兵；本次冻结视图只消费宿主提供的准确路由与当前授权。 */
    private McpToolPortal original() {
        return new McpToolPortal((tenant, frozen) -> { throw new AssertionError("general binding source queried"); },
                encrypted -> { throw new AssertionError("credential unexpectedly requested"); },
                call -> { throw new AssertionError("general policy invoked"); },
                call -> { throw new AssertionError("general continuation policy invoked"); },
                call -> "application/json".equals(call.arguments().schemaId()),
                call -> !call.inputResponses().isEmpty(),
                (call, output) -> { throw new AssertionError("general business schema guard invoked"); },
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC(), 65536,
                context -> { throw new AssertionError("general inbound source queried"); });
    }

    /** 冻结endpoint指向本测试的真实监听；与原ToolBinding完整一致。 */
    private McpToolPortal.Binding frozen(String protocol) {
        return new McpToolPortal.Binding(target, binding, "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                Optional.empty(), binding.toolName(), protocol);
    }

    /** 同Action的不同Attempt直接上下文，不构造旧STARTED fact或permit。 */
    private ToolCall call(String attempt) {
        var input = new InputId("input");
        var owner = SessionOwner.user(new SessionRef(TENANT, "session"), new AgentId("agent"), new EndUserId(1));
        var run = new RunRef(TENANT, "run");
        var identity = new RunIdentity(owner, owner.target().requireAgent(), input, run, new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var actor = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, owner.requireUser()), owner,
                Set.of("runtime:invoke"), 1, new ResultRef("verified"));
        var dispatch = new ProviderDispatch(run, new ActionRef(TENANT, "action"), "action", attempt, PortalCallKind.TOOL_INVOKE,
                new ResultRef("request:" + attempt), new ResultRef("capability"), "fingerprint:" + attempt, 1);
        var context = ProviderCallContext.dispatched(new PortCallContext(identity, actor,
                new InvocationControl(Instant.now().plusSeconds(5), () -> false)), dispatch);
        return new ToolCall(context, binding, target, new TypedPayload("application/json", "{\"query\":\"docs\"}"), new IdempotencyKey("action"));
    }

    /** 根据实际请求返回真实握手、input_required或SSE；不通过测试短路transport。 */
    private void respond(HttpExchange exchange) throws IOException {
        try {
            var request = JSON.readTree(exchange.getRequestBody()); requests.add(request);
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                if (revokeAfterHandshake) authorized.set(false);
                exchange.sendResponseHeaders(204, -1); return;
            }
            if (failureMode.equals("http") || failureMode.equals("large-http")) {
                byte[] body = (failureMode.equals("http") ? "private-provider-secret" : "x".repeat(70000)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(403, body.length); exchange.getResponseBody().write(body); return;
            }
            var packet = JSON.createObjectNode().put("jsonrpc", "2.0"); packet.set("id", request.get("id"));
            if (failureMode.equals("rpc")) {
                var error = packet.putObject("error").put("code", -32001).put("message", "private-provider-secret");
                error.putObject("data").put("detail", 7);
                byte[] body = ("data: " + JSON.writeValueAsString(packet) + "\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); return;
            }
            var result = packet.putObject("result");
            if ("initialize".equals(method)) {
                result.put("protocolVersion", McpHandshake.VERSION); result.putObject("capabilities").putObject("tools");
            } else if (waiting) {
                result.put("resultType", "input_required").put("requestState", "opaque-exact-state");
                var input = result.putObject("inputRequests").putObject("browser-connection");
                input.put("method", "elicitation/create"); input.putObject("params").put("message", "choose browser");
            } else {
                result.put("resultType", "complete").put("isError", failureMode.equals("tool")); result.putArray("content");
                if (failureMode.equals("tool")) result.putObject("structuredContent").put("message", "private-provider-secret");
                else result.putObject("structuredContent").put("answer", "actual-result");
            }
            boolean sse = "tools/call".equals(method) && !waiting;
            byte[] body = (sse ? "data: " + JSON.writeValueAsString(packet) + "\n\n" : JSON.writeValueAsString(packet)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } finally { exchange.close(); }
    }
}
