package com.skillforge.core.model.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.invocation.ExternalInvocationRequest;
import com.skillforge.core.billing.invocation.ExternalInvocationStatus;
import com.skillforge.core.billing.invocation.ExternalOperationType;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MediaUsage;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.MediaPricingContext;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.media.ModelMediaJob.Create;
import com.skillforge.core.model.media.ModelMediaJob.Owner;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式媒体调用：先持久登记原身份，再单次派发；网络等待不占数据库事务。 */
public final class ModelMediaJobService {
    private final PlatformModelCatalogService models;
    private final CryptoService crypto;
    private final ModelMediaJobMapper mapper;
    private final ModelMediaAssetStore assetStore;
    private final DomesticMediaProviders providers;
    private final TransactionTemplate transactions;
    private final ObjectMapper commandJson;
    private final ExternalInvocationLedger invocations;
    private final CostRecorder costs;

    public ModelMediaJobService(PlatformModelCatalogService models, CryptoService crypto,
            ModelMediaJobMapper mapper, ModelMediaAssetStore assetStore, DomesticMediaProviders providers,
            PlatformTransactionManager manager, ObjectMapper json, ExternalInvocationLedger invocations, CostRecorder costs) {
        this.models = models; this.crypto = crypto; this.mapper = mapper; this.assetStore = assetStore;
        this.providers = providers; transactions = new TransactionTemplate(manager);
        commandJson = json.copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        this.invocations = Objects.requireNonNull(invocations); this.costs = Objects.requireNonNull(costs);
    }

    /** 相同操作先返回原事实；草稿后续修改不影响原调用核对，UNKNOWN 不能触发再次派发。 */
    public ModelMediaJob create(Owner owner, Create command) {
        requireOwner(owner);
        if (owner.tenantId() != null) throw new IllegalArgumentException("model_media_metering_source_required");
        if (command == null || command.input() == null || command.operationId() == null)
            throw inputInvalid();
        try { UUID.fromString(command.operationId()); }
        catch (IllegalArgumentException rejected) { throw inputInvalid(); }
        String digest = commandDigest(command);
        var existing = tx(owner, () -> mapper.operation(owner.key(), command.operationId()));
        if (existing != null) return sameCommand(owner, existing, digest);
        PlatformModelConfig config;
        DomesticMediaProvider.Request request;
        try {
            if (command.id() == null && command.payload() == null) throw configurationInvalid();
            config = command.payload() == null ? models.draftForProbe(command.id())
                    : models.previewForProbe(command.id(), command.payload());
        } catch (ApiException rejected) {
            throw configurationInvalid();
        }
        if (!config.enabled() || !command.input().modelKind().equals(config.kind()) || !supported(config, command.input()))
            throw configurationInvalid();
        try { request = command.input().request(config.modelId()); }
        catch (IllegalArgumentException rejected) { throw inputInvalid(); }
        return register(owner, command, config, digest, request, Instant.now().plusSeconds(config.timeoutSeconds()),
                Thread.currentThread()::isInterrupted, null);
    }

    /** Agent 仅使用宿主已授权的精确发布版本，模型参数不能切换账号或读取工作草稿。 */
    public ModelMediaJob createPublished(Owner owner, String operationId, String modelId, long modelRevision,
            ModelMediaInput input, Instant deadline, BooleanSupplier cancelled, ModelMediaMeteringSource metering) {
        requireOwner(owner);
        if (owner.tenantId() == null || metering == null
                || !owner.sessionScope().equals(metering.context().sessionId()))
            throw new IllegalArgumentException("model_media_metering_source_required");
        if (input == null || operationId == null || modelRevision <= 0 || deadline == null || cancelled == null)
            throw inputInvalid();
        try { UUID.fromString(operationId); }
        catch (IllegalArgumentException rejected) { throw inputInvalid(); }
        var command = new PublishedCommand(operationId, modelId, modelRevision, input);
        String digest = commandDigest(command);
        var previous = tx(owner, () -> mapper.operation(owner.key(), operationId));
        if (previous != null) return sameCommand(owner, previous, digest);
        var config = models.readRevision(modelId, modelRevision);
        if (!config.enabled() || config.revision() != modelRevision || !input.modelKind().equals(config.kind()) || !supported(config, input))
            throw configurationInvalid();
        DomesticMediaProvider.Request request;
        try { request = input.request(config.modelId()); }
        catch (IllegalArgumentException rejected) { throw inputInvalid(); }
        if (!deadline.isAfter(Instant.now()) || cancelled.getAsBoolean())
            throw new ApiException(HttpStatus.BAD_REQUEST, "model_media_not_submitted", "调用期限已到或已取消，媒体任务未提交", false);
        return register(owner, new Create(operationId, modelId, null, input), config, digest, request, deadline, cancelled, metering);
    }

    private record PublishedCommand(String operationId, String id, long revision, ModelMediaInput input) { }

    private ModelMediaJob register(Owner owner, Create command, PlatformModelConfig config, String digest,
            DomesticMediaProvider.Request request,
            Instant deadline, BooleanSupplier cancelled, ModelMediaMeteringSource metering) {
        var row = row(owner, command, config, digest);
        boolean registered = Boolean.TRUE.equals(tx(owner, () -> {
            if (mapper.insert(row) != 1) return false;
            saveInputs(row.id, command.input());
            return true;
        }));
        if (!registered) {
            var original = tx(owner, () -> mapper.operation(owner.key(), command.operationId()));
            if (original == null) throw new IllegalStateException("model_media_registration_missing");
            return sameCommand(owner, original, digest);
        }
        return invoke(owner, row, request, deadline, cancelled, metering);
    }

    public ModelMediaJob detail(Owner owner, String id) {
        return view(owner, find(owner, id));
    }

    /** 查询只使用原供应商 task_id 与冻结连接；没有 task_id 的 UNKNOWN 保留原事实。 */
    public ModelMediaJob query(Owner owner, String id) {
        return query(owner, id, Instant.now().plusSeconds(600), Thread.currentThread()::isInterrupted);
    }

    /** 工具调用的查询与正文收集共同服从原执行deadline和取消信号。 */
    public ModelMediaJob query(Owner owner, String id, Instant deadline, BooleanSupplier cancelled) {
        var row = find(owner, id);
        if (row.providerTaskId == null || terminal(Status.valueOf(row.status))) {
            materialize(owner, row, deadline, cancelled);
            return view(owner, row);
        }
        var kind = switch (row.kind) {
            case "VIDEO_GENERATION" -> DomesticMediaProvider.TaskKind.VIDEO;
            case "SPEECH_RECOGNITION" -> DomesticMediaProvider.TaskKind.TRANSCRIPTION;
            default -> throw new IllegalStateException("model_media_task_kind_invalid");
        };
        return invoke(owner, row, new DomesticMediaProvider.TaskQuery(kind, row.providerTaskId), deadline, cancelled, null);
    }

    /** 只停止本地等待，不声明供应商任务已取消；原任务仍可被显式查询。 */
    public ModelMediaJob stopWaiting(Owner owner, String id) {
        find(owner, id);
        tx(owner, () -> mapper.stop(owner.key(), id));
        return detail(owner, id);
    }

    /** 先校验任务归属，后打开不可变正文；asset_id 不能独立授予读取权。 */
    public Download download(Owner owner, String jobId, String assetId) throws IOException {
        find(owner, jobId);
        var asset = tx(owner, () -> mapper.assets(jobId)).stream()
                .filter(value -> value.id.equals(assetId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "model_media_asset_not_found"));
        if (!asset.available) throw new ApiException(HttpStatus.CONFLICT, "model_media_asset_not_ready");
        return new Download(asset.mediaType, asset.name, asset.sizeBytes, assetStore.open(asset.id));
    }

    /** 仅供受信工具复用原媒体输入；完整Session授权之后才返回供应商临时引用，不作为公开API。 */
    public URI referenceSource(Owner owner, String jobId, String assetId) {
        find(owner, jobId);
        var asset = tx(owner, () -> mapper.assets(jobId)).stream()
                .filter(value -> value.id.equals(assetId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "model_media_asset_not_found"));
        if (asset.sourceEncrypted == null)
            throw new ApiException(HttpStatus.CONFLICT, "model_media_source_not_remote");
        return URI.create(crypto.decrypt(asset.sourceEncrypted));
    }

    /** 受信工具可把本会话已收集音频交给 Qwen；不公开本地路径，不上传或发布公网资源。 */
    public String transcriptionAudio(Owner owner, String jobId, String assetId) throws IOException {
        var download = download(owner, jobId, assetId);
        try (var content = download.content()) {
            String type = switch (download.mediaType()) {
                case "audio/mpeg", "audio/mp3" -> "audio/mpeg";
                case "audio/wav", "audio/x-wav" -> "audio/wav";
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "model_media_audio_type_invalid");
            };
            String prefix = "data:" + type + ";base64,";
            int maximum = (DomesticMediaProvider.QwenTranscriptionRequest.MAXIMUM_ENCODED_AUDIO_BYTES - prefix.length()) / 4 * 3;
            if (download.sizeBytes() <= 0 || download.sizeBytes() > maximum)
                throw new ApiException(HttpStatus.BAD_REQUEST, "model_media_audio_size_exceeded");
            byte[] bytes = content.readNBytes(maximum + 1);
            if (bytes.length == 0 || bytes.length > maximum)
                throw new ApiException(HttpStatus.BAD_REQUEST, "model_media_audio_size_exceeded");
            if (bytes.length != download.sizeBytes())
                throw new IOException("media_asset_size_invalid");
            return prefix + Base64.getEncoder().encodeToString(bytes);
        }
    }

    public record Download(String mediaType, String name, long sizeBytes, InputStream content) { }

    private ModelMediaJob invoke(Owner owner, ModelMediaJobMapper.Row row, DomesticMediaProvider.Request request,
            Instant deadline, BooleanSupplier cancelled, ModelMediaMeteringSource metering) {
        DomesticMediaProvider.Result result;
        boolean creation = !(request instanceof DomesticMediaProvider.TaskQuery);
        var admissionFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        try {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isZero() || remaining.isNegative() || cancelled.getAsBoolean()) {
                if (!creation) return view(owner, row);
                result = new DomesticMediaProvider.Result(Status.NOT_SUBMITTED, null, null,
                        List.of(), null, "model_media_not_submitted", Instant.now());
            } else {
                Duration timeout = remaining.compareTo(Duration.ofSeconds(row.timeoutSeconds)) < 0
                        ? remaining : Duration.ofSeconds(row.timeoutSeconds);
                result = providers.require(row.providerId).execute(new DomesticMediaProvider.Connection(URI.create(row.endpoint),
                    crypto.decrypt(row.credentialEncrypted), timeout,
                    () -> cancelled.getAsBoolean() || !Instant.now().isBefore(deadline), () -> {
                        if (metering == null) return;
                        try { startMetering(owner, row, metering); }
                        catch (RuntimeException failed) { admissionFailure.set(failed); throw failed; }
                    }), request);
            }
        } catch (RuntimeException unknown) {
            if (admissionFailure.get() != null) {
                var failed = admissionFailure.get();
                row.status = Status.NOT_SUBMITTED.name(); row.reasonCode = "model_media_metering_not_submitted";
                row.updatedAt = Instant.now();
                try { tx(owner, () -> mapper.update(row, row.lockVersion)); }
                catch (RuntimeException saveFailure) { if (saveFailure != failed) failed.addSuppressed(saveFailure); }
                throw failed;
            }
            // 登记后异常不证明未提交，也不能让调用方重新创建供应商任务。
            result = new DomesticMediaProvider.Result(Status.UNKNOWN, null, row.providerTaskId,
                    List.of(), null, "model_media_outcome_unknown", Instant.now());
        }
        // 查询自身未发送不改变已受理的原生成任务，也不能归零其已有计量。
        if (!creation && result.status() == Status.NOT_SUBMITTED) return view(owner, row);
        apply(owner, row, result, deadline, cancelled, creation);
        var current = find(owner, row.id);
        materialize(owner, current, deadline, cancelled);
        return view(owner, current);
    }

    private void apply(Owner owner, ModelMediaJobMapper.Row row, DomesticMediaProvider.Result result,
            Instant deadline, BooleanSupplier cancelled, boolean creation) {
        long expected = row.lockVersion;
        row.status = result.status().name();
        if (result.requestId() != null) row.providerRequestId = result.requestId();
        if (result.taskId() != null) row.providerTaskId = result.taskId();
        row.reasonCode = result.reasonCode(); row.updatedAt = result.observedAt();
        var usage = result.usage();
        if (usage != null) {
            if (usage.inputTokens() != null) row.inputTokens = usage.inputTokens();
            if (usage.outputTokens() != null) row.outputTokens = usage.outputTokens();
            if (usage.imageCount() != null) row.imageCount = usage.imageCount();
            if (usage.videoCount() != null) row.videoCount = usage.videoCount();
            if (usage.characters() != null) row.characters = usage.characters();
            if (usage.durationSeconds() != null) row.durationSeconds = usage.durationSeconds();
        }
        var preparedAssets = new java.util.ArrayList<ModelMediaJobMapper.AssetRow>();
        for (var asset : result.assets()) {
            var prepared = new ModelMediaJobMapper.AssetRow();
            prepared.id = nextId(); prepared.jobId = row.id; prepared.kind = asset.kind().name(); prepared.mediaType = asset.mediaType();
            switch (asset.source()) {
                case DomesticMediaProvider.UrlSource source -> prepared.sourceEncrypted = crypto.encrypt(source.url().toString());
                case DomesticMediaProvider.BytesSource source -> {
                    try {
                        var stored = assetStore.storeBytes(prepared.id, asset.kind(), source.bytes(), asset.mediaType(),
                                Duration.between(Instant.now(), deadline), cancelled);
                        prepared.mediaType = stored.mediaType(); prepared.sizeBytes = stored.sizeBytes();
                        prepared.sha256 = stored.sha256(); prepared.name = stored.name(); prepared.available = true;
                    } catch (IOException failed) { row.reasonCode = "model_media_asset_not_collected"; }
                }
            }
            preparedAssets.add(prepared);
        }
        tx(owner, () -> {
            if (mapper.update(row, expected) == 0) return null;
            for (var asset : preparedAssets) mapper.insertAsset(asset);
            if (owner.tenantId() != null) {
                if (creation && result.status() == Status.NOT_SUBMITTED)
                    costs.recordMediaNotSubmitted(owner.tenantId(), mediaInvocation(row), expected + 1);
                else costs.recordMediaObservation(owner.tenantId(), mediaInvocation(row), expected + 1,
                            new MediaUsage(row.inputTokens, row.outputTokens, row.imageCount, row.videoCount,
                                    row.characters, row.durationSeconds), terminal(result.status()), new MediaPricingContext(row.resolution));
                if (creation) invocations.finish(owner.tenantId(), mediaAttempt(row),
                        result.status() == Status.UNKNOWN ? ExternalInvocationStatus.UNRESOLVED
                                : result.status() == Status.FAILED || result.status() == Status.CANCELLED || result.status() == Status.NOT_SUBMITTED
                                    ? ExternalInvocationStatus.FAILED : ExternalInvocationStatus.SUCCEEDED,
                        row.providerRequestId, row.reasonCode, row.updatedAt);
            }
            return null;
        });
    }

    /** 原媒体创建的发送权和未知计量同事务提交；查询只能补原来源，不取得新的创建身份。 */
    private void startMetering(Owner owner, ModelMediaJobMapper.Row row, ModelMediaMeteringSource source) {
        var operation = switch (row.kind) {
            case "IMAGE_GENERATION" -> ExternalOperationType.MODEL_IMAGE;
            case "VIDEO_GENERATION" -> ExternalOperationType.MODEL_VIDEO;
            case "SPEECH_RECOGNITION" -> ExternalOperationType.MODEL_TRANSCRIPTION;
            case "SPEECH_SYNTHESIS" -> ExternalOperationType.MODEL_SPEECH;
            default -> throw new IllegalStateException("model_media_task_kind_invalid");
        };
        var type = switch (row.kind) {
            case "IMAGE_GENERATION" -> CostType.MEDIA_IMAGE;
            case "VIDEO_GENERATION" -> CostType.MEDIA_VIDEO;
            case "SPEECH_RECOGNITION" -> CostType.MEDIA_TRANSCRIPTION;
            case "SPEECH_SYNTHESIS" -> CostType.MEDIA_SPEECH;
            default -> throw new IllegalStateException("model_media_task_kind_invalid");
        };
        var request = new ExternalInvocationRequest(mediaInvocation(row), mediaAttempt(row), operation,
                row.providerId, row.modelId, row.operationId, source.context().traceContext(),
                Map.of("media_job_id", row.id, "tool_invocation_id", source.toolInvocationId(), "tool_attempt_id", source.toolAttemptId()));
        tx(owner, () -> {
            if (!invocations.start(owner.tenantId(), request, Instant.now()))
                throw new ExternalInvocationGuard.DuplicateAttemptException(request.attemptId());
            costs.recordMediaStarted(owner.tenantId(), source.context(), request, type,
                    new ModelRevisionRef(row.modelConfigId, row.modelRevision));
            return null;
        });
    }
    private static String mediaInvocation(ModelMediaJobMapper.Row row) { return "media-job:" + row.id; }
    private static String mediaAttempt(ModelMediaJobMapper.Row row) { return mediaInvocation(row) + ":create:1"; }

    private void materialize(Owner owner, ModelMediaJobMapper.Row row, Instant deadline, BooleanSupplier cancelled) {
        for (var asset : tx(owner, () -> mapper.assets(row.id))) {
            if (asset.available) continue;
            if (asset.sourceEncrypted == null) continue;
            if (cancelled.getAsBoolean() || !Instant.now().isBefore(deadline)) return;
            try {
                var stored = assetStore.importAsset(asset.id,
                        DomesticMediaProvider.AssetKind.valueOf(asset.kind),
                        URI.create(crypto.decrypt(asset.sourceEncrypted)), asset.mediaType,
                        Duration.between(Instant.now(), deadline),
                        () -> cancelled.getAsBoolean() || !Instant.now().isBefore(deadline));
                tx(owner, () -> mapper.materialize(asset.id, stored.mediaType(), stored.sizeBytes(), stored.sha256(), stored.name()));
            } catch (IOException unavailable) {
                // 供应商成功与正文收集失败分别表达；原URL保留，可显式重新获取，不重建任务。
            }
        }
    }

    private ModelMediaJob sameCommand(Owner owner, ModelMediaJobMapper.Row row, String digest) {
        if (!row.commandSha256.equals(digest)) throw new ApiException(HttpStatus.CONFLICT,
                "model_media_operation_conflict", "同一媒体操作身份不能用于不同输入", false);
        return view(owner, row);
    }

    private ModelMediaJob view(Owner owner, ModelMediaJobMapper.Row row) {
        var assets = tx(owner, () -> mapper.assets(row.id)).stream().map(asset -> new ModelMediaJob.Asset(
                asset.id, DomesticMediaProvider.AssetKind.valueOf(asset.kind), asset.mediaType,
                asset.sizeBytes, asset.name, asset.available)).toList();
        boolean incomplete = assets.stream().anyMatch(asset -> !asset.available());
        return new ModelMediaJob(row.id, row.operationId, row.modelConfigId, row.modelId, row.modelRevision, row.kind,
                Status.valueOf(row.status), row.waitingStopped, row.providerTaskId,
                incomplete && row.reasonCode == null ? "model_media_asset_not_collected" : row.reasonCode,
                row.createdAt, row.updatedAt, new ModelMediaJob.Usage(row.inputTokens, row.outputTokens,
                        row.imageCount, row.videoCount, row.characters, row.durationSeconds), assets);
    }

    private ModelMediaJobMapper.Row find(Owner owner, String id) {
        requireOwner(owner);
        if (id == null || !id.matches("[1-9][0-9]*")) throw inputInvalid();
        var row = tx(owner, () -> mapper.find(owner.key(), id));
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "model_media_job_not_found");
        return row;
    }

    private ModelMediaJobMapper.Row row(Owner owner, Create command, PlatformModelConfig config, String digest) {
        var row = new ModelMediaJobMapper.Row();
        row.id = nextId(); row.ownerId = owner.key(); row.tenantId = owner.tenantId();
        row.operationId = command.operationId(); row.commandSha256 = digest; row.modelConfigId = command.id();
        row.modelRevision = config.revision(); row.providerId = config.providerId(); row.serviceId = config.serviceId();
        row.profileVersion = config.profileVersion(); row.modelId = config.modelId(); row.kind = config.kind();
        row.endpoint = config.endpoint(); row.credentialEncrypted = config.apiKeyEncrypted();
        row.timeoutSeconds = config.timeoutSeconds(); row.status = Status.UNKNOWN.name();
        row.reasonCode = "model_media_dispatch_recorded"; row.createdAt = Instant.now(); row.updatedAt = row.createdAt;
        switch (command.input()) {
            case ModelMediaInput.Image input -> {
                row.inputText = input.prompt(); row.negativePrompt = input.negativePrompt(); row.size = input.size();
                row.outputCount = input.count(); row.seed = input.seed(); row.promptExtend = input.promptExtend(); row.watermark = input.watermark();
            }
            case ModelMediaInput.Video input -> {
                row.inputText = input.prompt(); row.resolution = input.resolution(); row.ratio = input.ratio();
                row.videoDurationSeconds = input.durationSeconds(); row.audioEnabled = input.audio(); row.seed = input.seed();
                row.promptExtend = input.promptExtend(); row.watermark = input.watermark();
            }
            case ModelMediaInput.Transcription input -> { row.diarization = input.diarization(); row.speakerCount = input.speakerCount(); }
            case ModelMediaInput.QwenTranscription input -> { row.language = input.language(); row.enableItn = input.enableItn(); }
            case ModelMediaInput.Speech input -> { row.inputText = input.text(); row.voice = input.voice(); row.language = input.language(); }
        }
        return row;
    }

    private void saveInputs(String job, ModelMediaInput input) {
        switch (input) {
            case ModelMediaInput.Image image -> {
                for (int i = 0; i < image.referenceImages().size(); i++)
                    mapper.insertSource(job, i, "REFERENCE_IMAGE", crypto.encrypt(image.referenceImages().get(i)));
            }
            case ModelMediaInput.Video video -> {
                for (int i = 0; i < video.media().size(); i++)
                    mapper.insertSource(job, i, video.media().get(i).kind().name(), crypto.encrypt(video.media().get(i).source()));
            }
            case ModelMediaInput.Transcription transcription -> {
                for (int i = 0; i < transcription.fileUrls().size(); i++)
                    mapper.insertSource(job, i, "AUDIO_FILE", crypto.encrypt(transcription.fileUrls().get(i)));
                if (transcription.channels() != null) for (int i = 0; i < transcription.channels().size(); i++)
                    mapper.insertChannel(job, i, transcription.channels().get(i));
                if (transcription.languageHints() != null) for (int i = 0; i < transcription.languageHints().size(); i++)
                    mapper.insertLanguage(job, i, transcription.languageHints().get(i));
            }
            case ModelMediaInput.QwenTranscription transcription ->
                    mapper.insertSource(job, 0, "AUDIO_FILE", crypto.encrypt(transcription.audio()));
            case ModelMediaInput.Speech ignored -> { }
        }
    }

    private String commandDigest(Object command) {
        try {
            // 仅对入口类型的规范序列化求摘要，不持久化第二份JSON业务对象。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(commandJson.writeValueAsBytes(command)));
        } catch (JsonProcessingException failure) { throw inputInvalid(); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private <T> T tx(Owner owner, Supplier<T> work) {
        return transactions.execute(status -> {
            if (owner.tenantId() != null) mapper.bindTenant(owner.tenantId());
            return work.get();
        });
    }
    private static void requireOwner(Owner owner) {
        Objects.requireNonNull(owner);
        if (!Objects.equals(owner.tenantId(), TenantContext.getTenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "model_media_scope_mismatch");
    }
    private static boolean terminal(Status status) {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED || status == Status.NOT_SUBMITTED;
    }
    private static boolean supported(PlatformModelConfig config, ModelMediaInput input) {
        boolean qwenService = "bailian-cn-beijing-qwen-asr".equals(config.serviceId());
        boolean qwenProtocol = ModelProtocol.QWEN_ASR_NATIVE.id().equals(config.protocol());
        if (input instanceof ModelMediaInput.QwenTranscription)
            return "aliyun-bailian".equals(config.providerId()) && qwenService && qwenProtocol;
        if (qwenService || qwenProtocol) return false;
        return "aliyun-bailian".equals(config.providerId())
                || "minimax".equals(config.providerId()) && "SPEECH_SYNTHESIS".equals(config.kind());
    }
    private static String nextId() { return Long.toString(SnowflakeIds.nextId()); }
    private static ApiException inputInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "model_media_input_invalid", "媒体输入无效，请核对参数和公开HTTPS资源地址", false);
    }
    private static ApiException configurationInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "model_media_configuration_invalid", "媒体模型配置无效，请核对国内服务及模型类型", false);
    }
}
