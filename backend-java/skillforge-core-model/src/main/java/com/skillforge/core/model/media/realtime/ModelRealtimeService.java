package com.skillforge.core.model.media.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.media.ModelMediaAssetStore;
import com.skillforge.core.model.media.ModelMediaJob.Owner;
import com.skillforge.core.model.media.realtime.ModelRealtimeMapper.Row;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.model.media.BailianRealtimeTranscription;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 实时语音连接的单次登记与持久回执；重读原操作不创建新 WebSocket 或重新合成。 */
public final class ModelRealtimeService implements AutoCloseable {
    private final PlatformModelCatalogService models;
    private final CryptoService crypto;
    private final ModelRealtimeMapper mapper;
    private final ModelMediaAssetStore assets;
    private final BailianRealtimeTranscription transcription;
    private final DomesticMediaProviders providers;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final ConcurrentHashMap<String, Live> live = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 事件消费者只承接当前连接；持久终态始终可通过 detail 读取。 */
    public interface Listener {
        void snapshot(ModelRealtimeSession session);
        default void audio(ModelRealtimeSession.Audio chunk) { }
        default void complete() { }
    }

    public ModelRealtimeService(PlatformModelCatalogService models, CryptoService crypto, ModelRealtimeMapper mapper,
            ModelMediaAssetStore assets, BailianRealtimeTranscription transcription, DomesticMediaProviders providers,
            PlatformTransactionManager manager, ObjectMapper json, Clock clock) {
        this.models = models; this.crypto = crypto; this.mapper = mapper; this.assets = assets;
        this.transcription = transcription; this.providers = providers;
        transactions = new TransactionTemplate(manager);
        this.json = json.copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY); this.clock = clock;
    }

    public ModelRealtimeSession openAsr(Owner owner, ModelRealtimeSession.OpenAsr command) {
        requireOwner(owner);
        if (command == null || command.sampleRateHz() != 8000 && command.sampleRateHz() != 16000)
            throw invalid("model_realtime_pcm_invalid");
        var registered = register(owner, command.operationId(), command.id(), command.payload(), command,
                "SPEECH_RECOGNITION", command.sampleRateHz(), command.language(), null, null);
        if (!registered.created()) return detail(owner, registered.row().id);
        var row = registered.row();
        var connection = new Live(owner, row.id);
        live.put(row.id, connection);
        if (closed.get()) return stopConnection(owner, row);
        try {
            connection.asr = transcription.open(connection(row, connection), new BailianRealtimeTranscription.RealtimeRequest(
                    row.providerTaskId, row.modelId, row.sampleRateHz,
                    row.language == null || row.language.isBlank() ? null : List.of(row.language)), event -> {
                switch (event) {
                    case BailianRealtimeTranscription.TranscriptEvent.Started ignored ->
                            tx(owner, () -> mapper.started(row.id, clock.instant()));
                    case BailianRealtimeTranscription.TranscriptEvent.Segment segment ->
                            tx(owner, () -> mapper.partial(row.id, segment.sentence().text(), clock.instant()));
                    case BailianRealtimeTranscription.TranscriptEvent.Completed completed ->
                            complete(owner, row.id, completed.outcome().result(), completed.outcome().text());
                }
                if (!(event instanceof BailianRealtimeTranscription.TranscriptEvent.Completed)) connection.notifySnapshot();
            });
        } catch (RuntimeException failure) {
            complete(owner, row.id, unknown(row, "model_realtime_open_unknown"), null);
        }
        return detail(owner, row.id);
    }

    /** 每个 sequence 先登记后单次发送；重复分片返回冲突，不重发不确定的原音频。 */
    public ModelRealtimeSession audio(Owner owner, String id, long sequence, byte[] pcm) {
        requireOwner(owner);
        if (sequence < 0 || pcm == null || pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > 1048576)
            throw invalid("model_realtime_pcm_invalid");
        var row = find(owner, id);
        var connection = requireAsr(owner, row);
        if (tx(owner, () -> mapper.claimAudio(owner.key(), id, sequence, clock.instant())) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "model_realtime_audio_sequence_conflict");
        try {
            long remaining = Math.max(1, Duration.between(clock.instant(), row.deadline).toMillis());
            connection.asr.sendAudio(pcm).toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            connection.cancelled.set(true); connection.asr.cancel();
            complete(owner, id, unknown(row, "model_realtime_audio_outcome_unknown"), null);
        }
        return detail(owner, id);
    }

    public ModelRealtimeSession finish(Owner owner, String id) {
        requireOwner(owner);
        var row = find(owner, id);
        if (terminal(row.status) || row.finishing) return view(row);
        var connection = requireAsr(owner, row);
        if (tx(owner, () -> mapper.finish(owner.key(), id, clock.instant())) == 1) connection.asr.finish();
        return detail(owner, id);
    }

    /** 同键再次调用仅发送原状态，不重新订阅供应商音频流。 */
    public ModelRealtimeSession streamSpeech(Owner owner, ModelRealtimeSession.OpenSpeech command, Listener listener) {
        requireOwner(owner); Objects.requireNonNull(listener);
        if (command == null || command.text() == null || command.text().isBlank()
                || command.voice() == null || command.voice().isBlank()) throw invalid("model_realtime_speech_invalid");
        var registered = register(owner, command.operationId(), command.id(), command.payload(), command,
                "SPEECH_SYNTHESIS", 0, command.language(), command.text(), command.voice());
        var row = registered.row();
        if (!registered.created()) {
            listener.snapshot(detail(owner, row.id)); listener.complete(); return view(row);
        }
        var connection = new Live(owner, row.id);
        connection.listeners.add(listener); live.put(row.id, connection);
        if (closed.get()) return stopConnection(owner, row);
        listener.snapshot(view(row));
        // 国内供应商发布者在 request 时管理自己的异步工作；宿主无需再创建无归属启动线程。
        try {
            providers.require(row.providerId).streamSpeech(connection(row, connection), new DomesticMediaProvider.SpeechRequest(
                    row.modelId, row.inputText, row.voice, row.language)).subscribe(new Flow.Subscriber<>() {
                private boolean completed;
                @Override public void onSubscribe(Flow.Subscription subscription) {
                    connection.subscription = subscription;
                    if (connection.cancelled.get()) subscription.cancel();
                    else { tx(owner, () -> mapper.started(row.id, clock.instant())); subscription.request(1); }
                }
                @Override public void onNext(DomesticMediaProvider.SpeechEvent event) {
                    if (event instanceof DomesticMediaProvider.SpeechEvent.AudioChunk chunk) {
                        var audio = new ModelRealtimeSession.Audio(Base64.getEncoder().encodeToString(chunk.bytes()),
                                chunk.encoding(), chunk.sampleRateHz(), chunk.channels());
                        connection.listeners.forEach(value -> value.audio(audio));
                    } else if (event instanceof DomesticMediaProvider.SpeechEvent.Completed done) {
                        completed = true; complete(owner, row.id, done.result(), null);
                    }
                    if (!completed && !connection.cancelled.get()) connection.subscription.request(1);
                }
                @Override public void onError(Throwable failure) { complete(owner, row.id, unknown(row, "model_realtime_stream_unknown"), null); }
                @Override public void onComplete() {
                    if (!completed) complete(owner, row.id, unknown(row, "model_realtime_stream_incomplete"), null);
                }
            });
        } catch (RuntimeException failure) { complete(owner, row.id, unknown(row, "model_realtime_stream_unknown"), null); }
        return view(row);
    }

    public ModelRealtimeSession detail(Owner owner, String id) {
        requireOwner(owner);
        var row = find(owner, id);
        if (!terminal(row.status) && !row.deadline.isAfter(clock.instant())) {
            stop(owner, id);
            row = find(owner, id);
        }
        return view(row);
    }

    /** 只终止当前宿主连接；断开后不能声称供应商执行或费用已撤销。 */
    public ModelRealtimeSession stop(Owner owner, String id) {
        requireOwner(owner);
        var row = find(owner, id);
        return stopConnection(owner, row);
    }

    private ModelRealtimeSession stopConnection(Owner owner, Row row) {
        var connection = live.get(row.id);
        if (connection != null) {
            connection.cancelled.set(true);
            if (connection.subscription != null) connection.subscription.cancel();
            if (connection.asr != null) connection.asr.cancel();
        }
        if (!terminal(row.status)) complete(owner, row.id, unknown(row, "model_realtime_connection_stopped"), null);
        return view(find(owner, row.id));
    }

    /** SSE 订阅只读取原实时会话，不触发新的供应商请求。 */
    public AutoCloseable listen(Owner owner, String id, Listener listener) {
        var snapshot = detail(owner, id);
        var connection = live.get(id);
        listener.snapshot(snapshot);
        if (connection == null || terminal(snapshot.status().name())) { listener.complete(); return () -> { }; }
        connection.listeners.add(listener);
        return () -> connection.listeners.remove(listener);
    }

    public Download download(Owner owner, String id) throws IOException {
        requireOwner(owner);
        var row = find(owner, id);
        if (!row.assetAvailable) throw new ApiException(HttpStatus.CONFLICT, "model_realtime_asset_not_ready");
        return new Download(row.assetMediaType, row.assetName, row.assetSizeBytes, assets.open(row.assetId));
    }
    public record Download(String mediaType, String name, long sizeBytes, InputStream content) { }

    private Registration register(Owner owner, String operation, String modelId, ModelWrite payload, Object command,
            String kind, int sampleRate, String language, String text, String voice) {
        if (closed.get()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "model_realtime_service_closed");
        try { if (operation == null || !UUID.fromString(operation).toString().equals(operation)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException rejected) { throw invalid("model_realtime_operation_invalid"); }
        String digest = digest(command);
        var existing = tx(owner, () -> mapper.operation(owner.key(), operation));
        if (existing != null) return new Registration(same(existing, digest), false);
        if (modelId == null && payload == null) throw invalid("model_realtime_configuration_required");
        PlatformModelConfig config = payload == null ? models.draftForProbe(modelId) : models.previewForProbe(modelId, payload);
        models.validateReasoning(config);
        if (!config.enabled() || !kind.equals(config.kind())
                || "SPEECH_RECOGNITION".equals(kind) && !"aliyun-bailian".equals(config.providerId()))
            throw invalid("model_realtime_configuration_invalid");
        providers.require(config.providerId());
        var row = new Row();
        row.id = nextId(); row.ownerId = owner.key(); row.tenantId = owner.tenantId(); row.operationId = operation;
        row.commandSha256 = digest; row.kind = kind; row.modelConfigId = modelId; row.modelRevision = config.revision();
        row.providerId = config.providerId(); row.serviceId = config.serviceId(); row.modelId = config.modelId();
        row.endpoint = config.endpoint(); row.credentialEncrypted = config.apiKeyEncrypted(); row.timeoutSeconds = config.timeoutSeconds();
        row.providerTaskId = "SPEECH_RECOGNITION".equals(kind) ? UUID.randomUUID().toString() : null;
        row.sampleRateHz = sampleRate; row.language = language;
        row.inputText = text; row.voice = voice; row.status = Status.PENDING.name();
        row.createdAt = clock.instant(); row.updatedAt = row.createdAt; row.deadline = row.createdAt.plusSeconds(config.timeoutSeconds());
        if (tx(owner, () -> mapper.insert(row)) == 1) return new Registration(row, true);
        return new Registration(same(tx(owner, () -> mapper.operation(owner.key(), operation)), digest), false);
    }
    private record Registration(Row row, boolean created) { }

    private void complete(Owner owner, String id, DomesticMediaProvider.Result result, String text) {
        var row = find(owner, id);
        row.status = result.status() == Status.CANCELLED ? Status.UNKNOWN.name() : result.status().name();
        if (result.status() == Status.PENDING || result.status() == Status.RUNNING) row.status = Status.UNKNOWN.name();
        row.finalText = text; row.reasonCode = result.reasonCode(); row.updatedAt = clock.instant();
        if (result.taskId() != null) row.providerTaskId = result.taskId();
        else if (result.requestId() != null) row.providerTaskId = result.requestId();
        if (result.usage() != null) {
            row.inputTokens = result.usage().inputTokens(); row.outputTokens = result.usage().outputTokens();
            row.characters = result.usage().characters(); row.durationSeconds = result.usage().durationSeconds();
        }
        DomesticMediaProvider.Asset output = result.assets().size() == 1
                && result.assets().getFirst().kind() == DomesticMediaProvider.AssetKind.AUDIO ? result.assets().getFirst() : null;
        if (output != null) {
            row.assetId = nextId();
            if (output.source() instanceof DomesticMediaProvider.UrlSource source)
                row.assetSourceEncrypted = crypto.encrypt(source.url().toString());
        }
        if (tx(owner, () -> mapper.complete(row)) != 1) return;
        if (output != null && Status.SUCCEEDED.name().equals(row.status)) {
            try {
                Duration remaining = Duration.between(clock.instant(), row.deadline);
                if (!remaining.isNegative() && !remaining.isZero()) {
                    var stored = switch (output.source()) {
                        case DomesticMediaProvider.UrlSource source -> assets.importAsset(row.assetId,
                                output.kind(), source.url(), output.mediaType(), remaining, () -> false);
                        case DomesticMediaProvider.BytesSource source -> assets.storeBytes(row.assetId,
                                output.kind(), source.bytes(), output.mediaType(), remaining, () -> false);
                    };
                    row.assetMediaType = stored.mediaType(); row.assetName = stored.name(); row.assetSizeBytes = stored.sizeBytes();
                    row.assetSha256 = stored.sha256(); row.updatedAt = clock.instant();
                    tx(owner, () -> mapper.asset(row));
                }
            } catch (IOException unavailable) {
                // 合成已完成；正文不可用单独记录，不能诱导重新发送原合成。
                tx(owner, () -> mapper.assetUnavailable(id, clock.instant()));
            }
        }
        var connection = live.remove(id);
        if (connection != null) {
            connection.listeners.forEach(listener -> listener.snapshot(view(find(owner, id))));
            connection.listeners.forEach(Listener::complete);
        }
    }

    private DomesticMediaProvider.Connection connection(Row row, Live connection) {
        Duration remaining = Duration.between(clock.instant(), row.deadline);
        return new DomesticMediaProvider.Connection(URI.create(row.endpoint), crypto.decrypt(row.credentialEncrypted), remaining,
                () -> connection.cancelled.get() || !clock.instant().isBefore(row.deadline));
    }
    private Live requireAsr(Owner owner, Row row) {
        if (!"SPEECH_RECOGNITION".equals(row.kind)) throw invalid("model_realtime_asr_required");
        var connection = live.get(row.id);
        if (connection == null || connection.asr == null) throw new ApiException(HttpStatus.CONFLICT, "model_realtime_connection_unavailable");
        return connection;
    }
    private Row find(Owner owner, String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) throw invalid("model_realtime_identity_invalid");
        var row = tx(owner, () -> mapper.find(owner.key(), id));
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "model_realtime_not_found");
        return row;
    }
    private ModelRealtimeSession view(Row row) {
        return new ModelRealtimeSession(row.id, row.operationId, row.kind, row.modelId, row.modelRevision,
                row.providerTaskId, Status.valueOf(row.status), live.containsKey(row.id), row.finishing,
                row.nextAudioSequence, row.partialText, row.finalText, row.reasonCode, row.createdAt, row.deadline, row.updatedAt,
                new ModelRealtimeSession.Usage(row.inputTokens, row.outputTokens, row.characters, row.durationSeconds),
                row.assetAvailable ? new ModelRealtimeSession.Asset(row.assetId, row.assetMediaType, row.assetSizeBytes, row.assetName) : null);
    }
    private <T> T tx(Owner owner, Supplier<T> work) {
        return transactions.execute(status -> {
            if (owner.tenantId() != null) mapper.bindTenant(owner.tenantId());
            return work.get();
        });
    }
    private String digest(Object command) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(command))); }
        catch (JsonProcessingException rejected) { throw invalid("model_realtime_input_invalid"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Row same(Row row, String digest) {
        if (row == null || !row.commandSha256.equals(digest)) throw new ApiException(HttpStatus.CONFLICT, "model_realtime_operation_conflict");
        return row;
    }
    private DomesticMediaProvider.Result unknown(Row row, String reason) {
        return new DomesticMediaProvider.Result(Status.UNKNOWN, null, row.providerTaskId, List.of(), null, reason, clock.instant());
    }
    private static boolean terminal(String status) { return !Status.PENDING.name().equals(status) && !Status.RUNNING.name().equals(status); }
    private static String nextId() { return Long.toString(com.skillforge.core.shared.config.SnowflakeIds.nextId()); }
    private static void requireOwner(Owner owner) {
        Objects.requireNonNull(owner);
        if (!Objects.equals(owner.tenantId(), TenantContext.getTenantId())) throw new ApiException(HttpStatus.FORBIDDEN, "model_realtime_scope_mismatch");
    }
    private static ApiException invalid(String reason) { return new ApiException(HttpStatus.BAD_REQUEST, reason, "实时语音参数或配置无效，请核对模型、输入与原操作身份", false); }
    /** 停机释放宿主持有的连接；本地取消不构造供应商撤销或工作者静止回执。 */
    @Override public void close() {
        if (closed.compareAndSet(false, true))
            List.copyOf(live.values()).forEach(value -> stopConnection(value.owner, find(value.owner, value.id)));
    }

    private final class Live {
        final Owner owner; final String id;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
        volatile BailianRealtimeTranscription.Session asr;
        volatile Flow.Subscription subscription;
        Live(Owner owner, String id) { this.owner = owner; this.id = id; }
        void notifySnapshot() { listeners.forEach(listener -> listener.snapshot(view(find(owner, id)))); }
    }
}
