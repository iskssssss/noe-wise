package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 浏览器连接后台只恢复新执行存储的精确等待；不重建Session来源图或提交内部Inbox阶段。 */
@Component
public final class BrowserConnectionRuntimeBridge {
    private static final String REQUEST_SCHEMA = "skillforge.browser.connection-request.v1";
    private static final String RESPONSE_SCHEMA = "skillforge.browser.connection-response.v1";
    private static final String INPUT_KEY = "browser-connection";
    private final AgentRunBindings bindings;
    private final ExecutionStore store;
    private final AgentRuntimeExecutionGateway gateway;
    private final AgentExecutionCodec codec;
    private final ObjectMapper json;

    /** 当前授权仍由正式绑定和受理身份入口核实；结果和恢复权威归ExecutionStore。 */
    public BrowserConnectionRuntimeBridge(AgentRunBindings bindings, ExecutionStore store,
            AgentRuntimeExecutionGateway gateway, AgentExecutionCodec codec, ObjectMapper json) {
        this.bindings = Objects.requireNonNull(bindings); this.store = Objects.requireNonNull(store);
        this.gateway = Objects.requireNonNull(gateway); this.codec = Objects.requireNonNull(codec); this.json = Objects.requireNonNull(json);
    }

    /** 稳定输入回执优先；等待尚未持久化时保持可观察，不将连接完成误当Run成功。 */
    public Snapshot inspect(String tenant, String runId, String invocation, String requestId) {
        var ref = new RunRef(new TenantId(tenant), runId);
        var binding = bindings.find(ref).orElseThrow(BrowserConnectionRuntimeBridge::rejected);
        return bindings.withContinuationRequester(binding.scope().origin(), () -> {
            bindings.authorize(binding);
            var run = requireRun(binding, ref);
            var accepted = store.response(ref, inputId(requestId));
            if (accepted.isPresent()) {
                var original = pending(run, accepted.orElseThrow());
                if (!original.waiting().action().actionId().equals(invocation)) throw rejected();
                requireRequestId(original.requests().get(INPUT_KEY).params(), REQUEST_SCHEMA, requestId);
                return new Snapshot(true, false, null);
            }
            if (terminal(run)) return new Snapshot(false, true, null);
            if (run.phase() != ExecutionStore.Phase.WAITING || run.waiting().isEmpty()) return new Snapshot(false, false, null);
            var pending = pending(run);
            if (!pending.waiting().action().actionId().equals(invocation)) return new Snapshot(false, false, null);
            requireRequestId(pending.requests().get(INPUT_KEY).params(), REQUEST_SCHEMA, requestId);
            return new Snapshot(false, false, pending);
        });
    }

    /** 原配对请求响应只受理一次；取消连接是工具业务响应，不是Run取消权限。 */
    public void resume(String tenant, String runId, String requestId, ToolWait pending,
            TypedPayload response, Instant receivedAt) {
        Objects.requireNonNull(pending); Objects.requireNonNull(receivedAt);
        var ref = new RunRef(new TenantId(tenant), runId);
        var binding = bindings.find(ref).orElseThrow(BrowserConnectionRuntimeBridge::rejected);
        bindings.withContinuationRequester(binding.scope().origin(), () -> {
            bindings.authorize(binding);
            var run = requireRun(binding, ref);
            var accepted = store.response(ref, inputId(requestId));
            if (accepted.isPresent()) {
                var original = accepted.orElseThrow();
                if (!original.reference().equals(pending.waiting())) throw rejected();
                var saved = store.material(ref.tenantId(), original.acceptedResponse().orElseThrow().response()).orElseThrow(BrowserConnectionRuntimeBridge::rejected);
                var expected = codec.encodeToolResponses(Map.of(INPUT_KEY, new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE, response)));
                if (!saved.content().equals(expected)) throw rejected();
                return null;
            }
            if (terminal(run) || run.phase() != ExecutionStore.Phase.WAITING || !pending.equals(pending(run))) throw rejected();
            requireRequestId(response, RESPONSE_SCHEMA, requestId);
            requireRequestId(pending.requests().get(INPUT_KEY).params(), REQUEST_SCHEMA, requestId);
            var id = inputId(requestId);
            gateway.resume(run, gateway.verify(run, id), id, pending.waiting(), pending.runRevision(),
                    Map.of(INPUT_KEY, new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE, response)));
            return null;
        });
    }

    /** 公开等待只读取原INPUT_REQUIRED结果中的请求，私有requestState始终留在原结果材料。 */
    private ToolWait pending(ExecutionStore.Run run) {
        var wait = store.waiting(run.identity().runRef(), run.waiting().orElseThrow()).orElseThrow(BrowserConnectionRuntimeBridge::rejected);
        return pending(run, wait);
    }

    /** 已接受的等待也从原Attempt验证连接请求，稳定InputId本身不能代替业务归属。 */
    private ToolWait pending(ExecutionStore.Run run, ExecutionStore.Waiting wait) {
        var attempt = store.attempt(run.identity().runRef(), wait.sourceAttempt()).orElseThrow(BrowserConnectionRuntimeBridge::rejected);
        if (!attempt.action().equals(wait.reference().action()) || attempt.resultKind().orElse(null) != ExecutionStore.ResultKind.INPUT_REQUIRED) throw rejected();
        var result = store.material(run.identity().runRef().tenantId(), attempt.result().orElseThrow()).orElseThrow(BrowserConnectionRuntimeBridge::rejected);
        var actual = codec.decodeToolOutcome(result.content());
        if (!(actual instanceof ToolOutcome.InputRequired required) || !required.inputRequests().keySet().equals(Set.of(INPUT_KEY))
                || required.inputRequests().get(INPUT_KEY).method() != ToolInputMethod.ELICITATION_CREATE) throw rejected();
        return new ToolWait(run.identity().runRef(), run.revision(), wait.reference(), required.inputRequests());
    }

    /** 绑定、结构化Run和调用目标必须属于相同owner和执行者。 */
    private ExecutionStore.Run requireRun(AgentRunBindings.Binding binding, RunRef ref) {
        var run = store.load(ref).orElseThrow(BrowserConnectionRuntimeBridge::rejected);
        if (!run.identity().owner().equals(binding.owner()) || !run.identity().agentId().equals(binding.actor())) throw rejected();
        return run;
    }

    /** 正文仅校验准确连接请求，不从device选择器建立可信授权。 */
    private void requireRequestId(TypedPayload payload, String schema, String requestId) {
        if (payload == null || !schema.equals(payload.schemaId())) throw rejected();
        try {
            var node = json.readTree(payload.canonicalJson());
            if (node == null || !node.isObject() || !node.path("connection_request_id").isTextual()
                    || !requestId.equals(node.path("connection_request_id").textValue())) throw rejected();
        } catch (java.io.IOException malformed) { throw rejected(); }
    }

    /** 每个持久配对请求只有一个稳定响应输入，不因后台重启更换。 */
    private static InputId inputId(String requestId) { return new InputId("browser-connection:" + Objects.requireNonNull(requestId)); }
    /** 结构化phase为终态或取消时只保留连接事实，不再恢复。 */
    private static boolean terminal(ExecutionStore.Run run) { return run.cancelled() || run.phase() == ExecutionStore.Phase.FINISHED; }
    /** 陈旧或错配事实在写入前拒绝。 */
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("browser_connection_runtime_source_rejected"); }

    /** @param run 原运行 @param runRevision 原等待提交版本 @param waiting 原Action等待代数 @param requests 已保存安全请求，不含opaque state */
    public record ToolWait(RunRef run, long runRevision, ExecutionStore.WaitRef waiting, Map<String, ToolInputRequest> requests) {
        public ToolWait { Objects.requireNonNull(run); Objects.requireNonNull(waiting); requests = Map.copyOf(requests); }
    }
    /** @param resumeAccepted 稳定响应已经受理 @param terminal 原Run已结束 @param pending 精确等待，尚未持久时为空 */
    public record Snapshot(boolean resumeAccepted, boolean terminal, ToolWait pending) { }
}
