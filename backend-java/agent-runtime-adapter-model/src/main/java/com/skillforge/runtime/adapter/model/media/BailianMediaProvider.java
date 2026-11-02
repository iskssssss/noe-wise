package com.skillforge.runtime.adapter.model.media;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 百炼北京原生图像、Wan 3 视频、Paraformer 文件识别和 Qwen-TTS 的单次传输。
 * 官方契约：
 * <a href="https://help.aliyun.com/zh/model-studio/qwen-image-edit-api">图像</a>、
 * <a href="https://help.aliyun.com/zh/model-studio/wan3-video-generation-api-reference">视频</a>、
 * <a href="https://help.aliyun.com/zh/model-studio/paraformer-recorded-speech-recognition-restful-api">识别</a>、
 * <a href="https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference">Qwen 同步识别</a>、
 * <a href="https://help.aliyun.com/zh/model-studio/qwen-tts-api">语音合成</a>。
 * 不自动重发创建，不在 SDK 中轮询，不下载或持久化结果，不声明未提供的云端取消。
 */
public final class BailianMediaProvider implements DomesticMediaProvider {
    private static final String GENERATION = "/services/aigc/multimodal-generation/generation";
    private static final String VIDEO = "/services/aigc/video-generation/video-synthesis";
    private static final String TRANSCRIPTION = "/services/audio/asr/transcription";
    private static final String TRANSPORT_UNKNOWN = "media_transport_outcome_unknown";
    private static final String BODY_READ_UNKNOWN = "media_response_read_unknown";
    private static final String JSON_INVALID = "media_response_json_invalid";
    private static final String CONTENT_INVALID = "media_response_content_invalid";
    private static final String SPEECH_AUDIO_ENCODING = "wav";
    private static final int MAXIMUM_SINGLE_VIDEO_MATERIAL_COUNT = 1;
    private static final int MAXIMUM_REFERENCE_IMAGE_COUNT = 10;
    private static final int MAXIMUM_REFERENCE_VIDEO_OR_AUDIO_COUNT = 5;
    private static final int MINIMUM_VIDEO_DURATION_SECONDS = 2;
    private static final int MINIMUM_REFERENCE_VIDEO_OUTPUT_SECONDS = 1;
    private static final int MAXIMUM_VIDEO_DURATION_SECONDS = 30;
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient http;
    private final Clock clock;
    private final int maximumResponseBytes;

    public BailianMediaProvider(HttpClient http, Clock clock, int maximumResponseBytes) {
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumResponseBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("media_transport_configuration_invalid");
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override public Result execute(Connection connection, Request request) {
        Objects.requireNonNull(connection); Objects.requireNonNull(request);
        var deadline = clock.instant().plus(connection.timeout());
        if (connection.cancelled().getAsBoolean()) return result(Status.NOT_SUBMITTED, null, taskId(request), List.of(), null, "media_cancelled_before_dispatch");
        HttpRequest prepared;
        try { prepared = prepare(connection, request, false); }
        catch (IllegalArgumentException | IOException invalid) {
            return result(Status.NOT_SUBMITTED, null, taskId(request), List.of(), null, "media_configuration_invalid");
        }
        if (connection.cancelled().getAsBoolean()) return result(Status.NOT_SUBMITTED, null, taskId(request), List.of(), null, "media_cancelled_before_dispatch");
        // 宿主审计失败属于派发前技术失败，不能进入供应商 UNKNOWN 转换边界。
        connection.beforeDispatch().beforeDispatch();
        if (connection.cancelled().getAsBoolean()) return result(Status.NOT_SUBMITTED, null, taskId(request), List.of(), null, "media_cancelled_before_dispatch");
        if (!clock.instant().isBefore(deadline)) return result(Status.NOT_SUBMITTED, null, taskId(request), List.of(), null, "media_deadline_before_dispatch");
        String unknownReason = TRANSPORT_UNKNOWN;
        BailianMediaWire.Envelope envelope = null;
        try (var exchange = new MediaHttpExchange(http, clock, connection, prepared, deadline)) {
            var response = exchange.response();
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return httpFailure(response, request);
            unknownReason = BODY_READ_UNKNOWN;
            byte[] bytes;
            try (var body = response.body()) { bytes = readBounded(body); }
            unknownReason = JSON_INVALID;
            envelope = JSON.readValue(bytes, BailianMediaWire.Envelope.class);
            unknownReason = CONTENT_INVALID;
            return decode(envelope, request);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // 仅保留固定失败阶段，不把异常正文、供应商响应或签名URL写入诊断。
        }
        return result(Status.UNKNOWN, envelope == null ? null : envelope.requestId(), taskId(request), List.of(), null, unknownReason);
    }

    @Override public Flow.Publisher<SpeechEvent> streamSpeech(Connection connection, SpeechRequest request) {
        Objects.requireNonNull(connection); Objects.requireNonNull(request);
        var subscribed = new AtomicBoolean();
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() { public void request(long count) { } public void cancel() { } });
                subscriber.onError(new IllegalStateException("media_stream_already_subscribed"));
                return;
            }
            subscriber.onSubscribe(new SpeechSubscription(connection, request, subscriber));
        };
    }

    /** 只接北京服务；允许同一服务的兼容地址输入，但实际发送固定原生 API 路径。 */
    public static URI nativeEndpoint(URI endpoint, String path) {
        var host = endpoint.getHost();
        boolean beijing = "dashscope.aliyuncs.com".equals(host)
                || host != null && host.matches("[a-zA-Z0-9-]+\\.cn-beijing\\.maas\\.aliyuncs\\.com");
        if (!beijing || !"https".equals(endpoint.getScheme()) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || endpoint.getPort() != -1 && endpoint.getPort() != 443
                || !List.of("", "/", "/api/v1", "/api/v1/", "/compatible-mode/v1", "/compatible-mode/v1/").contains(endpoint.getPath()))
            throw new IllegalArgumentException("media_domestic_endpoint_required");
        if (!path.startsWith("/") || path.contains("?") || path.contains("#") || path.contains(".."))
            throw new IllegalArgumentException("media_api_path_invalid");
        return URI.create("https://" + host + "/api/v1" + path);
    }

    private HttpRequest prepare(Connection connection, Request request, boolean stream) throws IOException {
        String path;
        Object body;
        boolean asynchronous = false;
        switch (request) {
            case ImageRequest image -> {
                path = GENERATION;
                var parts = new ArrayList<BailianMediaWire.Part>();
                for (String source : image.referenceImages()) {
                    requireSource(source, true);
                    parts.add(new BailianMediaWire.Part(null, source));
                }
                parts.add(new BailianMediaWire.Part(image.prompt(), null));
                body = new BailianMediaWire.ImageBody(image.model(),
                        new BailianMediaWire.ImageInput(List.of(new BailianMediaWire.Message("user", List.copyOf(parts)))),
                        new BailianMediaWire.ImageParameters(image.size(), image.count(), image.negativePrompt(), image.seed(), image.promptExtend(), image.watermark()));
            }
            case VideoRequest video -> {
                path = VIDEO; asynchronous = true;
                var media = new ArrayList<BailianMediaWire.VideoMedia>();
                var counts = new EnumMap<VideoInputKind, Integer>(VideoInputKind.class);
                for (var source : video.media()) {
                    int maximum = switch (source.kind()) {
                        case FIRST_FRAME, LAST_FRAME, FILE, LINK -> MAXIMUM_SINGLE_VIDEO_MATERIAL_COUNT;
                        case REFERENCE_IMAGE -> MAXIMUM_REFERENCE_IMAGE_COUNT;
                        case REFERENCE_VIDEO, REFERENCE_AUDIO -> MAXIMUM_REFERENCE_VIDEO_OR_AUDIO_COUNT;
                    };
                    if (counts.merge(source.kind(), 1, Integer::sum) > maximum)
                        throw new IllegalArgumentException("media_video_input_count_invalid");
                    requireSource(source.source(), source.kind() == VideoInputKind.FIRST_FRAME || source.kind() == VideoInputKind.LAST_FRAME || source.kind() == VideoInputKind.REFERENCE_IMAGE);
                    media.add(new BailianMediaWire.VideoMedia(source.kind().name().toLowerCase(Locale.ROOT), source.source()));
                }
                boolean frames = counts.containsKey(VideoInputKind.FIRST_FRAME) || counts.containsKey(VideoInputKind.LAST_FRAME);
                if (frames && counts.keySet().stream().anyMatch(kind -> kind != VideoInputKind.FIRST_FRAME && kind != VideoInputKind.LAST_FRAME)
                        || counts.containsKey(VideoInputKind.FILE) && counts.containsKey(VideoInputKind.LINK))
                    throw new IllegalArgumentException("media_video_input_combination_invalid");
                if (Boolean.FALSE.equals(video.promptExtend()) && (counts.containsKey(VideoInputKind.FILE) || counts.containsKey(VideoInputKind.LINK)))
                    throw new IllegalArgumentException("media_video_prompt_extension_required");
                // 参考视频允许更短输出；素材时长未随请求提供，输入与输出总时长由供应商校验。
                int minimumDuration = counts.containsKey(VideoInputKind.REFERENCE_VIDEO)
                        ? MINIMUM_REFERENCE_VIDEO_OUTPUT_SECONDS : MINIMUM_VIDEO_DURATION_SECONDS;
                if (video.durationSeconds() != null && video.durationSeconds() != VideoRequest.AUTOMATIC_DURATION_SECONDS
                        && (video.durationSeconds() < minimumDuration || video.durationSeconds() > MAXIMUM_VIDEO_DURATION_SECONDS))
                    throw new IllegalArgumentException("media_video_duration_invalid");
                body = new BailianMediaWire.VideoBody(video.model(), new BailianMediaWire.VideoInput(video.prompt(), media.isEmpty() ? null : List.copyOf(media)),
                        new BailianMediaWire.VideoParameters(video.resolution(), video.ratio(), video.durationSeconds(), video.audio(), video.seed(), video.promptExtend(), video.watermark()));
            }
            case TranscriptionRequest transcription -> {
                path = TRANSCRIPTION; asynchronous = true;
                transcription.fileUrls().forEach(source -> requireSource(source, false));
                body = new BailianMediaWire.TranscriptionBody(transcription.model(), new BailianMediaWire.TranscriptionInput(transcription.fileUrls()),
                        new BailianMediaWire.TranscriptionParameters(transcription.channels(), transcription.languageHints(), transcription.diarization(), transcription.speakerCount()));
            }
            case QwenTranscriptionRequest transcription -> {
                path = GENERATION;
                requireQwenAudio(transcription.audio());
                body = new BailianMediaWire.QwenTranscriptionBody(transcription.model(),
                        new BailianMediaWire.QwenTranscriptionInput(List.of(new BailianMediaWire.QwenAudioMessage("user",
                                List.of(new BailianMediaWire.QwenAudioPart(transcription.audio()))))),
                        new BailianMediaWire.QwenTranscriptionParameters(new BailianMediaWire.AsrOptions(
                                transcription.language(), transcription.enableItn())));
            }
            case SpeechRequest speech -> {
                path = GENERATION;
                body = new BailianMediaWire.SpeechBody(speech.model(), new BailianMediaWire.SpeechInput(speech.text(), speech.voice(), speech.language()));
            }
            case TaskQuery task -> {
                path = "/tasks/" + task.taskId(); body = null;
            }
        }
        var builder = HttpRequest.newBuilder(nativeEndpoint(connection.endpoint(), path))
                .timeout(connection.timeout()).header("Authorization", "Bearer " + connection.credential())
                .header("Content-Type", "application/json");
        if (asynchronous) builder.header("X-DashScope-Async", "enable");
        if (stream) builder.header("X-DashScope-SSE", "enable").header("Accept", "text/event-stream");
        return body == null ? builder.GET().build() : builder.POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
    }

    private Result decode(BailianMediaWire.Envelope envelope, Request request) throws IOException {
        String originalTask = taskId(request);
        if (envelope == null) return result(Status.UNKNOWN, null, originalTask, List.of(), null, "media_response_incomplete");
        var usage = request instanceof QwenTranscriptionRequest ? qwenUsage(envelope.usage()) : usage(envelope.usage());
        if (present(envelope.code()) || envelope.statusCode() != null && envelope.statusCode() >= 400)
            return result(Status.FAILED, envelope.requestId(), originalTask, List.of(), usage, "media_provider_rejected");
        var output = envelope.output();
        if (output == null) return result(Status.UNKNOWN, envelope.requestId(), originalTask, List.of(), usage, "media_response_incomplete");
        if (present(output.code())) return result(Status.FAILED, envelope.requestId(), originalTask, List.of(), usage, "media_provider_rejected");
        if (request instanceof QwenTranscriptionRequest) {
            if (output.choices() == null || output.choices().size() != 1)
                return result(Status.UNKNOWN, envelope.requestId(), null, List.of(), usage, "media_response_incomplete");
            var choice = output.choices().getFirst();
            var message = choice.message();
            if (!"stop".equals(choice.finishReason()) || message == null || !"assistant".equals(message.role())
                    || message.content() == null || message.content().isEmpty())
                return result(Status.UNKNOWN, envelope.requestId(), null, List.of(), usage, "media_response_incomplete");
            var text = new StringBuilder();
            for (var part : message.content()) {
                if (part == null || part.text() == null || part.image() != null)
                    return result(Status.UNKNOWN, envelope.requestId(), null, List.of(), usage, "media_response_incomplete");
                text.append(part.text());
            }
            var transcript = new BailianMediaWire.QwenTranscript(List.of(new BailianMediaWire.QwenTranscriptTrack(
                    text.toString(), message.annotations() == null ? List.of() : List.copyOf(message.annotations()))));
            return result(Status.SUCCEEDED, envelope.requestId(), null, List.of(new Asset(AssetKind.TRANSCRIPT,
                    new BytesSource(JSON.writeValueAsBytes(transcript)), "application/json")), usage, null);
        }
        if (request instanceof VideoRequest || request instanceof TranscriptionRequest || request instanceof TaskQuery) {
            String id = output.taskId();
            if (!present(id) || originalTask != null && !originalTask.equals(id))
                return result(Status.UNKNOWN, envelope.requestId(), originalTask, List.of(), usage, "media_task_identity_invalid");
            var status = taskStatus(output.taskStatus());
            var assets = new ArrayList<Asset>();
            String reason = null;
            if (status == Status.SUCCEEDED) {
                boolean transcription = request instanceof TranscriptionRequest || request instanceof TaskQuery query && query.kind() == TaskKind.TRANSCRIPTION;
                if (transcription) {
                    if (output.results() == null || output.results().isEmpty()) status = Status.UNKNOWN;
                    else for (var item : output.results()) {
                        if (!"SUCCEEDED".equals(item.subtaskStatus())) { status = Status.FAILED; reason = "media_transcription_partial_failure"; }
                        else if (!present(item.transcriptionUrl())) status = Status.UNKNOWN;
                        else assets.add(asset(AssetKind.TRANSCRIPT, item.transcriptionUrl(), "application/json"));
                    }
                    if (output.taskMetrics() != null && output.taskMetrics().failed() != null && output.taskMetrics().failed() > 0) {
                        status = Status.FAILED; reason = "media_transcription_partial_failure";
                    }
                } else if (!present(output.videoUrl())) status = Status.UNKNOWN;
                else assets.add(asset(AssetKind.VIDEO, output.videoUrl(), "video/mp4"));
            }
            if (status == Status.FAILED && reason == null) reason = "media_provider_task_failed";
            if (status == Status.UNKNOWN) reason = "media_task_outcome_unknown";
            return result(status, envelope.requestId(), id, assets, usage, reason);
        }
        if (request instanceof ImageRequest) {
            var assets = new ArrayList<Asset>();
            if (output.choices() == null || output.choices().isEmpty()) return result(Status.UNKNOWN, envelope.requestId(), null, assets, usage, "media_response_incomplete");
            for (var choice : output.choices()) {
                if (!"stop".equals(choice.finishReason()) || choice.message() == null || choice.message().content() == null)
                    return result(Status.UNKNOWN, envelope.requestId(), null, assets, usage, "media_response_incomplete");
                for (var part : choice.message().content()) if (present(part.image())) assets.add(asset(AssetKind.IMAGE, part.image(), "image/png"));
            }
            return result(assets.isEmpty() ? Status.UNKNOWN : Status.SUCCEEDED, envelope.requestId(), null, assets, usage, assets.isEmpty() ? "media_response_incomplete" : null);
        }
        if (!"stop".equals(output.finishReason()) || output.audio() == null || !present(output.audio().url()))
            return result(Status.UNKNOWN, envelope.requestId(), null, List.of(), usage, "media_response_incomplete");
        return result(Status.SUCCEEDED, envelope.requestId(), null, List.of(asset(AssetKind.AUDIO, output.audio().url(), "audio/wav")), usage, null);
    }

    private Result httpFailure(HttpResponse<InputStream> response, Request request) {
        int status = response.statusCode();
        String requestId = null;
        try (var body = response.body()) {
            var rejection = JSON.readValue(readBounded(body), BailianMediaWire.Envelope.class);
            if (rejection != null) requestId = rejection.requestId();
        } catch (IOException | RuntimeException unavailable) {
            // HTTP拒绝已确定，错误正文缺失、超限或读取超时不能抹掉该事实；不记录敏感正文。
        }
        return result(status >= 400 && status < 500 && status != 408 ? Status.FAILED : Status.UNKNOWN,
                requestId, taskId(request), List.of(), null, status == 401 || status == 403 ? "media_authentication_rejected" : "media_provider_http_failure");
    }

    private static Status taskStatus(String value) {
        if (value == null) return Status.UNKNOWN;
        return switch (value) {
            case "PENDING" -> Status.PENDING;
            case "RUNNING" -> Status.RUNNING;
            case "SUCCEEDED" -> Status.SUCCEEDED;
            case "FAILED" -> Status.FAILED;
            case "CANCELED", "CANCELLED" -> Status.CANCELLED;
            default -> Status.UNKNOWN;
        };
    }

    private static Usage usage(BailianMediaWire.Usage value) {
        return value == null ? null : new Usage(value.inputTokens(), value.outputTokens(), value.imageCount(), value.videoCount(), value.characters(), value.duration());
    }

    private static Usage qwenUsage(BailianMediaWire.Usage value) {
        if (value == null) return null;
        Long output = value.outputTokens() != null ? value.outputTokens()
                : value.outputTokensDetails() == null ? null : value.outputTokensDetails().textTokens();
        if (value.inputTokens() != null && value.inputTokens() < 0 || output != null && output < 0
                || value.seconds() != null && value.seconds().signum() < 0)
            throw new IllegalArgumentException("media_usage_invalid");
        // input_tokens_details.text_tokens 不是音频输入总量，不补成 0，也不按音频时长推算。
        return new Usage(value.inputTokens(), output, null, null, null, value.seconds());
    }

    private Result result(Status status, String requestId, String taskId, List<Asset> assets, Usage usage, String reason) {
        return new Result(status, requestId, taskId, assets, usage, reason, clock.instant());
    }

    private static String taskId(Request request) { return request instanceof TaskQuery query ? query.taskId() : null; }
    private static boolean present(String value) { return value != null && !value.isBlank(); }

    /**
     * 仅接收原生百炼回执中的国内地域 OSS 或 dashscope 单桶加速地址，签名查询串原样保留。
     * 加速地址本身不证明资源在国内；宿主必须核实地域并改用国内地域端点后获取，SDK 不下载。
     */
    private static Asset asset(AssetKind kind, String value, String mediaType) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean https = "https".equals(uri.getScheme());
        boolean domestic = host != null && !host.contains(".oss-cn-hongkong.")
                && host.matches("[a-zA-Z0-9-]+\\.oss-cn-[a-zA-Z0-9-]+\\.aliyuncs\\.com");
        boolean accelerate = https && host != null
                && host.matches("dashscope-[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.oss-accelerate\\.aliyuncs\\.com");
        if (!(https || "http".equals(uri.getScheme())) || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != (https ? 443 : 80)
                || !(domestic || accelerate))
            throw new IllegalArgumentException("media_result_origin_not_domestic");
        return new Asset(kind, uri, mediaType);
    }

    private static void requireSource(String value, boolean image) {
        URI uri = URI.create(value);
        if (image && "data".equals(uri.getScheme()) && value.startsWith("data:image/") && value.contains(";base64,")) return;
        if ("oss".equals(uri.getScheme()) && "dashscope-instant".equals(uri.getHost())) return;
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("media_input_source_invalid");
    }

    private static void requireQwenAudio(String value) {
        if (value.startsWith("data:")) {
            if (value.length() > QwenTranscriptionRequest.MAXIMUM_ENCODED_AUDIO_BYTES)
                throw new IllegalArgumentException("media_audio_data_too_large");
            String prefix = value.startsWith("data:audio/mpeg;base64,") ? "data:audio/mpeg;base64,"
                    : value.startsWith("data:audio/wav;base64,") ? "data:audio/wav;base64," : null;
            if (prefix == null) throw new IllegalArgumentException("media_audio_data_type_invalid");
            String encoded = value.substring(prefix.length());
            byte[] audio = Base64.getDecoder().decode(encoded);
            if (audio.length == 0 || !Base64.getEncoder().encodeToString(audio).equals(encoded))
                throw new IllegalArgumentException("media_audio_data_encoding_invalid");
            return;
        }
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("media_audio_public_https_required");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.matches("[0-9.]+") || host.indexOf(':') >= 0)
            throw new IllegalArgumentException("media_audio_public_https_required");
    }

    private byte[] readBounded(InputStream body) throws IOException {
        byte[] value = body.readNBytes(maximumResponseBytes + 1);
        if (value.length > maximumResponseBytes) throw new IOException("media_response_too_large");
        return value;
    }

    private final class SpeechSubscription implements Flow.Subscription, Runnable {
        private final Connection connection;
        private final SpeechRequest request;
        private final Flow.Subscriber<? super SpeechEvent> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean started = new AtomicBoolean();
        private final java.time.Instant deadline;
        private volatile boolean cancelled;
        private volatile MediaHttpExchange exchange;

        SpeechSubscription(Connection connection, SpeechRequest request, Flow.Subscriber<? super SpeechEvent> subscriber) {
            this.connection = connection; this.request = request; this.subscriber = subscriber;
            this.deadline = clock.instant().plus(connection.timeout());
        }

        @Override public void request(long count) {
            if (cancelled) return;
            if (count <= 0) { cancel(); subscriber.onError(new IllegalArgumentException("media_stream_demand_invalid")); return; }
            demand.getAndUpdate(current -> current == Long.MAX_VALUE || Long.MAX_VALUE - current < count ? Long.MAX_VALUE : current + count);
            synchronized (this) { notifyAll(); }
            if (started.compareAndSet(false, true)) Thread.startVirtualThread(this);
        }

        @Override public void cancel() {
            cancelled = true;
            var current = exchange; if (current != null) current.close();
            synchronized (this) { notifyAll(); }
        }

        private boolean emit(SpeechEvent event) throws InterruptedException {
            synchronized (this) {
                while (!cancelled && demand.get() == 0) {
                    if (connection.cancelled().getAsBoolean() || !clock.instant().isBefore(deadline)) { cancel(); return false; }
                    wait(20);
                }
            }
            if (cancelled) return false;
            demand.getAndUpdate(current -> current == Long.MAX_VALUE ? current : current - 1);
            subscriber.onNext(event);
            return !cancelled;
        }

        @Override public void run() {
            Result terminal = null;
            Usage lastUsage = null;
            String lastRequestId = null;
            boolean sawAudio = false;
            try {
                if (connection.cancelled().getAsBoolean()) {
                    terminal = result(Status.NOT_SUBMITTED, null, null, List.of(), null, "media_cancelled_before_dispatch");
                } else {
                    HttpRequest prepared;
                    try { prepared = prepare(connection, request, true); }
                    catch (IllegalArgumentException | IOException invalid) {
                        terminal = result(Status.NOT_SUBMITTED, null, null, List.of(), null, "media_configuration_invalid");
                        prepared = null;
                    }
                    if (prepared != null && connection.cancelled().getAsBoolean()) {
                        terminal = result(Status.NOT_SUBMITTED, null, null, List.of(), null, "media_cancelled_before_dispatch");
                        prepared = null;
                    }
                    if (prepared != null) {
                        if (cancelled) return;
                        try { connection.beforeDispatch().beforeDispatch(); }
                        catch (RuntimeException failure) {
                            // Flow 的失败由订阅者接收；原异常不能伪装为已发送的供应商结果。
                            cancel(); subscriber.onError(failure); return;
                        }
                        if (cancelled) return;
                        if (connection.cancelled().getAsBoolean())
                            terminal = result(Status.NOT_SUBMITTED, null, null, List.of(), null, "media_cancelled_before_dispatch");
                        else if (!clock.instant().isBefore(deadline))
                            terminal = result(Status.NOT_SUBMITTED, null, null, List.of(), null, "media_deadline_before_dispatch");
                        else try (var current = new MediaHttpExchange(http, clock, connection, prepared, deadline)) {
                            exchange = current;
                            if (cancelled) return;
                            var response = current.response();
                            if (response.statusCode() < 200 || response.statusCode() >= 300) terminal = httpFailure(response, request);
                            else if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
                                terminal = result(Status.UNKNOWN, null, null, List.of(), null, "media_speech_stream_not_returned");
                            else try (var reader = new BufferedReader(new InputStreamReader(new BoundedStream(response.body(), maximumResponseBytes), StandardCharsets.UTF_8))) {
                                var data = new StringBuilder();
                                String line;
                                while (!cancelled && (line = reader.readLine()) != null) {
                                    if (line.startsWith("data:")) {
                                        if (!data.isEmpty()) data.append('\n');
                                        data.append(line.substring(5).stripLeading());
                                    }
                                    if (!line.isEmpty() || data.isEmpty()) continue;
                                    if ("[DONE]".contentEquals(data)) break;
                                    var envelope = JSON.readValue(data.toString(), BailianMediaWire.Envelope.class); data.setLength(0);
                                    lastRequestId = envelope.requestId();
                                    if (envelope.usage() != null) lastUsage = usage(envelope.usage());
                                    if (present(envelope.code()) || envelope.statusCode() != null && envelope.statusCode() >= 400) {
                                        terminal = result(Status.FAILED, lastRequestId, null, List.of(), lastUsage, "media_provider_rejected"); break;
                                    }
                                    var output = envelope.output();
                                    if (output == null) throw new IOException("media_speech_stream_invalid");
                                    if (output.audio() != null && present(output.audio().data())) {
                                        byte[] audio = Base64.getDecoder().decode(output.audio().data());
                                        sawAudio |= audio.length > 0;
                                        if (audio.length > 0 && !emit(new SpeechEvent.AudioChunk(audio, SPEECH_AUDIO_ENCODING, 24000, 1))) return;
                                    }
                                    if ("stop".equals(output.finishReason())) {
                                        List<Asset> assets = output.audio() != null && present(output.audio().url())
                                                ? List.of(asset(AssetKind.AUDIO, output.audio().url(), "audio/wav")) : List.of();
                                        terminal = result(sawAudio || !assets.isEmpty() ? Status.SUCCEEDED : Status.UNKNOWN,
                                                lastRequestId, null, assets, lastUsage, sawAudio || !assets.isEmpty() ? null : "media_speech_stream_incomplete");
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                terminal = result(Status.UNKNOWN, lastRequestId, null, List.of(), lastUsage, "media_speech_stream_incomplete");
            } finally { exchange = null; }
            if (cancelled) return;
            if (terminal == null) terminal = result(Status.UNKNOWN, lastRequestId, null, List.of(), lastUsage, "media_speech_stream_incomplete");
            try { if (emit(new SpeechEvent.Completed(terminal))) subscriber.onComplete(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); cancel(); }
        }
    }

    private static final class BoundedStream extends FilterInputStream {
        private final long maximum;
        private long read;
        BoundedStream(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        private int measured(int count) throws IOException {
            if (count > 0 && (read += count) > maximum) throw new IOException("media_stream_too_large");
            return count;
        }
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0) measured(1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { return measured(in.read(bytes, offset, length)); }
    }
}
