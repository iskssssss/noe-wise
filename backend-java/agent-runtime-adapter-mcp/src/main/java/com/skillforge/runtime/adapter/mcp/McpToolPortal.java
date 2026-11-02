package com.skillforge.runtime.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** 冻结 MCP 协议路由的 Streamable HTTP 调用；握手只在业务派发前进行，未知副作用不重放。 */
public final class McpToolPortal implements ToolPortal {
    /** 宿主先提交开始事实；发送标记只能做本地无阻塞记录，不拥有重试或执行状态。 */
    public interface BusinessSendAudit {
        void beforeSend();
        void sendAttempted();
    }

    private static final BusinessSendAudit NO_AUDIT = new BusinessSendAudit() {
        @Override public void beforeSend() { }
        @Override public void sendAttempted() { }
    };
    private final BindingSource bindings;
    private final Function<String, byte[]> decrypt;
    private final Predicate<ToolCall> authorize;
    private final Predicate<ToolContinuationCall> authorizeContinuation;
    private final Predicate<ToolCall> inputValidator;
    private final Predicate<ToolContinuationCall> continuationValidator;
    private final BiPredicate<ToolCall, TypedPayload> outputValidator;
    private final HttpClient http;
    private final Clock clock;
    private final int maximumResponseBytes;
    private final McpInvocationContextSource invocationContext;

    /** schema guard验证原冻结schema，authorize核当前IAM与真实原派发事实。 */
    public McpToolPortal(BindingSource bindings, Function<String, byte[]> decrypt,
            Predicate<ToolCall> authorize,
            Predicate<ToolContinuationCall> authorizeContinuation,
            Predicate<ToolCall> inputValidator,
            Predicate<ToolContinuationCall> continuationValidator,
            BiPredicate<ToolCall, TypedPayload> outputValidator, HttpClient http, Clock clock,
            int maximumResponseBytes) {
        this(bindings, decrypt, authorize, authorizeContinuation, inputValidator,
                continuationValidator, outputValidator, http, clock, maximumResponseBytes,
                McpInvocationContextSource.empty());
    }

    public McpToolPortal(BindingSource bindings, Function<String, byte[]> decrypt,
            Predicate<ToolCall> authorize, Predicate<ToolContinuationCall> authorizeContinuation,
            Predicate<ToolCall> inputValidator,
            Predicate<ToolContinuationCall> continuationValidator,
            BiPredicate<ToolCall, TypedPayload> outputValidator, HttpClient http, Clock clock,
            int maximumResponseBytes, McpInvocationContextSource invocationContext) {
        this.bindings = Objects.requireNonNull(bindings); this.decrypt = Objects.requireNonNull(decrypt);
        this.authorize = Objects.requireNonNull(authorize);
        this.authorizeContinuation = Objects.requireNonNull(authorizeContinuation);
        this.inputValidator = Objects.requireNonNull(inputValidator);
        this.continuationValidator = Objects.requireNonNull(continuationValidator);
        this.outputValidator = Objects.requireNonNull(outputValidator); this.http = Objects.requireNonNull(http);
        this.clock = Objects.requireNonNull(clock);
        if (maximumResponseBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("mcp_transport_configuration_invalid");
        this.maximumResponseBytes = maximumResponseBytes;
        this.invocationContext = Objects.requireNonNull(invocationContext);
    }

    /**
     * 只消费宿主已核对的本次直接调用；当前授权在握手前及真实业务发送前分别检查。
     * 一次性视图不签发发送权、不读取旧许可，不查询冻结绑定；再次调用或替换调用对象均拒绝。
     * observationGuard只核验有界结果封套，冻结业务输出schema由宿主保存原结果后安全采纳。
     */
    public ToolPortal forDispatch(ToolCall registeredCall, Binding frozenBinding,
            BooleanSupplier currentAuthorization, BusinessSendAudit businessSendAudit, Predicate<TypedPayload> observationGuard,
            Optional<TypedPayload> extraParameters) {
        Objects.requireNonNull(registeredCall);
        Objects.requireNonNull(currentAuthorization);
        Objects.requireNonNull(businessSendAudit);
        requireBinding(registeredCall.context(), registeredCall.target(), registeredCall.binding(), frozenBinding);
        var restricted = direct(frozenBinding, call -> call == registeredCall && currentAuthorization.getAsBoolean(),
                call -> false, observationGuard, extraParameters);
        var used = new AtomicBoolean();
        return new ToolPortal() {
            @Override public ToolOutcome invoke(ToolCall call) {
                if (call != registeredCall || !used.compareAndSet(false, true)) throw rejected();
                return restricted.invoke(call, businessSendAudit);
            }
            @Override public ToolOutcome resume(ToolContinuationCall call) { throw rejected(); }
        };
    }

    /** 原逻辑Action的新Attempt只允许一次resume；原响应和opaque state绑定同一调用对象。 */
    public ToolPortal forContinuation(ToolContinuationCall registeredCall, Binding frozenBinding,
            BooleanSupplier currentAuthorization, BusinessSendAudit businessSendAudit, Predicate<TypedPayload> observationGuard,
            Optional<TypedPayload> extraParameters) {
        Objects.requireNonNull(registeredCall);
        Objects.requireNonNull(currentAuthorization);
        Objects.requireNonNull(businessSendAudit);
        requireBinding(registeredCall.context(), registeredCall.target(), registeredCall.binding(), frozenBinding);
        var restricted = direct(frozenBinding, call -> false,
                call -> call == registeredCall && currentAuthorization.getAsBoolean(), observationGuard, extraParameters);
        var used = new AtomicBoolean();
        return new ToolPortal() {
            @Override public ToolOutcome invoke(ToolCall call) { throw rejected(); }
            @Override public ToolOutcome resume(ToolContinuationCall call) {
                if (call != registeredCall || !used.compareAndSet(false, true)) throw rejected();
                return restricted.resume(call, businessSendAudit);
            }
        };
    }

    /** 本次视图只替换来源和授权边界，仍使用真实schema guard、HTTP与协议实现。 */
    private McpToolPortal direct(Binding frozenBinding, Predicate<ToolCall> authorized,
            Predicate<ToolContinuationCall> continuationAuthorized, Predicate<TypedPayload> observationGuard,
            Optional<TypedPayload> extraParameters) {
        Objects.requireNonNull(observationGuard); Objects.requireNonNull(extraParameters);
        return new McpToolPortal((tenant, target) -> target.equals(frozenBinding.target())
                        ? Optional.of(frozenBinding) : Optional.empty(), decrypt, authorized, continuationAuthorized,
                inputValidator, continuationValidator, (call, payload) -> observationGuard.test(payload),
                http, clock, maximumResponseBytes, context -> extraParameters);
    }

    /** 只验证直接调用与宿主冻结路由一致；本检查不能代替当前IAM或真实Store登记回执。 */
    private static void requireBinding(ProviderCallContext context, FrozenToolTarget target,
            ToolBinding binding, Binding frozenBinding) {
        Objects.requireNonNull(frozenBinding);
        if (context.dispatch().callKind() != PortalCallKind.TOOL_INVOKE
                || !frozenBinding.target().equals(target) || !frozenBinding.toolBinding().equals(binding)) throw rejected();
    }

    @Override public ToolOutcome invoke(ToolCall call) {
        return invoke(call, NO_AUDIT);
    }

    private ToolOutcome invoke(ToolCall call, BusinessSendAudit businessSendAudit) {
        Objects.requireNonNull(call);
        return invoke(call, authorize, inputValidator,
                (binding, requestId) -> McpProtocol.call(
                        requestId, binding.toolName(), call.arguments(),
                        invocationContext.extraParameters(call.context())), businessSendAudit);
    }

    /** continuation 使用新的 Provider request id；不复用首次 tools/call 的响应或传输会话。 */
    @Override public ToolOutcome resume(ToolContinuationCall continuation) {
        return resume(continuation, NO_AUDIT);
    }

    private ToolOutcome resume(ToolContinuationCall continuation, BusinessSendAudit businessSendAudit) {
        Objects.requireNonNull(continuation);
        var call = new ToolCall(continuation.context(), continuation.binding(), continuation.target(),
                continuation.arguments(), continuation.idempotencyKey());
        return invoke(call,
                ignored -> authorizeContinuation.test(continuation),
                ignored -> continuationValidator.test(continuation),
                (binding, requestId) -> McpProtocol.continueCall(requestId,
                        binding.toolName(), continuation.arguments(),
                        continuation.inputResponses(), continuation.requestState(),
                        invocationContext.extraParameters(continuation.context())), businessSendAudit);
    }

    private ToolOutcome invoke(ToolCall call,
            Predicate<ToolCall> currentAuthorization,
            Predicate<ToolCall> currentInputValidator,
            BusinessRequest request, BusinessSendAudit businessSendAudit) {
        var dispatched = new AtomicBoolean();
        try (var scope = new ExchangeScope(call)) {
            // 只读幂等来自宿主冻结策略；写工具仍须具备稳定 action-key 重放协议。
            if ((call.target().idempotent() && call.target().effect() != ToolEffect.READ_ONLY)
                    || call.context().callKind() != PortalCallKind.TOOL_INVOKE
                    || !currentAuthorization.test(call)
                    || !currentInputValidator.test(call)) throw rejected();
            var binding = binding(call);
            String attemptId = call.context().dispatch().attemptId();
            String requestId = "mcp-tools-call:" + attemptId;
            byte[] business = request.encode(binding, requestId);
            URI endpoint = URI.create(binding.endpoint());
            if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                    || endpoint.getRawQuery() != null
                    || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))) throw rejected();
            String authorization = authorization(binding);
            Optional<String> session = Optional.empty();
            boolean handshake = McpHandshake.VERSION.equals(binding.protocolVersion());
            if (handshake) {
                business = McpHandshake.business(business);
                String initializeId = "mcp-initialize:" + attemptId;
                var initialize = scope.send(endpoint, authorization, null, McpHandshake.initialize(initializeId),
                        "initialize", null, Optional.empty());
                if (!McpHttpResponseReader.successful(initialize.statusCode())) throw rejected();
                McpHandshake.validate(scope.packet(initialize, initializeId), initializeId);
                session = McpHandshake.session(initialize.headers().firstValue("Mcp-Session-Id"));
                var ready = scope.send(endpoint, authorization, null, McpHandshake.initialized(),
                        "notifications/initialized", binding.protocolVersion(), session);
                if (!McpHttpResponseReader.successful(ready.statusCode())) throw rejected();
            }
            // 握手不是授权事实；完成协商后、业务发送前重新验证实时权限。
            if (!currentAuthorization.test(call)) throw rejected();
            scope.requireActive();
            var response = scope.send(endpoint, authorization, binding.toolName(), business, McpProtocol.TOOL_CALL_METHOD,
                    binding.protocolVersion(), session, new BusinessSendAudit() {
                        @Override public void beforeSend() {
                            businessSendAudit.beforeSend();
                            // 开始事务可能阻塞；原 Store 当前授权须在事务返回后再次核验。
                            if (!currentAuthorization.test(call)) throw rejected();
                        }
                        @Override public void sendAttempted() {
                            businessSendAudit.sendAttempted();
                            dispatched.set(true);
                        }
                    });
            int status = response.statusCode();
            if (!McpHttpResponseReader.successful(status)) {
                if (status == 408 || status >= 500) return unknown(call);
                Optional<TypedPayload> fact;
                try {
                    var bytes = McpHttpResponseReader.failureBody(response.body(), maximumResponseBytes, scope::requireActive);
                    fact = Optional.of(McpProtocol.httpFailure(status,
                            response.headers().firstValue("Content-Type").orElse(""), bytes));
                } catch (IOException | RuntimeException unavailable) { fact = Optional.empty(); }
                return new ToolOutcome.Failed(failure(call, "mcp_http_rejected"), fact);
            }
            JsonNode toolResult = McpProtocol.result(scope.packet(response, requestId), requestId);
            if (handshake) toolResult = McpHandshake.result(toolResult);
            if ("input_required".equals(toolResult.path("resultType").asText(null))) {
                var required = McpProtocol.inputRequired(toolResult);
                return new ToolOutcome.InputRequired(required.requests(), required.requestState());
            }
            if (toolResult.path("isError").asBoolean(false))
                return new ToolOutcome.Failed(failure(call, toolErrorReason(call, toolResult)), McpProtocol.failureObservation(toolResult));
            TypedPayload observation = McpProtocol.observation(toolResult);
            if (!outputValidator.test(call, observation)) return unknown(call);
            return new ToolOutcome.Succeeded(observation, resultRef(call), List.of());
        } catch (McpProtocol.RpcError rejected) {
            return new ToolOutcome.Failed(failure(call, "mcp_rpc_rejected"), rejected.comparisonFact());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return dispatched.get() ? unknown(call) : new ToolOutcome.Failed(failure(call, "mcp_pre_dispatch_rejected"));
        }
    }

    @FunctionalInterface
    private interface BusinessRequest {
        byte[] encode(Binding binding, String requestId) throws IOException;
    }

    private Binding binding(ToolCall call) {
        var binding = bindings.find(
                call.context().callContext().runIdentity().owner().sessionRef().tenantId(),
                call.target()).orElseThrow(McpToolPortal::rejected);
        if (!binding.target().equals(call.target()) || !binding.toolBinding().equals(call.binding())) {
            throw rejected();
        }
        return binding;
    }

    private String authorization(Binding binding) throws IOException {
        if (binding.encryptedCredential().isEmpty()) return null;
        byte[] key = decrypt.apply(binding.encryptedCredential().orElseThrow());
        try {
            if (key == null || key.length == 0) throw rejected();
            String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(key)).toString();
            if (value.isBlank() || value.chars().anyMatch(c -> c <= 32 || c >= 127)) throw rejected();
            return "Bearer " + value;
        } finally { if (key != null) Arrays.fill(key, (byte) 0); }
    }

    private static IllegalArgumentException rejected() { return new IllegalArgumentException("mcp_pre_dispatch_rejected"); }
    private ResultRef resultRef(ToolCall call) {
        return new ResultRef("mcp-result:" + call.context().dispatch().attemptId());
    }
    private PortFailure failure(ToolCall call, String reason) { return new PortFailure(reason, RetryMode.NONE, resultRef(call)); }
    private ToolOutcome unknown(ToolCall call) { return new ToolOutcome.OutcomeUnknown(failure(call, "mcp_outcome_unknown")); }

    /** 结构化 status 仅作不可信错误提示；只输出封闭原因码，不授予重试或权限。 */
    private static String toolErrorReason(ToolCall call, JsonNode result) {
        var status = result.path("structuredContent").path("status");
        if (!status.isIntegralNumber() || !status.canConvertToInt()) return "mcp_tool_rejected";
        boolean safeRead = call.target().effect() == ToolEffect.READ_ONLY && call.target().idempotent();
        return switch (status.intValue()) {
            case 401 -> "mcp_tool_authentication_required";
            case 403 -> "mcp_tool_permission_denied";
            case 422 -> safeRead ? "mcp_tool_arguments_invalid" : "mcp_tool_arguments_invalid_after_dispatch";
            case 503 -> "mcp_tool_unavailable";
            case 408, 504 -> safeRead ? "mcp_tool_timeout" : "mcp_tool_rejected";
            case 429 -> safeRead ? "mcp_tool_rate_limited" : "mcp_tool_rejected";
            default -> "mcp_tool_rejected";
        };
    }

    /** 由装配层提供的精确版本 MCP 部署绑定读取，不依赖其存储实现。 */
    @FunctionalInterface
    public interface BindingSource {
        Optional<Binding> find(com.skillforge.runtime.api.TenantId tenantId, FrozenToolTarget target);
    }

    /** transport 消费的冻结 Tool 目标、端点、密文引用与协议身份。 */
    public record Binding(FrozenToolTarget target, ToolBinding toolBinding, String endpoint,
                          Optional<String> encryptedCredential,
                          String toolName, String protocolVersion) {
        public Binding {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(toolBinding, "toolBinding");
            Objects.requireNonNull(encryptedCredential, "encryptedCredential");
            if (toolBinding.providerType() != ToolBinding.ProviderType.MCP
                    || !toolBinding.toolName().equals(toolName)
                    || endpoint == null || endpoint.isBlank() || toolName == null || toolName.isBlank()
                    || !McpHandshake.supported(protocolVersion)
                    || encryptedCredential.filter(String::isBlank).isPresent()) {
                throw new IllegalArgumentException("mcp_binding_invalid");
            }
        }
        @Override public String toString() {
            return "McpBinding[revision=" + target.deploymentRevision() + "]";
        }
    }

    /** 监视总责任窗口，包括响应头之后的阻塞 body；超时关闭当前交换且不启动新交换。 */
    private final class ExchangeScope implements AutoCloseable {
        private final ToolCall call;
        private final Instant end;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<InputStream> body = new AtomicReference<>();
        private volatile CompletableFuture<HttpResponse<InputStream>> future;
        private final Thread watchdog;
        ExchangeScope(ToolCall call) {
            this.call = call;
            Instant timeout = clock.instant().plus(call.target().timeout());
            Instant deadline = call.context().callContext().control().deadlineAt();
            end = timeout.isBefore(deadline) ? timeout : deadline;
            watchdog = Thread.startVirtualThread(() -> {
                try {
                    while (!closed.get()) {
                        if (stopped()) { close(); return; }
                        Thread.sleep(20);
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }
        boolean stopped() {
            return !clock.instant().isBefore(end)
                    || call.context().callContext().control().cancellationSignal().isCancellationRequested();
        }
        void requireActive() { if (closed.get() || stopped()) throw rejected(); }
        HttpResponse<InputStream> send(URI endpoint, String authorization, String toolName,
                byte[] payload, String method, String protocol, Optional<String> session)
                throws InterruptedException, ExecutionException, IOException {
            return send(endpoint, authorization, toolName, payload, method, protocol, session, NO_AUDIT);
        }

        HttpResponse<InputStream> send(URI endpoint, String authorization, String toolName,
                byte[] payload, String method, String protocol, Optional<String> session, BusinessSendAudit audit)
                throws InterruptedException, ExecutionException, IOException {
            closeBody(); requireActive();
            var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.between(clock.instant(), end))
                    .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                    .header("Mcp-Method", method);
            if (protocol != null) builder.header("MCP-Protocol-Version", protocol);
            if (toolName != null) builder.header("Mcp-Name", headerValue(toolName));
            session.ifPresent(value -> builder.header("Mcp-Session-Id", value));
            if (authorization != null) builder.header("Authorization", authorization);
            var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            CompletableFuture<HttpResponse<InputStream>> registeredResponse;
            synchronized (this) {
                requireActive();
                // 请求已构造且业务发送仍获准；宿主必须先提交原 Attempt 的开始事实，握手不触发。
                audit.beforeSend();
                // 开始事务可能跨过截止时间；只有再次确认窗口有效才标记真正发送尝试。
                requireActive();
                audit.sendAttempted();
                future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
                // 完成回调不取 scope 锁；关闭原 Future 时，迟到响应也必须接管并关闭正文。
                registeredResponse = future.thenApply(response -> {
                    body.set(response.body());
                    if (closed.get() || stopped()) closeBody();
                    return response;
                });
            }
            // 等待正文登记完成，而不是只等待原发送 Future，避免先唤醒调用线程的窗口。
            var response = registeredResponse.get();
            if (closed.get() || stopped()) { closeBody(); throw new IOException("mcp_exchange_closed"); }
            return response;
        }
        JsonNode packet(HttpResponse<InputStream> response, String id) throws IOException {
            return McpHttpResponseReader.packet(response, id, maximumResponseBytes, this::requireActive);
        }
        void closeBody() {
            var current = body.getAndSet(null);
            if (current != null) try { current.close(); } catch (IOException ignored) { /* 关闭不改变原结果确定性。 */ }
        }
        @Override public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                var current = future; if (current != null) current.cancel(true);
                closeBody();
                if (watchdog != null && Thread.currentThread() != watchdog) watchdog.interrupt();
            }
        }
    }

    /** MCP header mirror 仅允许无歧义 ASCII；其他名称使用规范 Base64 sentinel。 */
    private static String headerValue(String value) {
        boolean sentinel = value.startsWith("=?base64?") && value.endsWith("?=");
        boolean plain = !sentinel && !value.isEmpty()
                && value.charAt(0) != ' ' && value.charAt(0) != '\t'
                && value.charAt(value.length() - 1) != ' '
                && value.charAt(value.length() - 1) != '\t';
        for (int index = 0; plain && index < value.length(); index++) {
            char character = value.charAt(index);
            plain = character == '\t' || character >= 0x20 && character <= 0x7e;
        }
        if (plain) return value;
        return "=?base64?" + Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
