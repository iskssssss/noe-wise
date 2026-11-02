package com.skillforge.institution.execution.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.capability.connector.MediaToolDefinitions;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.media.ModelMediaInput;
import com.skillforge.core.model.media.ModelMediaJob;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.core.model.media.ModelMediaMeteringSource;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactMetadata;
import com.skillforge.institution.artifact.ArtifactPublicInspection;
import com.skillforge.institution.artifact.ArtifactClosingBudget;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.RunIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** 单次受治理媒体创建或原任务查询，模型参数不提供账号、模型配置或操作身份。 */
public final class ModelMediaToolHandler implements PlatformToolHandler {
    private static final TypeReference<Map<String, Object>> RESULT = new TypeReference<>() { };
    private final String name;
    private final MediaToolModelBindings.Binding binding;
    private final ModelMediaJobService jobs;
    private final PlatformModelCatalogService models;
    private final DeliverableService outputs;
    private final ModelMediaArtifactPublisher publisher;
    private final ObjectMapper json;
    private final Function<RunIdentity, RunIdentity> resourceOrigin;

    public ModelMediaToolHandler(String name, MediaToolModelBindings.Binding binding,
            ModelMediaJobService jobs, PlatformModelCatalogService models, DeliverableService outputs,
            ModelMediaArtifactPublisher publisher, ObjectMapper json, Function<RunIdentity, RunIdentity> resourceOrigin) {
        if (!List.of(MediaToolDefinitions.GENERATE_IMAGE, MediaToolDefinitions.GENERATE_VIDEO,
                MediaToolDefinitions.TRANSCRIBE_AUDIO, MediaToolDefinitions.SYNTHESIZE_SPEECH, MediaToolDefinitions.QUERY_JOB).contains(name))
            throw new IllegalArgumentException("media_tool_name_invalid");
        this.name = name; this.binding = binding; this.jobs = jobs; this.models = models;
        this.outputs = outputs; this.publisher = publisher;
        this.resourceOrigin = java.util.Objects.requireNonNull(resourceOrigin);
        this.json = json.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
    @Override public String toolName() { return name; }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, PlatformToolExecutionContext context) throws Exception {
        if (!(context.run().owner().subject() instanceof SessionSubject.User user))
            return rejected("model_media_user_session_required");
        if (stopped(context))
            return rejected("model_media_call_cancelled");
        var owner = new ModelMediaJob.Owner(context.run().runRef().tenantId().value(),
                Long.toString(user.endUserId().value()), context.run().owner().sessionRef().sessionId());
        if (!name.equals(MediaToolDefinitions.QUERY_JOB) && binding == null)
            return rejected("model_media_tool_binding_required");

        Object parsed;
        try {
            if (arguments == null) throw new IllegalArgumentException("media_arguments_required");
            parsed = switch (name) {
                case MediaToolDefinitions.GENERATE_IMAGE -> json.convertValue(arguments, MediaToolDefinitions.ImageArguments.class);
                case MediaToolDefinitions.GENERATE_VIDEO -> json.convertValue(arguments, MediaToolDefinitions.VideoArguments.class);
                case MediaToolDefinitions.TRANSCRIBE_AUDIO -> json.convertValue(arguments, MediaToolDefinitions.TranscriptionArguments.class);
                case MediaToolDefinitions.SYNTHESIZE_SPEECH -> json.convertValue(arguments, MediaToolDefinitions.SpeechArguments.class);
                case MediaToolDefinitions.QUERY_JOB -> json.convertValue(arguments, MediaToolDefinitions.QueryArguments.class);
                default -> throw new IllegalStateException("media_tool_name_invalid");
            };
        } catch (IllegalArgumentException invalid) { return rejected("model_media_arguments_invalid"); }

        ModelMediaJob job;
        try {
            // 媒体生成前先检查真实Run是否仍允许收集成果，避免付费后才发现收尾已关闭。
            try { outputs.admitCollection(context.run()); }
            catch (IllegalArgumentException unavailable) {
                // 收尾账本的确定预算拒绝保留正式原因；其他收集限制不冒充预算不足。
                return rejected(unavailable.getCause() instanceof ExecutionBudgetPort.ClosingInsufficient
                        ? ArtifactClosingBudget.REJECTION_CODE : "model_media_collection_unavailable");
            }
            if (parsed instanceof MediaToolDefinitions.QueryArguments query) {
                job = jobs.query(owner, query.jobId(), context.deadlineAt(),
                        context.cancellationSignal()::isCancellationRequested);
            } else {
                boolean qwenAsr = false;
                if (parsed instanceof MediaToolDefinitions.TranscriptionArguments) {
                    var config = models.readRevision(binding.modelConfigId(), binding.publishedRevision());
                    qwenAsr = "bailian-cn-beijing-qwen-asr".equals(config.serviceId())
                            && ModelProtocol.QWEN_ASR_NATIVE.id().equals(config.protocol());
                    boolean paraformer = "bailian-cn-beijing-asr".equals(config.serviceId())
                            && ModelProtocol.BAILIAN_NATIVE.id().equals(config.protocol());
                    if (!qwenAsr && !paraformer) return rejected("model_media_transcription_binding_invalid");
                }
                if (stopped(context)) return rejected("model_media_call_cancelled");
                ModelMediaInput input;
                try { input = input(parsed, owner, qwenAsr); }
                catch (IllegalArgumentException invalid) { return rejected("model_media_arguments_invalid"); }
                if (stopped(context)) return rejected("model_media_call_cancelled");
                String identity = context.run().runRef().tenantId().value() + "\n" + owner.sessionScope() + "\n"
                        + context.run().runRef().runId() + "\n" + context.invocationId();
                String operation = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
                job = jobs.createPublished(owner, operation, binding.modelConfigId(), binding.publishedRevision(), input,
                        context.deadlineAt(), context.cancellationSignal()::isCancellationRequested, meteringSource(context));
            }
        } catch (ApiException rejected) {
            if (List.of(400, 403, 404, 409, 422).contains(rejected.getStatus().value()))
                return rejected("model_media_request_rejected");
            throw rejected;
        }
        ArtifactMetadata candidate = publisher.publish(owner, job, context);
        return result(job, candidate);
    }

    /** 真实调用来源显式越过工具工作线程，不依赖未传播的计量ThreadLocal。 */
    private ModelMediaMeteringSource meteringSource(PlatformToolExecutionContext context) {
        var run = context.run();
        var origin = resourceOrigin.apply(run);
        var trace = run.trace();
        var causal = new TraceContext(run.causationInputId().value(), trace.rootInputId().value(), trace.traceId(), trace.spanId(),
                context.invocationId(), trace.causedByEventId().map(value -> value.value()).orElse(null));
        var source = CostContext.builder().requestId(run.causationInputId().value()).rootInputId(trace.rootInputId().value())
                .runId(run.runRef().runId()).sessionId(run.owner().sessionRef().sessionId())
                .attributionRunId(origin.runRef().runId())
                .endUserId(run.requester() instanceof RunRequester.EndUser user ? user.endUserId().value() : null)
                .agentId(Long.parseLong(run.agentId().value())).callPoint("agent-runtime.media").traceContext(causal).build();
        return new ModelMediaMeteringSource(source, context.invocationId(), context.attemptId());
    }

    private ModelMediaInput input(Object parsed, ModelMediaJob.Owner owner, boolean qwenAsr) throws IOException {
        return switch (parsed) {
            case MediaToolDefinitions.ImageArguments image -> {
                var sources = new ArrayList<String>();
                if (image.referenceImages() != null)
                    for (var source : image.referenceImages()) sources.add(imageSource(source, owner));
                yield new ModelMediaInput.Image(image.prompt(), sources, image.size(), image.count(),
                        image.negativePrompt(), image.seed(), image.promptExtend(), image.watermark());
            }
            case MediaToolDefinitions.VideoArguments video -> {
                var sources = new ArrayList<DomesticMediaProvider.VideoInput>();
                if (video.media() != null) for (var media : video.media()) {
                    if (media == null || media.source() == null) throw new IllegalArgumentException("media_source_required");
                    sources.add(new DomesticMediaProvider.VideoInput(DomesticMediaProvider.VideoInputKind.valueOf(media.kind()),
                            remoteSource(media.source(), owner)));
                }
                yield new ModelMediaInput.Video(video.prompt(), sources, video.resolution(), video.ratio(), video.durationSeconds(),
                        video.audio(), video.seed(), video.promptExtend(), video.watermark());
            }
            case MediaToolDefinitions.TranscriptionArguments audio -> {
                if (audio.audioSource() == null) throw new IllegalArgumentException("media_source_required");
                if (qwenAsr) {
                    if (audio.channels() != null || audio.languageHints() != null || audio.diarization() != null || audio.speakerCount() != null)
                        throw new IllegalArgumentException("media_qwen_asr_options_invalid");
                    var source = audio.audioSource();
                    yield new ModelMediaInput.QwenTranscription(source.url() == null
                            ? jobs.transcriptionAudio(owner, source.jobId(), source.assetId()) : source.url(), audio.language(), audio.enableItn());
                }
                if (audio.language() != null || audio.enableItn() != null)
                    throw new IllegalArgumentException("media_paraformer_options_invalid");
                yield new ModelMediaInput.Transcription(List.of(remoteSource(audio.audioSource(), owner)),
                        audio.channels(), audio.languageHints(), audio.diarization(), audio.speakerCount());
            }
            case MediaToolDefinitions.SpeechArguments speech -> new ModelMediaInput.Speech(speech.text(), speech.voice(), speech.language());
            default -> throw new IllegalArgumentException("media_arguments_invalid");
        };
    }

    private String imageSource(MediaToolDefinitions.Source source, ModelMediaJob.Owner owner) throws IOException {
        if (source == null) throw new IllegalArgumentException("media_source_required");
        if (source.url() != null) return source.url();
        var download = jobs.download(owner, source.jobId(), source.assetId());
        try (var input = download.content()) {
            if (download.mediaType() == null || !download.mediaType().startsWith("image/")
                    || download.sizeBytes() > ArtifactPublicInspection.MAX_INPUT_BYTES)
                throw new IllegalArgumentException("media_image_reference_invalid");
            byte[] bytes = input.readNBytes(Math.toIntExact(download.sizeBytes()) + 1);
            if (bytes.length != download.sizeBytes()) throw new IOException("media_asset_size_invalid");
            return "data:" + download.mediaType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
    }

    private String remoteSource(MediaToolDefinitions.Source source, ModelMediaJob.Owner owner) {
        if (source == null) throw new IllegalArgumentException("media_source_required");
        return source.url() == null ? jobs.referenceSource(owner, source.jobId(), source.assetId()).toString() : source.url();
    }

    private Map<String, Object> result(ModelMediaJob job, ArtifactMetadata candidate) {
        boolean complete = job.status() == DomesticMediaProvider.Status.SUCCEEDED
                && !job.assets().isEmpty() && job.assets().stream().allMatch(ModelMediaJob.Asset::available);
        boolean accepted = job.status() == DomesticMediaProvider.Status.SUCCEEDED
                || job.status() == DomesticMediaProvider.Status.PENDING || job.status() == DomesticMediaProvider.Status.RUNNING;
        List<Candidate> candidates = candidate == null ? List.of() : List.of(new Candidate(candidate.id(),
                candidate.files().stream().map(ArtifactMetadata.File::path).toList()));
        var presentation = candidate == null ? ProcessPresentation.of("媒体任务", stateText(job.status()))
                : new ProcessPresentation("媒体候选文件", "媒体结果已收集，尚未确认最终交付。", List.of(), List.of(
                        new ChatReferenceView(candidate.name(), new ChatInputReference.ArtifactFiles(
                                candidate.files().stream().map(file -> new ChatInputReference.ArtifactFile(candidate.id(), file.path())).toList()))));
        var value = new Result(accepted, complete, job.status().name(), job.id(), job.reasonCode(),
                job.assets().stream().map(asset -> new Asset(asset.id(), asset.kind().name(), asset.available())).toList(),
                candidates, presentation);
        return json.convertValue(value, RESULT);
    }

    private Map<String, Object> rejected(String reason) {
        String summary = ArtifactClosingBudget.REJECTION_CODE.equals(reason)
                ? "当前运行剩余额度不足以保护媒体文件收集和最终交付，本次尚未提交媒体任务。"
                : "请核对工具参数、当前会话权限和已配置的媒体模型。";
        return json.convertValue(new Result(false, false, "NOT_SUBMITTED", null, reason,
                List.of(), List.of(), ProcessPresentation.of("媒体请求未提交", summary)), RESULT);
    }
    private static boolean stopped(PlatformToolExecutionContext context) {
        return context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt());
    }
    private static String stateText(DomesticMediaProvider.Status status) {
        return switch (status) {
            case SUCCEEDED -> "供应商已完成，正文收集状态以资产清单为准。";
            case PENDING, RUNNING -> "原媒体任务仍在执行，请保留任务引用查询。";
            case UNKNOWN -> "原任务结果未知，请查询原任务，不要重新创建。";
            case FAILED, NOT_SUBMITTED -> "媒体任务未成功，请核对已返回的状态和原因。";
            case CANCELLED -> "供应商已确认任务取消。";
        };
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record Result(boolean success, boolean complete, String status, String jobId, String reasonCode,
            List<Asset> assets, List<Candidate> artifacts, ProcessPresentation publicPresentation) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record Asset(String assetId, String kind, boolean available) { }
    private record Candidate(String id, List<String> paths) { }
}
