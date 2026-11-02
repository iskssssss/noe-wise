package com.skillforge.runtime.desktop;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ProviderCallContext;
import com.skillforge.runtime.api.port.ProviderDispatch;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 只消费 Store 的真实派发回执；模型和工具不另建调度循环或取得恢复重发权。 */
final class DesktopCapabilities implements ExecutionCoordinator.Capabilities {
    static final String COMMAND_CONFIRMATION = "desktop-command-confirmation";
    private final DesktopRuntime host;
    private final ExecutionStore store;
    private final DesktopModels models;
    private final AgentExecutionCodec codec;
    private final CoreRuntime core;
    private final CoreRuntime.PortalResults portals;

    DesktopCapabilities(DesktopRuntime host, ExecutionStore store, DesktopModels models, AgentExecutionCodec codec, CoreRuntime core) {
        this.host = host; this.store = store; this.models = models; this.codec = codec; this.core = core;
        var payloads = new SessionRuntimePayloads(); portals = core.portalResults(payloads, payloads, payloads);
    }

    @Override public ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
        host.confirmActivity(registered.run());
        if (prepared.kind() == ExecutionStore.Kind.MODEL) return model(registered, prepared);
        if (prepared.kind() == ExecutionStore.Kind.TOOL) return tool(registered, prepared);
        throw new IllegalStateException("desktop_capability_not_available");
    }

    private ExecutionCoordinator.CapabilityResult model(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
        var run = registered.run(); var request = codec.decodeModelRequest(prepared).request();
        var settings = DesktopModels.Settings.read(host.frozen(run).required("model"));
        var callKind = request.target().capabilities().streaming() ? PortalCallKind.MODEL_STREAM : PortalCallKind.MODEL_GENERATE;
        var dispatch = new ProviderDispatch(run.identity().runRef(), prepared.action(), prepared.action().actionId(), registered.attempt().id(),
                callKind, prepared.request().reference(), prepared.capability(), prepared.fingerprint(), registered.attempt().fence());
        var context = ProviderCallContext.dispatched(new PortCallContext(run.identity(), registered.actor(),
                new InvocationControl(host.instant().plus(request.target().timeout()), () -> host.cancelled(run))), dispatch);
        var call = new ModelPort.ModelCall(context, request.requestedStrategy(), request.target(), prepared.request().reference(), request.messages(), request.tools(),
                request.toolChoice(), request.response(), request.generation(), request.reasoning(), request.budgetCategory());
        // authorizeSend 会在真实调用前核原 Action/Attempt、fence、取消和预算发送权。
        var port = models.port(settings, request.target(),
                candidate -> candidate == call && !host.cancelled(run) && store.authorizeSend(registered, prepared),
                delta -> host.modelPreview(run, registered.attempt().id(), delta));
        var actual = callKind == PortalCallKind.MODEL_STREAM ? stream(port.stream(call), call.target().timeout(), () -> host.cancelled(run), delta -> { }) : port.generate(call);
        var payload = codec.encodeOutcome(core.encodeOutcome(callKind, actual));
        var kind = actual instanceof ModelPort.ModelResult.Completed ? ExecutionStore.ResultKind.SUCCEEDED
                : actual instanceof ModelPort.ModelResult.Failed ? ExecutionStore.ResultKind.FAILED : ExecutionStore.ResultKind.UNKNOWN;
        var usage = actual instanceof ModelPort.ModelResult.Completed completed
                ? ExecutionBudgetPort.Usage.reported(completed.usage().inputTokens(), completed.usage().outputTokens()) : ExecutionBudgetPort.Usage.unreported();
        return new ExecutionCoordinator.CapabilityResult(kind, new ExecutionStore.Material(AgentExecutionCodec.reference(payload), payload),
                Optional.empty(), Optional.empty(), usage);
    }

    /** 预览没有提交权；只把适配器的实际终态交给原回执链。 */
    static ModelPort.ModelResult stream(java.util.concurrent.Flow.Publisher<ModelPort.ModelStreamEvent> publisher, java.time.Duration timeout, java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<String> preview) {
        var result = new java.util.concurrent.CompletableFuture<ModelPort.ModelResult>();
        var subscription = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription.set(value); value.request(Long.MAX_VALUE); }
            public void onNext(ModelPort.ModelStreamEvent event) {
                switch (event) {
                    case ModelPort.ModelStreamEvent.ContentDelta delta -> preview.accept(delta.content());
                    case ModelPort.ModelStreamEvent.Completed completed -> result.complete(completed.result());
                    case ModelPort.ModelStreamEvent.Failed failed -> result.complete(new ModelPort.ModelResult.Failed(failed.failure(), failed.retryAt()));
                    case ModelPort.ModelStreamEvent.OutcomeUnknown unknown -> result.complete(new ModelPort.ModelResult.OutcomeUnknown(unknown.failure()));
                    default -> { }
                }
            }
            public void onError(Throwable failure) { result.completeExceptionally(failure); }
            public void onComplete() { if (!result.isDone()) result.completeExceptionally(new IllegalStateException("desktop_stream_terminal_missing")); }
        });
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new IllegalStateException("desktop_stream_cancelled");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("desktop_stream_deadline_exceeded");
                try { return result.get(Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)), java.util.concurrent.TimeUnit.NANOSECONDS); }
                catch (java.util.concurrent.TimeoutException pending) { /* 观察同一调用的取消与期限，不重发。 */ }
            }
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("desktop_stream_interrupted", interrupted); }
        catch (java.util.concurrent.ExecutionException failure) { throw new IllegalStateException("desktop_stream_unresolved", failure); }
        finally { if (subscription.get() != null) subscription.get().cancel(); }
    }

    private ExecutionCoordinator.CapabilityResult tool(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
        var run = registered.run(); var request = codec.decodeTool(prepared.request().content());
        SessionRuntimePayloads.validateAgainstSchema(request.target().inputSchema(), request.arguments(), DesktopRuntime.CODEC_LIMITS);
        var name = DesktopConfiguration.toolName(host.frozen(run), request);
        if (!store.authorizeSend(registered, prepared) || host.cancelled(run)) return toolResult(request,
                new ToolOutcome.Failed(failure("CANCELLED_BEFORE_DISPATCH", prepared)), ExecutionStore.ResultKind.FAILED);
        if (name.equals("run_command") && request.continuation().isEmpty()) {
            var params = DesktopJson.object().put("title", "确认执行本机命令")
                    .put("command", DesktopJson.parse(request.arguments().canonicalJson()).required("command").textValue())
                    .put("workspace", host.frozen(run).required("workspace").textValue());
            var input = new ToolOutcome.InputRequired(Map.of(COMMAND_CONFIRMATION, new ToolPortal.ToolInputRequest(
                    ToolPortal.ToolInputMethod.ELICITATION_CREATE, new TypedPayload("skillforge.runtime.tool.mcp-input-request.v1", DesktopJson.write(params)))),
                    Optional.of(prepared.action().actionId()));
            return toolResult(request, input, ExecutionStore.ResultKind.INPUT_REQUIRED);
        }
        boolean approved = false;
        if (name.equals("run_command")) {
            var continuation = request.continuation().orElseThrow();
            var response = continuation.responses().get(COMMAND_CONFIRMATION);
            if (response == null || response.method() != ToolPortal.ToolInputMethod.ELICITATION_CREATE
                    || !continuation.requestState().equals(Optional.of(prepared.action().actionId()))) throw new IllegalArgumentException("desktop_command_response_invalid");
            var confirmation = DesktopJson.parse(response.result().canonicalJson());
            if (!confirmation.path("approved").isBoolean()) throw new IllegalArgumentException("desktop_command_response_invalid");
            approved = confirmation.required("approved").booleanValue();
            if (!approved) return toolResult(request, new ToolOutcome.Failed(failure("COMMAND_REJECTED", prepared)), ExecutionStore.ResultKind.FAILED);
        }
        final DesktopTools.Result executed;
        host.toolStarted(run, prepared.action().actionId());
        try { executed = name.equals("read_context_reference")
                ? DesktopContextReferences.read(store, codec, run, request.arguments(), () -> host.cancelled(run))
                : host.tools(run).execute(name, request.arguments().canonicalJson(), prepared.action().actionId(), approved, () -> host.cancelled(run)); }
        finally { host.toolStopped(run, prepared.action().actionId()); }
        var actual = executed;
        var body = DesktopJson.parse(actual.contentJson());
        boolean completedCommandFailure = name.equals("run_command") && actual.status() == DesktopTools.Status.FAILED
                && body.path("exitCode").isIntegralNumber() && body.path("exitCode").longValue() != 0
                && body.path("stdout").isTextual() && body.path("stderr").isTextual() && body.path("processTreeStopped").asBoolean();
        if (completedCommandFailure) {
            var observation = (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy();
            observation.put("status", "FAILED").put("isError", true);
            actual = new DesktopTools.Result(actual.status(), DesktopJson.write(observation));
        }
        var receipt = host.saveToolReceipt(registered, actual);
        final ToolOutcome outcome; final ExecutionStore.ResultKind kind;
        switch (actual.status()) {
            case SUCCEEDED -> {
                var observation = DesktopJson.parse(actual.contentJson());
                if (name.equals("write_file") || name.equals("edit_file")) {
                    // 完整修改已保存到receipt；模型只消费有界确认，避免把每次before/after再次灌入历史。
                    var summary = DesktopJson.object().put("path", body.required("path").textValue())
                            .put("version", body.required("version").textValue())
                            .put("bytes", body.required("after").textValue().getBytes(StandardCharsets.UTF_8).length);
                    if (body.has("created")) summary.set("created", body.required("created"));
                    observation = summary;
                }
                var envelope = DesktopJson.object(); envelope.putArray("content"); envelope.set("structuredContent", observation);
                outcome = new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, DesktopJson.write(envelope)), receipt, List.of());
                kind = ExecutionStore.ResultKind.SUCCEEDED;
            }
            case FAILED -> {
                if (completedCommandFailure) {
                    // Handler返回准确观察，不表示命令/测试通过；失败状态和诊断进入模型与UI的同一结果。
                    var envelope = DesktopJson.object().put("isError", true); envelope.putArray("content");
                    envelope.set("structuredContent", DesktopJson.parse(actual.contentJson()));
                    outcome = new ToolOutcome.Succeeded(new TypedPayload(SessionRuntimePayloads.TOOL_RESULT_SCHEMA, DesktopJson.write(envelope)), receipt, List.of());
                    kind = ExecutionStore.ResultKind.SUCCEEDED;
                } else {
                    var code = body.path("error").path("code").asText(body.path("code").asText("TOOL_FAILED"));
                    outcome = new ToolOutcome.Failed(new PortFailure(code, RetryMode.NONE, receipt)); kind = ExecutionStore.ResultKind.FAILED;
                }
            }
            case UNKNOWN -> {
                outcome = new ToolOutcome.OutcomeUnknown(new PortFailure("TOOL_OUTCOME_UNKNOWN", RetryMode.NONE, receipt)); kind = ExecutionStore.ResultKind.UNKNOWN;
            }
            default -> throw new IllegalStateException("desktop_tool_status_invalid");
        }
        return toolResult(request, outcome, kind);
    }

    private ExecutionCoordinator.CapabilityResult toolResult(AgentExecutionCodec.ToolRequest request, ToolOutcome outcome, ExecutionStore.ResultKind kind) {
        var encoded = codec.encodeToolOutcome(portals, outcome);
        var payload = encoded.payload();
        return new ExecutionCoordinator.CapabilityResult(kind, new ExecutionStore.Material(AgentExecutionCodec.reference(payload), payload),
                codec.safeToolFeedback(request, encoded.outcome()).map(value -> new ExecutionStore.Material(AgentExecutionCodec.reference(value), value)),
                Optional.empty(), ExecutionBudgetPort.Usage.unreported());
    }

    private static PortFailure failure(String code, ExecutionStore.Prepared prepared) {
        return new PortFailure(code, RetryMode.NONE, prepared.request().reference());
    }
}
