package com.skillforge.runtime.adapter.model;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 精确版本的 OpenAI-compatible 调用；只消费已登记派发，不签发或重复消费调用权。 */
public final class OpenAiModelPort implements ModelPort, AutoCloseable {
    private static final System.Logger log = System.getLogger(OpenAiModelPort.class.getName());
    private final BindingSource bindings;
    private final Function<String, byte[]> decrypt;
    private final Predicate<ModelCall> authorize;
    private final ModelCall registeredCall;
    private final BiPredicate<ModelCall, TypedPayload> outputValidator;
    private final Clock clock;
    private final ModelProviderRuntime providers;
    private boolean ownsProviders;
    private final ModelInvocationAudit invocationAudit;
    private final MediaPort media;
    private final Consumer<String> bodyPreview;

    /** outputValidator 须实现宿主已注册结果 schema，尤其不能省略 STRICT JSON_SCHEMA 验证。 */
    public OpenAiModelPort(BindingSource bindings, Function<String, byte[]> decrypt, Predicate<ModelCall> authorize,
            BiPredicate<ModelCall, TypedPayload> outputValidator, HttpClient http, Clock clock, int maximumResponseBytes) {
        this(bindings, decrypt, authorize, outputValidator, http, clock,
                maximumResponseBytes,
                ModelProviderRuntime.runtime(http, clock, maximumResponseBytes),
                ModelInvocationAudit.noop());
        this.ownsProviders = true;
    }

    /** 允许宿主显式装配供应商集合；协议传输与供应商实现保持独立。 */
    public OpenAiModelPort(BindingSource bindings, Function<String, byte[]> decrypt,
            Predicate<ModelCall> authorize,
            BiPredicate<ModelCall, TypedPayload> outputValidator, HttpClient http,
            Clock clock, int maximumResponseBytes, ModelProviderRuntime providers) {
        this(bindings, decrypt, authorize, outputValidator, http, clock,
                maximumResponseBytes, providers, ModelInvocationAudit.noop());
    }

    /** 正文预览仅供展示，不改变正式流过滤、发送授权或终态审计。 */
    public OpenAiModelPort(BindingSource bindings, Function<String, byte[]> decrypt,
            Predicate<ModelCall> authorize, BiPredicate<ModelCall, TypedPayload> outputValidator,
            HttpClient http, Clock clock, int maximumResponseBytes, ModelProviderRuntime providers,
            Consumer<String> bodyPreview) {
        this(bindings, decrypt, authorize, outputValidator, http, clock, maximumResponseBytes,
                providers, ModelInvocationAudit.noop(), new MediaPort() {
                    public byte[] read(com.skillforge.runtime.api.SessionRef owner, MediaRef ref) { throw rejected(); }
                    public MediaRef prepare(com.skillforge.runtime.api.SessionRef owner, MediaRef ref,
                            com.skillforge.runtime.api.media.ModelImagePolicy policy) { throw rejected(); }
                }, bodyPreview);
    }

    /** 宿主可装配发送前 attempt 守卫以及确定结果后的计量审计。 */
    public OpenAiModelPort(BindingSource bindings, Function<String, byte[]> decrypt,
            Predicate<ModelCall> authorize,
            BiPredicate<ModelCall, TypedPayload> outputValidator, HttpClient http,
            Clock clock, int maximumResponseBytes, ModelProviderRuntime providers,
            ModelInvocationAudit invocationAudit) {
        this(bindings, decrypt, authorize, outputValidator, http, clock, maximumResponseBytes,
                providers, invocationAudit, new MediaPort() {
                    public byte[] read(com.skillforge.runtime.api.SessionRef owner, MediaRef ref) { throw rejected(); }
                    public MediaRef prepare(com.skillforge.runtime.api.SessionRef owner, MediaRef ref,
                            com.skillforge.runtime.api.media.ModelImagePolicy policy) { throw rejected(); }
                });
    }

    /** 可信媒体仅在供应商准备发送时读取；不重写已冻结的引用或图片变体。 */
    public OpenAiModelPort(BindingSource bindings, Function<String, byte[]> decrypt,
            Predicate<ModelCall> authorize, BiPredicate<ModelCall, TypedPayload> outputValidator,
            HttpClient http, Clock clock, int maximumResponseBytes, ModelProviderRuntime providers,
            ModelInvocationAudit invocationAudit, MediaPort media) {
        this(bindings, decrypt, authorize, outputValidator, http, clock, maximumResponseBytes,
                providers, invocationAudit, media, content -> { });
    }

    /** 装配展示预览时仍复用同一授权、媒体和审计边界，预览不能替代完整结果。 */
    public OpenAiModelPort(BindingSource bindings, Function<String, byte[]> decrypt,
            Predicate<ModelCall> authorize, BiPredicate<ModelCall, TypedPayload> outputValidator,
            HttpClient http, Clock clock, int maximumResponseBytes, ModelProviderRuntime providers,
            ModelInvocationAudit invocationAudit, MediaPort media, Consumer<String> bodyPreview) {
        this.bindings = Objects.requireNonNull(bindings); this.decrypt = Objects.requireNonNull(decrypt);
        this.authorize = Objects.requireNonNull(authorize); this.outputValidator = Objects.requireNonNull(outputValidator);
        Objects.requireNonNull(http); this.clock = Objects.requireNonNull(clock);
        if (maximumResponseBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("model_transport_configuration_invalid");
        this.providers = Objects.requireNonNull(providers);
        this.invocationAudit = Objects.requireNonNull(invocationAudit);
        this.media = Objects.requireNonNull(media);
        this.bodyPreview = Objects.requireNonNull(bodyPreview);
        this.registeredCall = null;
    }

    /**
     * 为一次已登记的直接派发建立受限视图，复用原 Provider transport、HTTP 连接池和媒体能力。
     *
     * <p>宿主必须先核对真实 Store Registered/actor/请求材料，并传入绑定该回执的受限审计实例；
     * 本方法不授予权限，也不重新执行旧 IAM/permit 链。发送仍须通过该实例的 audit.start，
     * 标准 noop 审计不允许使用此入口。视图只接受同一个不可变 registeredCall 对象，
     * 使用边界已读取的完整 frozenBinding，不再次查配置或按缺值回退。</p>
     */
    public OpenAiModelPort forDispatch(ModelCall registeredCall, Binding frozenBinding, ModelInvocationAudit scopedAudit) {
        return forDispatch(registeredCall, frozenBinding, scopedAudit, content -> { });
    }

    /** 单次派发的非权威正文观察；不改变 ModelPort 流、聚合结果或调用次数。宿主负责安全过滤。 */
    public OpenAiModelPort forDispatch(ModelCall registeredCall, Binding frozenBinding, ModelInvocationAudit scopedAudit,
            Consumer<String> bodyPreview) {
        Objects.requireNonNull(bodyPreview);
        Objects.requireNonNull(registeredCall); Objects.requireNonNull(frozenBinding); Objects.requireNonNull(scopedAudit);
        var dispatch = registeredCall.context().dispatch();
        if (scopedAudit == ModelInvocationAudit.noop()
                || dispatch.callKind() != PortalCallKind.MODEL_GENERATE && dispatch.callKind() != PortalCallKind.MODEL_STREAM
                || !dispatch.requestRef().equals(registeredCall.invocationSnapshotRef())
                || !frozenBinding.target().equals(registeredCall.target())
                || !frozenBinding.strategy().equals(registeredCall.requestedStrategy()))
            throw new IllegalArgumentException("model_registered_dispatch_view_rejected");
        return new OpenAiModelPort(this, registeredCall, frozenBinding, scopedAudit, bodyPreview);
    }

    /** 已验证单调用视图仅替换请求来源与审计，所有真实协议和 transport 对象保持同一实例。 */
    private OpenAiModelPort(OpenAiModelPort original, ModelCall registeredCall, Binding frozenBinding,
                            ModelInvocationAudit scopedAudit, Consumer<String> bodyPreview) {
        this.bindings = reference -> reference.equals(frozenBinding.target().binding()) ? Optional.of(frozenBinding) : Optional.empty();
        this.authorize = candidate -> candidate == registeredCall;
        this.registeredCall = registeredCall;
        this.decrypt = original.decrypt;
        this.outputValidator = original.outputValidator;
        this.clock = original.clock;
        this.providers = original.providers;
        this.media = original.media;
        this.invocationAudit = scopedAudit;
        this.bodyPreview = bodyPreview;
    }

    /** 仅便捷构造器创建的 Runtime 由此实例拥有；受限派发视图及注入集合由装配关闭。 */
    @Override public void close() { if (ownsProviders) providers.close(); }

    @Override public ModelResult generate(ModelCall call) {
        Objects.requireNonNull(call);
        long started = System.nanoTime();
        long prepared = started, headers = started, received = started;
        String phase = "prepare";
        ModelInvocationAudit.Provider auditedProvider = null;
        boolean auditStarted = false;
        boolean terminalAuditAttempted = false;
        try {
            ProviderRequest preparedRequest;
            try { preparedRequest = providerRequest(call, false); }
            catch (Exception denied) { return new ModelResult.Failed(failure(call, "model_pre_dispatch_rejected")); }
            prepared = System.nanoTime();
            phase = "headers";
            headers = System.nanoTime();
            phase = "body";
            ModelInvocationAudit.Provider provider = provider(preparedRequest);
            if (!invocationAudit.start(call, provider)) {
                return new ModelResult.OutcomeUnknown(
                        failure(call, "model_attempt_already_started"));
            }
            auditedProvider = provider;
            auditStarted = true;
            var response = providers.generate(
                    preparedRequest.providerId(), preparedRequest.request());
            received = System.nanoTime();
            phase = "decode";
            if (response instanceof ModelProvider.InvocationResult.Rejected rejected) {
                reportUsage(call, provider, 1, rejected.usage());
                terminalAuditAttempted = true;
                invocationAudit.failed(call, provider, rejected.reasonCode());
                return new ModelResult.Failed(failure(call, rejected.reasonCode()), rejected.retryAt());
            }
            if (response instanceof ModelProvider.InvocationResult.OutcomeUnknown unknown) {
                reportUsage(call, provider, 1, unknown.usage());
                terminalAuditAttempted = true;
                invocationAudit.unresolved(call, provider, unknown.reasonCode());
                return new ModelResult.OutcomeUnknown(failure(call, unknown.reasonCode()));
            }
            var completed = (ModelProvider.InvocationResult.Completed) response;
            reportUsage(call, provider, 1, completed.usage());
            if (!limitedFinish(completed.finishReasonCode()) && disabledToolResponse(call, completed)) {
                terminalAuditAttempted = true;
                invocationAudit.failed(call, provider, "model_output_schema_rejected");
                return new ModelResult.Failed(failure(call, "model_output_schema_rejected"));
            }
            ModelResult.Completed result;
            boolean accepted;
            try {
                String content = governedContent(call, completed.content(), limitedFinish(completed.finishReasonCode())
                        && !(call.response().format() instanceof ResponseFormat.NativeConversation) ? List.of() : completed.toolCalls());
                result = OpenAiModelProtocol.completed(call, content,
                        completed.finishReasonCode(), completed.usage(), resultRef(call),
                        Optional.ofNullable(completed.reasoningContent()),
                        assistantTurn(call, completed), completed.protocolState());
                accepted = terminalWithoutUsableOutput(result) || outputValidator.test(call, result.output());
            } catch (IOException | RuntimeException invalidResponse) {
                terminalAuditAttempted = true;
                if (call.response().format() instanceof ResponseFormat.NativeConversation) {
                    invocationAudit.failed(call, provider, "model_output_schema_rejected");
                    return new ModelResult.Failed(failure(call, "model_output_schema_rejected"));
                }
                invocationAudit.unresolved(call, provider, "model_outcome_unknown");
                return new ModelResult.OutcomeUnknown(
                        failure(call, "model_outcome_unknown"));
            }
            terminalAuditAttempted = true;
            if (accepted) {
                invocationAudit.succeeded(call, provider, result.usage());
                return result;
            }
            invocationAudit.failed(call, provider, "model_output_schema_rejected");
            return new ModelResult.Failed(failure(call, "model_output_schema_rejected"));
        } catch (RuntimeException auditFailure) {
            if (auditStarted && !terminalAuditAttempted) {
                try {
                    invocationAudit.unresolved(call, auditedProvider, "model_outcome_unknown");
                } catch (RuntimeException ignored) {
                    // 原 attempt 已无法确定；保持 Runtime 的 unknown 语义。
                }
            }
            return new ModelResult.OutcomeUnknown(failure(call, "model_outcome_unknown"));
        } finally {
            // 单条摘要只包含计时和执行阶段，不输出请求、响应或凭据；失败耗时归属当前阶段。
            long finished = System.nanoTime();
            if (phase.equals("prepare")) prepared = finished;
            if (phase.equals("prepare") || phase.equals("headers")) headers = finished;
            if (!phase.equals("decode")) received = finished;
            log.log(System.Logger.Level.DEBUG,
                    "模型调用分段耗时 phase={0} prepareMs={1} headersMs={2} bodyMs={3} decodeMs={4} totalMs={5}",
                    phase, (prepared - started) / 1_000_000,
                    (headers - prepared) / 1_000_000, (received - headers) / 1_000_000,
                    (finished - received) / 1_000_000, (finished - started) / 1_000_000);
        }
    }

    /** 冷的单订阅流；逐事件响应需求，关闭连接后只保留一个待交付终态。 */
    @Override public Flow.Publisher<ModelStreamEvent> stream(ModelCall call) {
        Objects.requireNonNull(call);
        AtomicBoolean subscribed = new AtomicBoolean();
        return subscriber -> {
            Objects.requireNonNull(subscriber);
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long count) { }
                    @Override public void cancel() { }
                });
                subscriber.onError(new IllegalStateException(
                        "model_stream_already_subscribed"));
                return;
            }
            DeferredProviderSubscription subscription =
                    new DeferredProviderSubscription(call, subscriber);
            subscriber.onSubscribe(subscription);
            // 准备及拒绝回调可同步进入权限/持久化端口，避免 Java 21 监视器阻塞载体线程。
            Thread.ofPlatform().daemon(true).name("model-provider-prepare").start(subscription::start);
        };
    }

    private final class DeferredProviderSubscription implements Flow.Subscription,
            Flow.Subscriber<ModelProvider.StreamEvent> {
        private final ModelCall call;
        private final Flow.Subscriber<? super ModelStreamEvent> downstream;
        private Flow.Subscription upstream;
        private long pendingDemand;
        private long downstreamDemand;
        private ModelStreamEvent pendingTerminal;
        private boolean previewUnavailable;
        private ProviderRequest prepared;
        private ModelInvocationAudit.Provider provider;
        private boolean auditStarted;
        private boolean auditFinished;
        private boolean cancelled;
        private boolean terminated;
        /** 供应商 usage 是同一 attempt 的累计快照；相同快照不重复成为计量事实。 */
        private long usageRevision;
        private ModelUsage lastReportedUsage = OpenAiModelProtocol.emptyUsage();
        /** 仅对实际交给 Runtime 的增量编号；供应商被过滤的分块不能留下序号空洞。 */
        private long deliveredDeltaSequence;

        private DeferredProviderSubscription(ModelCall call,
                Flow.Subscriber<? super ModelStreamEvent> downstream) {
            this.call = call;
            this.downstream = downstream;
        }

        private void start() {
            try {
                prepared = providerRequest(call, true);
                provider = provider(prepared);
                synchronized (this) { if (cancelled) return; }
                if (!invocationAudit.start(call, provider)) {
                    finish(new ModelStreamEvent.OutcomeUnknown(
                            failure(call, "model_attempt_already_started")));
                    return;
                }
                auditStarted = true;
                providers.stream(prepared.providerId(), prepared.request()).subscribe(this);
            } catch (Exception denied) {
                if (auditStarted) {
                    unresolvedAudit("model_outcome_unknown");
                    finish(new ModelStreamEvent.OutcomeUnknown(
                            failure(call, "model_outcome_unknown")));
                } else {
                    finish(new ModelStreamEvent.Failed(
                            failure(call, "model_pre_dispatch_rejected")));
                }
            }
        }

        @Override public void request(long count) {
            Flow.Subscription current;
            ModelStreamEvent terminal;
            synchronized (this) {
                if (cancelled || terminated) return;
                if (count <= 0) {
                    cancelled = true;
                    current = upstream;
                    terminal = null;
                } else {
                    downstreamDemand = addDemand(downstreamDemand, count);
                    if (pendingTerminal != null) {
                        terminal = pendingTerminal;
                        pendingTerminal = null;
                        consumeDemand();
                        terminated = true;
                        current = null;
                    } else {
                        terminal = null;
                        current = upstream;
                        if (current == null) {
                            pendingDemand = addDemand(pendingDemand, count);
                        }
                    }
                }
            }
            if (count <= 0) {
                if (current != null) current.cancel();
                unresolvedAudit("model_stream_invalid_demand");
                downstream.onError(new IllegalArgumentException(
                        "model_stream_invalid_demand"));
            } else if (terminal != null) {
                emitTerminal(terminal);
            } else if (current != null) {
                current.request(count);
            }
        }

        @Override public void cancel() {
            Flow.Subscription current;
            synchronized (this) {
                cancelled = true;
                pendingTerminal = null;
                current = upstream;
            }
            if (current != null) current.cancel();
            unresolvedAudit("model_stream_cancelled");
        }

        @Override public void onSubscribe(Flow.Subscription value) {
            long initialDemand;
            synchronized (this) {
                if (cancelled || upstream != null) {
                    value.cancel();
                    return;
                }
                upstream = value;
                initialDemand = pendingDemand;
                pendingDemand = 0;
            }
            if (initialDemand > 0) value.request(initialDemand);
        }

        @Override public void onNext(ModelProvider.StreamEvent event) {
            boolean observePreview;
            synchronized (this) { observePreview = !cancelled && !terminated; }
            try {
                if (event instanceof ModelProvider.StreamEvent.UsageDelta usage) {
                    reportUsageSnapshot(usage.usage());
                }
                if (event instanceof ModelProvider.StreamEvent.Completed completed) {
                    // 完整帧可能是唯一的 usage 来源；先持久其计量，再做会抛错的输出治理与 schema 校验。
                    reportUsageSnapshot(completed.result().usage());
                }
            } catch (RuntimeException auditFailure) {
                log.log(System.Logger.Level.WARNING,
                        "模型流用量审计失败 exception={0}", auditFailure.getClass().getSimpleName());
                Flow.Subscription current;
                synchronized (this) { current = upstream; }
                if (current != null) current.cancel();
                unresolvedAudit("model_outcome_unknown");
                finish(new ModelStreamEvent.OutcomeUnknown(failure(call, "model_outcome_unknown")));
                return;
            }
            try {
                // 只观察正文，reasoning/工具参数仍留在私有流；投影故障不改写已外调的模型事实。
                if (observePreview && !previewUnavailable && event instanceof ModelProvider.StreamEvent.ContentDelta delta) {
                    try { bodyPreview.accept(delta.content()); }
                    catch (RuntimeException unavailable) {
                        previewUnavailable = true;
                        log.log(System.Logger.Level.WARNING, "model_body_preview_unavailable attempt={0}", call.context().dispatch().attemptId());
                    }
                }
                ModelStreamEvent mapped = mapStreamEvent(call, event);
                if (mapped == null) {
                    // 过滤的供应商片段不消耗下游需求，补回上游需求以继续读取最终结果。
                    Flow.Subscription current;
                    synchronized (this) {
                        current = cancelled || terminated ? null : upstream;
                    }
                    if (current != null) current.request(1);
                    return;
                }
                if (terminal(mapped)) {
                    mapped = completeAuditOrDowngrade(mapped);
                }
                Flow.Subscription invalidUpstream = null;
                synchronized (this) {
                    if (cancelled || terminated) return;
                    if (downstreamDemand == 0) {
                        cancelled = true;
                        invalidUpstream = upstream;
                    } else {
                        consumeDemand();
                    }
                    if (invalidUpstream == null && terminal(mapped)) {
                        terminated = true;
                    }
                    if (invalidUpstream == null) mapped = renumberDeliveredDelta(mapped);
                }
                if (invalidUpstream != null) {
                    invalidUpstream.cancel();
                    unresolvedAudit("model_stream_backpressure_violated");
                    downstream.onError(new IllegalStateException(
                            "model_stream_backpressure_violated"));
                    return;
                }
                downstream.onNext(mapped);
            } catch (RuntimeException | IOException invalid) {
                log.log(System.Logger.Level.WARNING,
                        "模型流结果转换失败 event={0} exception={1} location={2}",
                        event.getClass().getSimpleName(), invalid.getClass().getSimpleName(),
                        invalid.getStackTrace().length == 0 ? "unknown" : invalid.getStackTrace()[0]);
                Flow.Subscription current;
                synchronized (this) { current = upstream; }
                if (current != null) current.cancel();
                if (event instanceof ModelProvider.StreamEvent.Completed
                        && call.response().format() instanceof ResponseFormat.NativeConversation) {
                    finish(completeAuditOrDowngrade(new ModelStreamEvent.Failed(
                            failure(call, "model_output_schema_rejected"))));
                    return;
                }
                unresolvedAudit("model_outcome_unknown");
                finish(new ModelStreamEvent.OutcomeUnknown(
                        failure(call, "model_outcome_unknown")));
            }
        }

        @Override public void onError(Throwable failure) {
            unresolvedAudit("model_outcome_unknown");
            finish(new ModelStreamEvent.OutcomeUnknown(
                    OpenAiModelPort.failure(call, "model_outcome_unknown")));
        }

        @Override public void onComplete() {
            boolean complete;
            synchronized (this) {
                if (cancelled) return;
                complete = terminated;
            }
            if (complete) downstream.onComplete();
            else {
                unresolvedAudit("model_outcome_unknown");
                finish(new ModelStreamEvent.OutcomeUnknown(
                        failure(call, "model_outcome_unknown")));
            }
        }

        private void finishAudit(ModelStreamEvent event) {
            if (!auditStarted || auditFinished) return;
            try {
                if (event instanceof ModelStreamEvent.Completed completed) {
                    reportUsageSnapshot(completed.result().usage());
                    invocationAudit.succeeded(call, provider, completed.result().usage());
                } else if (event instanceof ModelStreamEvent.Failed failed) {
                    invocationAudit.failed(call, provider, failed.failure().reasonCode());
                } else if (event instanceof ModelStreamEvent.OutcomeUnknown unknown) {
                    invocationAudit.unresolved(call, provider, unknown.failure().reasonCode());
                } else {
                    return;
                }
            } finally {
                // 宿主可能已接受本次终态后才抛错；不再对同一 attempt 追加冲突的审计终态。
                auditFinished = true;
            }
        }

        /** 终态先完成审计；审计不可用时交付明确的 unknown，不能吞掉已消费的终态需求。 */
        private ModelStreamEvent completeAuditOrDowngrade(ModelStreamEvent terminal) {
            try {
                finishAudit(terminal);
                return terminal;
            } catch (RuntimeException auditFailure) {
                log.log(System.Logger.Level.WARNING,
                        "模型流终态审计失败 exception={0}", auditFailure.getClass().getSimpleName());
                return new ModelStreamEvent.OutcomeUnknown(
                        failure(call, "model_outcome_unknown"));
            }
        }

        private static boolean terminal(ModelStreamEvent event) {
            return event instanceof ModelStreamEvent.Completed
                    || event instanceof ModelStreamEvent.Failed
                    || event instanceof ModelStreamEvent.OutcomeUnknown;
        }

        /** 只在获得新快照时发出 revision，避免流式收尾复报最后一份完整 usage。 */
        private void reportUsageSnapshot(ModelUsage usage) {
            if (!OpenAiModelProtocol.hasReportedTokens(usage) || usage.equals(lastReportedUsage)) return;
            long revision = Math.addExact(usageRevision, 1);
            invocationAudit.usageReported(call, provider, revision, usage);
            usageRevision = revision;
            lastReportedUsage = usage;
        }

        private void unresolvedAudit(String reasonCode) {
            if (!auditStarted || auditFinished) return;
            try {
                invocationAudit.unresolved(call, provider, reasonCode);
            } catch (RuntimeException auditFailure) {
                log.log(System.Logger.Level.WARNING,
                        "模型流未知审计不可用 exception={0}", auditFailure.getClass().getSimpleName());
            } finally {
                auditFinished = true;
            }
        }

        private void finish(ModelStreamEvent terminal) {
            synchronized (this) {
                if (cancelled || terminated) return;
                if (downstreamDemand == 0) {
                    pendingTerminal = terminal;
                    return;
                }
                consumeDemand();
                terminated = true;
            }
            emitTerminal(terminal);
        }

        private void emitTerminal(ModelStreamEvent terminal) {
            try {
                downstream.onNext(terminal);
                synchronized (this) { if (cancelled) return; }
                downstream.onComplete();
            } catch (RuntimeException rejectedBySubscriber) {
                cancel();
            }
        }

        private void consumeDemand() {
            if (downstreamDemand != Long.MAX_VALUE) downstreamDemand--;
        }

        /** Core 按已投递事件校验连续 sequence，不能复用包含私有或过滤分块的供应商序号。 */
        private ModelStreamEvent renumberDeliveredDelta(ModelStreamEvent event) {
            long sequence = deliveredDeltaSequence + 1;
            if (event instanceof ModelStreamEvent.ContentDelta delta) {
                deliveredDeltaSequence = sequence;
                return new ModelStreamEvent.ContentDelta(sequence, delta.content());
            }
            if (event instanceof ModelStreamEvent.ReasoningDelta delta) {
                deliveredDeltaSequence = sequence;
                return new ModelStreamEvent.ReasoningDelta(sequence, delta.content());
            }
            if (event instanceof ModelStreamEvent.ToolCallDelta delta) {
                deliveredDeltaSequence = sequence;
                return new ModelStreamEvent.ToolCallDelta(sequence, delta.index(), delta.callId(),
                        delta.toolHandle(), delta.argumentsDelta());
            }
            if (event instanceof ModelStreamEvent.UsageDelta delta) {
                deliveredDeltaSequence = sequence;
                return new ModelStreamEvent.UsageDelta(sequence, delta.usage());
            }
            return event;
        }

        private static long addDemand(long current, long added) {
            return current > Long.MAX_VALUE - added ? Long.MAX_VALUE : current + added;
        }
    }

    /** 非流式响应只有一份最终快照；未知 usage 不伪造为零或报告事件。 */
    private void reportUsage(ModelCall call, ModelInvocationAudit.Provider provider,
            long revision, ModelUsage usage) {
        if (OpenAiModelProtocol.hasReportedTokens(usage)) {
            invocationAudit.usageReported(call, provider, revision, usage);
        }
    }

    private ModelStreamEvent mapStreamEvent(
            ModelCall call, ModelProvider.StreamEvent event) throws IOException {
        if (event instanceof ModelProvider.StreamEvent.ContentDelta delta) {
            // 第一批只提交完整校验后的原生正文，暂不把候选增量公开为已交付回答。
            if (call.response().format() instanceof ResponseFormat.NativeConversation) return null;
            return new ModelStreamEvent.ContentDelta(delta.sequence(), delta.content());
        }
        if (event instanceof ModelProvider.StreamEvent.ReasoningDelta delta) {
            return new ModelStreamEvent.ReasoningDelta(delta.sequence(), delta.content());
        }
        if (event instanceof ModelProvider.StreamEvent.UsageDelta delta) {
            return new ModelStreamEvent.UsageDelta(delta.sequence(), delta.usage());
        }
        if (event instanceof ModelProvider.StreamEvent.ToolCallDelta delta) {
            var matches = call.tools().stream()
                    .filter(tool -> tool.wireName().equals(delta.name())).toList();
            if (matches.size() != 1 || call.toolChoice() == ToolChoice.NONE) {
                // 禁用调用或未绑定的增量不释放执行句柄；原生完整事实随后交 Session 判定。
                return null;
            }
            return new ModelStreamEvent.ToolCallDelta(delta.sequence(), delta.index(),
                    delta.callId(), matches.getFirst().toolHandle(), delta.argumentsDelta());
        }
        if (event instanceof ModelProvider.StreamEvent.Completed completed) {
            var raw = completed.result();
            if (!limitedFinish(raw.finishReasonCode()) && disabledToolResponse(call, raw)) {
                // 结构化路径的工具禁令在适配边界拒绝；原生响应事实交给 Session 治理。
                return new ModelStreamEvent.Failed(failure(call, "model_output_schema_rejected"));
            }
            String content = governedContent(call, raw.content(), limitedFinish(raw.finishReasonCode())
                    && !(call.response().format() instanceof ResponseFormat.NativeConversation) ? List.of() : raw.toolCalls());
            var result = OpenAiModelProtocol.completed(call, content,
                    completed.result().finishReasonCode(), completed.result().usage(),
                    resultRef(call), Optional.ofNullable(completed.result().reasoningContent()),
                    assistantTurn(call, completed.result()), completed.result().protocolState());
            return terminalWithoutUsableOutput(result) || outputValidator.test(call, result.output())
                    ? new ModelStreamEvent.Completed(result)
                    : new ModelStreamEvent.Failed(
                            failure(call, "model_output_schema_rejected"));
        }
        if (event instanceof ModelProvider.StreamEvent.Rejected rejected) {
            return new ModelStreamEvent.Failed(failure(call, rejected.reasonCode()), rejected.retryAt());
        }
        if (event instanceof ModelProvider.StreamEvent.OutcomeUnknown unknown) {
            return new ModelStreamEvent.OutcomeUnknown(
                    failure(call, unknown.reasonCode()));
        }
        throw new IllegalArgumentException("model_stream_event_unsupported");
    }

    /** 结构化路径不接受违反 NONE 的工具响应；原生模式由 Session 解释完整事实。 */
    private static boolean disabledToolResponse(ModelCall call, ModelProvider.InvocationResult.Completed raw) {
        return !(call.response().format() instanceof ResponseFormat.NativeConversation)
                && call.toolChoice() == ToolChoice.NONE && !raw.toolCalls().isEmpty();
    }

    /** 原生结果保留供应商调用身份和参数，动作解释归 Session。 */
    private static Optional<AssistantTurn> assistantTurn(ModelCall call,
            ModelProvider.InvocationResult.Completed completed) {
        if (!(call.response().format() instanceof ResponseFormat.NativeConversation)) return Optional.empty();
        var blocks = new java.util.ArrayList<AssistantContent>();
        if (completed.content() != null) blocks.add(new AssistantContent.Text(completed.content()));
        for (var tool : completed.toolCalls()) blocks.add(new AssistantContent.ToolCall(
                tool.callId(), tool.name(), tool.argumentsJson()));
        return Optional.of(new AssistantTurn(blocks));
    }

    private ProviderRequest providerRequest(ModelCall call, boolean streaming) {
        var kind = call.context().callKind();
        if (kind != (streaming ? PortalCallKind.MODEL_STREAM : PortalCallKind.MODEL_GENERATE)
                || stopped(call) || streaming && !call.target().capabilities().streaming()
                || registeredCall != null && call != registeredCall) throw rejected();
        var binding = bindings.find(call.target().binding()).orElseThrow(OpenAiModelPort::rejected);
        if (!binding.target().equals(call.target())
                || !binding.strategy().equals(call.requestedStrategy())
                || call.reasoning() != ReasoningPolicy.DISABLED
                    && !call.target().capabilities().reasoning()) throw rejected();
        var profile = binding.providerProfile();
        var invocationProfile = new ModelProvider.InvocationProfile(profile.serviceId(),
                profile.profileVersion(), profile.protocol(), profile.authenticationScheme(), profile.dialectVersion());
        if (!providers.supportsInvocationProfile(profile.providerId(), invocationProfile)) throw rejected();
        for (var message : call.messages()) if (message.protocolState().isPresent()) {
            var state = message.protocolState().orElseThrow();
            String expected = switch (state.protocol()) {
                case CHAT_COMPLETIONS -> "openai-chat-completions";
                case RESPONSES -> "openai-responses";
                case MESSAGES -> "anthropic-messages";
            };
            if (!expected.equals(invocationProfile.protocol().id()) || !state.dialectVersion().equals(invocationProfile.dialectVersion())
                    || !state.modelId().equals(call.target().effectiveModelId())) throw rejected();
        }
        if (!call.tools().isEmpty() && call.toolChoice() == ToolChoice.NONE
                && (call.target().capabilities().prefixCapabilities().retainToolsWithToolChoiceNone()
                    != CapabilitySupportStatus.SUPPORTED
)) throw rejected();
        byte[] key = decrypt.apply(binding.encryptedCredential());
        try {
            if (key == null || key.length == 0) throw rejected();
            String credential = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(key)).toString();
            Duration remaining = Duration.between(clock.instant(),
                    call.context().callContext().control().deadlineAt());
            Duration timeout = remaining.compareTo(call.target().timeout()) < 0
                    ? remaining : call.target().timeout();
            if (timeout.isNegative() || timeout.isZero() || stopped(call)) throw rejected();
            var request = new ModelProvider.InvocationRequest(
                    binding.endpoint(), credential, call.target().effectiveModelId(),
                    invocationProfile,
                    call.messages().stream().map(message -> new ModelProvider.Message(
                            switch (message.role()) {
                                case SYSTEM -> ModelProvider.Role.SYSTEM;
                                case USER -> ModelProvider.Role.USER;
                                case ASSISTANT -> ModelProvider.Role.ASSISTANT;
                                case TOOL -> ModelProvider.Role.TOOL;
                            }, message.contents(), message.toolCallId().orElse(null),
                            message.toolCalls().stream().map(tool -> new ModelProvider.ToolCall(
                                    tool.callId(), tool.wireName(), tool.arguments().canonicalJson())).toList(),
                            message.reasoningContent().orElse(null), message.protocolState())).toList(),
                    call.response(), call.generation(), call.reasoning(), call.tools().stream()
                            .map(tool -> new ModelProvider.ToolDefinition(tool.wireName(),
                                    tool.description(), tool.inputSchema(), tool.strict(), tool.kind(), tool.batchEligible()))
                            .toList(), providerToolChoice(call.toolChoice()), timeout,
                    () -> stopped(call), mediaReader(call),
                    call.target().capabilities().imagePolicy().enabled()
                            ? call.target().capabilities().imagePolicy().maxRequestBytes() : 16L * 1024 * 1024,
                    Optional.of(ModelPromptCacheScope.create(call.context().callContext().runIdentity(), binding)),
                    call.target().capabilities().prefixCapabilities(), () -> {
                        if (!authorize.test(call)) throw new IllegalArgumentException("model_pre_dispatch_rejected");
                    });
            return new ProviderRequest(profile.providerId(), request);
        } catch (IOException invalidCredential) {
            throw rejected();
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private Function<MediaRef, byte[]> mediaReader(ModelCall call) {
        var owner = call.context().callContext().runIdentity().owner().sessionRef();
        var policy = call.target().capabilities().imagePolicy();
        int count = 0;
        long bytes = 0;
        for (var message : call.messages()) for (var part : message.contents()) {
            if (!(part instanceof ModelContent.Image image)) continue;
            var ref = image.media();
            if (!policy.enabled() || message.role() == ModelRole.TOOL && !policy.toolImages()
                    || !owner.equals(ref.owner()) || ref.byteLength() > policy.maxImageBytes()
                    || ref.width() > policy.maxDimension() || ref.height() > policy.maxDimension()) throw rejected();
            count = Math.addExact(count, 1); bytes = Math.addExact(bytes, ref.byteLength());
        }
        if (count > policy.maxImages() || count > 0 && bytes > policy.maxRequestBytes()) throw rejected();
        var cache = new java.util.HashMap<MediaRef, byte[]>();
        return ref -> cache.computeIfAbsent(ref, value -> {
            if (stopped(call) || !owner.equals(value.owner())) throw rejected();
            byte[] content = media.read(owner, value);
            if (content == null || content.length != value.byteLength()) throw rejected();
            content = content.clone();
            try {
                var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
                if (!hash.equals(value.sha256())) throw rejected();
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            return content;
        });
    }

    private record ProviderRequest(
            String providerId, ModelProvider.InvocationRequest request) { }

    private static ModelInvocationAudit.Provider provider(ProviderRequest request) {
        return new ModelInvocationAudit.Provider(request.providerId(),
                request.request().profile().serviceId(), request.request().modelId());
    }

    private boolean stopped(ModelCall call) {
        var control = call.context().callContext().control();
        return control.cancellationSignal().isCancellationRequested() || !clock.instant().isBefore(control.deadlineAt());
    }
    /** 原生正文原样保留，工具语义和响应可采纳性由 Session 判断。 */
    private static String governedContent(ModelCall call, String content,
            List<ModelProvider.ToolCall> toolCalls) throws IOException {
        if (call.response().format() instanceof ResponseFormat.NativeConversation)
            return content == null ? "" : content;
        if (!toolCalls.isEmpty()) throw new IOException("model_protocol_invalid");
        if (content == null && !(call.response().format() instanceof ResponseFormat.Text)) return "{}";
        return content;
    }

    /** 终止事实不要求通过业务输出 schema，消费者按完成原因拒绝采纳。 */
    private static boolean terminalWithoutUsableOutput(ModelResult.Completed result) {
        return limitedFinish(result.finishReasonCode());
    }

    private static boolean limitedFinish(String finish) {
        return "length".equals(finish) || "content_filter".equals(finish);
    }

    private static ModelProvider.ToolChoice providerToolChoice(ToolChoice choice) {
        return switch (choice) {
            case NONE -> ModelProvider.ToolChoice.NONE;
            case AUTO -> ModelProvider.ToolChoice.AUTO;
            case REQUIRED -> ModelProvider.ToolChoice.REQUIRED;
        };
    }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("model_pre_dispatch_rejected"); }
    private static ResultRef resultRef(ModelCall call) {
        return new ResultRef("model-result:" + call.invocationSnapshotRef().value());
    }
    private static PortFailure failure(ModelCall call, String reason) { return new PortFailure(reason, RetryMode.NONE, resultRef(call)); }

    /** 由装配层提供的精确版本模型绑定读取，不暴露具体存储实现。 */
    @FunctionalInterface
    public interface BindingSource {
        Optional<Binding> find(ModelBindingRef binding);
    }

    /** transport 消费的冻结模型目标、策略、端点与密文引用。 */
    public record Binding(FrozenModelTarget target, RequestedModelStrategy strategy,
                          String endpoint, String encryptedCredential,
                          ProviderProfile providerProfile) {
        public Binding {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(providerProfile, "providerProfile");
            if (endpoint == null || endpoint.isBlank()
                    || encryptedCredential == null || encryptedCredential.isBlank()) {
                throw new IllegalArgumentException("model_binding_invalid");
            }
        }

        @Override public String toString() {
            return "ModelBinding[revision=" + target.binding().revision() + "]";
        }
    }

    /** transport 私有的冻结供应商 profile；不会进入 ModelPort 或 Runtime 快照。 */
    public record ProviderProfile(String providerId, String serviceId,
            String profileVersion, ModelProtocol protocol,
            AuthenticationScheme authenticationScheme, String dialectVersion) {
        public ProviderProfile {
            requireText(providerId); requireText(serviceId); requireText(profileVersion);
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(authenticationScheme, "authenticationScheme");
            requireText(dialectVersion);
        }

        private static void requireText(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("model_provider_profile_invalid");
            }
        }
    }

}
