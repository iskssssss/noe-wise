package com.skillforge.platform.models;

import com.skillforge.core.knowledge.PlatformKnowledgeModelProbe;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.ModelProviderCatalogView;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderRuntime;
import com.skillforge.runtime.api.port.ModelPort;
import java.time.Duration;
import com.skillforge.core.model.ModelCompatibilityReport.Check;
import com.skillforge.core.model.ModelCompatibilityReport.Status;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 平台显式模型发现与最小连接探测；复用已装配的 SDK Provider，结果不回显供应商正文。 */
@Service
public class PlatformModelProbeService {
    private final PlatformModelCatalogService models;
    private final CryptoService crypto;
    private final PlatformKnowledgeModelProbe knowledge;
    private final ModelProviderCatalog catalog;
    private final ModelProviderRuntime runtime;

    public PlatformModelProbeService(PlatformModelCatalogService models, CryptoService crypto,
            PlatformKnowledgeModelProbe knowledge, ModelProviderCatalog catalog, ModelProviderRuntime runtime) {
        this.models = models;
        this.crypto = crypto;
        this.knowledge = knowledge;
        this.catalog = catalog;
        this.runtime = runtime;
    }

    public ModelProviderCatalogView providers() {
        return ModelProviderCatalogView.from(catalog);
    }

    /** 本地查询 SDK 的正式推理选项，不需要凭据，不调用供应商。 */
    public ReasoningOptionsView reasoningOptions(ReasoningSelection request) {
        try {
            var options = catalog.reasoningOptions(new ModelProviderCatalog.Selection(
                    request.providerId(), request.serviceId(), request.profileVersion(), request.modelId(),
                    ModelProviderCatalog.ModelProtocol.fromId(request.protocol()),
                    ModelProviderCatalog.AuthenticationScheme.fromId(request.authenticationScheme()), request.endpoint()));
            return new ReasoningOptionsView(options.options().stream().map(Enum::name).toList(),
                    options.source().name(), options.sourceUrl(), catalog.imageSupport(request.providerId(),
                            request.serviceId(), ModelProviderCatalog.ModelProtocol.fromId(request.protocol()), request.modelId()));
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "model_provider_profile_invalid");
        }
    }

    public DiscoveryView discover(Discovery request) {
        try {
            var profile = catalog.resolveDiscovery(new ModelProviderCatalog.DiscoverySelection(
                    request.providerId(), request.serviceId(), request.profileVersion(),
                    null, request.endpoint()));
            String credential = request.apiKey();
            if (credential == null || credential.isBlank()) {
                if (request.id() == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                        "model_discovery_credential_required", "model_discovery_credential_required", false);
                var saved = models.draftForProbe(request.id());
                if (!Objects.equals(request.expectedRevision(), saved.revision()))
                    throw new ApiException(HttpStatus.CONFLICT, "model_revision_conflict");
                if (!Objects.equals(saved.providerId(), request.providerId())
                        || !Objects.equals(saved.serviceId(), request.serviceId())
                        || !Objects.equals(saved.endpoint(), profile.endpoint())
                        || !Objects.equals(saved.authenticationScheme(), request.authenticationScheme()))
                    throw new ApiException(HttpStatus.BAD_REQUEST, "model_discovery_credential_scope_changed",
                            "model_discovery_credential_scope_changed", false);
                credential = crypto.decrypt(saved.apiKeyEncrypted());
            }
            var values = runtime.discover(profile.providerId(), new ModelProvider.DiscoveryRequest(
                    profile, credential, Duration.ofSeconds(15)));
            return new DiscoveryView(values.stream().map(model -> new DiscoveredModel(model.modelId(), model.metadata().orElse(null))).toList());
        } catch (ApiException rejected) {
            throw rejected;
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "model_discovery_configuration_invalid", "model_discovery_configuration_invalid", false);
        } catch (Exception failure) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "model_discovery_failed", "model_discovery_failed", false);
        }
    }

    public ProbeResult test(String id) {
        return test(id, null);
    }

    /** payload 存在时测试临时输入，结果只返回调用方，不附到已保存草稿。 */
    public ProbeResult test(String id, ModelWrite payload) {
        if (id == null && payload == null) throw new ApiException(HttpStatus.BAD_REQUEST, "model_probe_configuration_required");
        var config = payload == null ? models.draftForProbe(id) : models.previewForProbe(id, payload);
        if (!config.kind().equals("LLM")) {
            var result = knowledge.test(config);
            return new ProbeResult(result.ok(), result.latencyMs(), result.error(), result.dimensions(), null,
                    result.ranking(), result.usage());
        }
        var report = testLlm(config);
        if (payload == null) models.saveCompatibilityReport(id, config.revision(), report);
        return new ProbeResult(report.status() == com.skillforge.core.model.ModelCompatibilityReport.Status.PASSED,
                report.durationMs(), report.reasonCode(), null, report);
    }

    private com.skillforge.core.model.ModelCompatibilityReport testLlm(com.skillforge.core.model.PlatformModelConfig config) {
        long started = System.nanoTime();
        var testedAt = java.time.Instant.now();
        var notRun = check(Status.NOT_RUN, 0, null);
        var acceptance = notRun;
        var streaming = notRun;
        var continuation = notRun;
        try {
            models.validateReasoning(config);
            var tool = new ModelProvider.ToolDefinition("compatibility_echo", "Return the supplied value using the local echo fixture.",
                    new com.skillforge.runtime.api.TypedPayload("json-schema", """
                            {"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}
                            """), false, ModelPort.ModelToolKind.TOOL_CALL, false);
            var messages = new java.util.ArrayList<ModelProvider.Message>();
            messages.add(new ModelProvider.Message(ModelProvider.Role.USER,
                    "Call compatibility_echo exactly once with value probe_echo. After the tool returns, reply with exactly its returned text and no further tools.", null, List.of()));
            long firstStarted = System.nanoTime();
            var first = stream(config, messages, List.of(tool), ModelProvider.ToolChoice.AUTO);
            if (first.status() != Status.PASSED) {
                acceptance = first.result() == null ? check(first.status(), elapsed(firstStarted), first.reason())
                        : check(Status.PASSED, elapsed(firstStarted), "configuration_request_accepted");
                streaming = check(first.status(), elapsed(firstStarted), first.reason());
                return report(config, testedAt, started, first.status(), acceptance, streaming, continuation, first.reason());
            }
            acceptance = check(Status.PASSED, elapsed(firstStarted), "configuration_request_accepted");
            streaming = check(Status.PASSED, elapsed(firstStarted), "stream_completed_with_deltas");
            long toolStarted = System.nanoTime();
            var result = first.result();
            if (!"tool_calls".equals(result.finishReasonCode()) || result.toolCalls().size() != 1)
                return toolFailure(config, testedAt, started, acceptance, streaming, toolStarted, "model_probe_tool_call_missing");
            var call = result.toolCalls().getFirst();
            var args = new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.argumentsJson());
            if (!"compatibility_echo".equals(call.name()) || args == null || !args.isObject()
                    || args.size() != 1 || !args.path("value").isTextual() || !"probe_echo".equals(args.path("value").textValue()))
                return toolFailure(config, testedAt, started, acceptance, streaming, toolStarted, "model_probe_tool_arguments_invalid");
            // 原样续接原生块、签名和推理内容，不由宿主重造供应商状态。
            List<com.skillforge.runtime.api.media.ModelContent> contents = result.content() == null || result.content().isEmpty()
                    ? List.of() : List.of(new com.skillforge.runtime.api.media.ModelContent.Text(result.content()));
            messages.add(new ModelProvider.Message(ModelProvider.Role.ASSISTANT, contents, null,
                    result.toolCalls(), result.reasoningContent(), result.protocolState()));
            messages.add(new ModelProvider.Message(ModelProvider.Role.TOOL, "echo:probe_echo", call.callId(), List.of()));
            var second = stream(config, messages, List.of(tool), ModelProvider.ToolChoice.NONE);
            if (second.status() != Status.PASSED) {
                continuation = check(second.status(), elapsed(toolStarted), second.reason());
                return report(config, testedAt, started, second.status(), acceptance, streaming, continuation, second.reason());
            }
            if (!"stop".equals(second.result().finishReasonCode()) || !second.result().toolCalls().isEmpty()
                    || second.result().content() == null || !"echo:probe_echo".equals(second.result().content().strip()))
                return toolFailure(config, testedAt, started, acceptance, streaming, toolStarted, "model_probe_tool_continuation_invalid");
            continuation = check(Status.PASSED, elapsed(toolStarted), "native_tool_continuation_completed");
            return report(config, testedAt, started, Status.PASSED, acceptance, streaming, continuation, null);
        } catch (Exception failure) {
            // 异常正文可能包含凭据、正文或协议块，管理 API 仅返回固定分类。
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            var status = failure instanceof IllegalArgumentException || failure instanceof ApiException ? Status.FAILED : Status.UNKNOWN;
            String reason = status == Status.FAILED ? "model_probe_configuration_invalid" : "model_probe_outcome_unknown";
            if (acceptance.status() == Status.NOT_RUN) acceptance = check(status, elapsed(started), reason);
            else continuation = check(status, elapsed(started), reason);
            return report(config, testedAt, started, status, acceptance, streaming, continuation, reason);
        }
    }

    private StreamRound stream(com.skillforge.core.model.PlatformModelConfig config, List<ModelProvider.Message> messages,
            List<ModelProvider.ToolDefinition> tools, ModelProvider.ToolChoice choice) throws InterruptedException {
        Thread caller = Thread.currentThread();
        var invocation = new ModelProvider.InvocationRequest(config.endpoint(), crypto.decrypt(config.apiKeyEncrypted()), config.modelId(),
                new ModelProvider.InvocationProfile(config.serviceId(), config.profileVersion(),
                        ModelProviderCatalog.ModelProtocol.fromId(config.protocol()),
                        ModelProviderCatalog.AuthenticationScheme.fromId(config.authenticationScheme()), config.dialectVersion()),
                List.copyOf(messages), new ModelPort.ResponseContract(new ModelPort.ResponseFormat.Text(),
                        ModelPort.EnforcementLevel.BEST_EFFORT, "platform_model_compatibility"),
                new ModelPort.GenerationOptions(OptionalInt.of(config.maxOutputTokens()), config.temperature() == null
                        ? OptionalDouble.empty() : OptionalDouble.of(config.temperature())),
                ModelPort.ReasoningPolicy.valueOf(config.reasoning()), tools, choice,
                Duration.ofSeconds(config.timeoutSeconds()), caller::isInterrupted, () -> { })
                .withPrefixCapabilities(config.prefixCapabilities());
        var probe = new StreamProbe();
        try {
            runtime.stream(config.providerId(), invocation).subscribe(probe);
            if (!probe.done.await(config.timeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS))
                return new StreamRound(Status.UNKNOWN, null, "model_probe_stream_timeout");
            if (probe.failed || probe.terminals != 1 || !probe.closed)
                return new StreamRound(Status.UNKNOWN, null, "model_probe_stream_incomplete");
            if (probe.terminal instanceof ModelProvider.StreamEvent.Rejected)
                return new StreamRound(Status.FAILED, null, "model_probe_request_rejected");
            if (probe.terminal instanceof ModelProvider.StreamEvent.OutcomeUnknown)
                return new StreamRound(Status.UNKNOWN, null, "model_probe_outcome_unknown");
            var result = ((ModelProvider.StreamEvent.Completed) probe.terminal).result();
            if ("length".equals(result.finishReasonCode())) return new StreamRound(Status.FAILED, result, "model_output_length");
            if ("content_filter".equals(result.finishReasonCode())) return new StreamRound(Status.FAILED, result, "model_content_filtered");
            if (!probe.sawDelta || !probe.content.toString().equals(result.content() == null ? "" : result.content())
                    || !probe.reasoning.toString().equals(result.reasoningContent() == null ? "" : result.reasoningContent()))
                return new StreamRound(Status.FAILED, result, "model_probe_stream_deltas_invalid");
            for (int index = 0; index < result.toolCalls().size(); index++) {
                var call = result.toolCalls().get(index);
                var deltas = probe.toolDeltas.get(index);
                if (deltas == null || deltas.isEmpty()) return new StreamRound(Status.FAILED, result, "model_probe_tool_deltas_missing");
                var arguments = new StringBuilder();
                for (var delta : deltas) {
                    if (delta.callId() != null && !delta.callId().isEmpty() && !delta.callId().equals(call.callId())
                            || delta.name() != null && !delta.name().isEmpty() && !delta.name().equals(call.name()))
                        return new StreamRound(Status.FAILED, result, "model_probe_tool_delta_identity_invalid");
                    if (delta.argumentsDelta() != null) arguments.append(delta.argumentsDelta());
                }
                try {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    if (!json.readTree(arguments.toString()).equals(json.readTree(call.argumentsJson())))
                        return new StreamRound(Status.FAILED, result, "model_probe_tool_deltas_invalid");
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    return new StreamRound(Status.FAILED, result, "model_probe_tool_deltas_invalid");
                }
            }
            return new StreamRound(Status.PASSED, result, null);
        } finally {
            probe.cancel();
        }
    }

    private static final class StreamProbe implements java.util.concurrent.Flow.Subscriber<ModelProvider.StreamEvent> {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final java.util.Map<Integer, java.util.List<ModelProvider.StreamEvent.ToolCallDelta>> toolDeltas = new java.util.HashMap<>();
        volatile java.util.concurrent.Flow.Subscription subscription;
        volatile boolean cancellationRequested;
        void cancel() { cancellationRequested = true; var current = subscription; if (current != null) current.cancel(); }
        ModelProvider.StreamEvent terminal;
        int terminals;
        boolean failed, closed, sawDelta;
        int characters, events;
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; if (cancellationRequested) value.cancel(); else value.request(Long.MAX_VALUE); }
        @Override public void onNext(ModelProvider.StreamEvent event) {
            if (++events > 100000 || terminals > 0) { failed = true; subscription.cancel(); done.countDown(); return; }
            if (event instanceof ModelProvider.StreamEvent.ContentDelta delta) { content.append(delta.content()); sawDelta = true; characters += delta.content().length(); }
            else if (event instanceof ModelProvider.StreamEvent.ReasoningDelta delta) { reasoning.append(delta.content()); sawDelta = true; characters += delta.content().length(); }
            else if (event instanceof ModelProvider.StreamEvent.ToolCallDelta delta) {
                toolDeltas.computeIfAbsent(delta.index(), ignored -> new java.util.ArrayList<>()).add(delta); sawDelta = true;
                characters += delta.argumentsDelta() == null ? 0 : delta.argumentsDelta().length();
            } else if (event instanceof ModelProvider.StreamEvent.Completed || event instanceof ModelProvider.StreamEvent.Rejected
                    || event instanceof ModelProvider.StreamEvent.OutcomeUnknown) { terminal = event; terminals++; }
            if (characters > 2 * 1024 * 1024) { failed = true; subscription.cancel(); done.countDown(); }
        }
        @Override public void onError(Throwable failure) { failed = true; done.countDown(); }
        @Override public void onComplete() { closed = true; done.countDown(); }
    }
    private record StreamRound(Status status, ModelProvider.InvocationResult.Completed result, String reason) {}
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static Check check(Status status, long duration, String reason) { return new Check(status, duration, reason); }
    private static com.skillforge.core.model.ModelCompatibilityReport report(com.skillforge.core.model.PlatformModelConfig config,
            java.time.Instant testedAt, long started, Status status, Check acceptance, Check streaming, Check continuation, String reason) {
        return new com.skillforge.core.model.ModelCompatibilityReport(config.revision(), testedAt, status, acceptance, streaming, continuation, elapsed(started), reason);
    }
    private static com.skillforge.core.model.ModelCompatibilityReport toolFailure(com.skillforge.core.model.PlatformModelConfig config,
            java.time.Instant testedAt, long started, Check acceptance, Check streaming, long toolStarted, String reason) {
        return report(config, testedAt, started, Status.FAILED, acceptance, streaming, check(Status.FAILED, elapsed(toolStarted), reason), reason);
    }

    public record ReasoningSelection(
            @com.fasterxml.jackson.annotation.JsonProperty("provider_id") String providerId,
            @com.fasterxml.jackson.annotation.JsonProperty("service_id") String serviceId,
            @com.fasterxml.jackson.annotation.JsonProperty("profile_version") String profileVersion,
            String protocol,
            @com.fasterxml.jackson.annotation.JsonProperty("authentication_scheme") String authenticationScheme,
            String endpoint,
            @com.fasterxml.jackson.annotation.JsonProperty("model_id") String modelId) {}
    public record ReasoningOptionsView(List<String> options, String source,
            @com.fasterxml.jackson.annotation.JsonProperty("source_url") String sourceUrl,
            @com.fasterxml.jackson.annotation.JsonProperty("image_support") com.skillforge.runtime.adapter.model.provider.ModelImageSupport imageSupport) {
        public ReasoningOptionsView(List<String> options, String source, String sourceUrl) {
            this(options, source, sourceUrl, null);
        }
        public ReasoningOptionsView { options = List.copyOf(options); }
    }
    public record Discovery(String providerId, String serviceId, String profileVersion, String protocol,
            String authenticationScheme, String endpoint, String apiKey, String id, Long expectedRevision) {
        public Discovery(String providerId, String serviceId, String profileVersion, String protocol,
                String authenticationScheme, String endpoint, String apiKey) {
            this(providerId, serviceId, profileVersion, protocol, authenticationScheme, endpoint, apiKey, null, null);
        }
        @Override public String toString() { return "ModelDiscovery"; }
    }
    public record DiscoveredModel(String modelId, com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryMetadata metadata) {
        public DiscoveredModel(String modelId) { this(modelId, null); }
    }
    public record DiscoveryView(List<DiscoveredModel> models) {}
    public record ProbeResult(boolean ok, long latencyMs, String error, Integer dimensions,
            com.skillforge.core.model.ModelCompatibilityReport compatibilityReport,
            List<PlatformKnowledgeModelProbe.Ranking> ranking, PlatformKnowledgeModelProbe.Usage usage) {
        public ProbeResult(boolean ok, long latencyMs, String error, Integer dimensions) {
            this(ok, latencyMs, error, dimensions, null, null, null);
        }
        public ProbeResult(boolean ok, long latencyMs, String error, Integer dimensions,
                com.skillforge.core.model.ModelCompatibilityReport compatibilityReport) {
            this(ok, latencyMs, error, dimensions, compatibilityReport, null, null);
        }
    }
}
