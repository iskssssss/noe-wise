package com.skillforge.runtime.adapter.model.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MiniMax 中国内地 HTTP 语音合成。只派发一次，不轮询、重试、下载或落盘。
 * <a href="https://platform.minimax.cn/docs/api-reference/speech-t2a-http">官方协议</a>
 * 中的 status=2 才表示合成结束；最终聚合音频不能重复作为流式分片发送。
 */
public final class MiniMaxSpeechProvider implements DomesticMediaProvider {
    private static final int SAMPLE_RATE = 32000;
    private static final String FORMAT = "mp3";
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient http;
    private final Clock clock;
    private final int maximumResponseBytes;

    public MiniMaxSpeechProvider(HttpClient http, Clock clock, int maximumResponseBytes) {
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumResponseBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("media_transport_configuration_invalid");
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override public Result execute(Connection connection, Request request) {
        Objects.requireNonNull(connection, "connection"); Objects.requireNonNull(request, "request");
        var deadline = clock.instant().plus(connection.timeout());
        if (!(request instanceof SpeechRequest speech))
            return result(Status.NOT_SUBMITTED, null, null, "media_request_not_supported");
        if (connection.cancelled().getAsBoolean())
            return result(Status.NOT_SUBMITTED, null, null, "media_cancelled_before_dispatch");
        HttpRequest prepared;
        try { prepared = prepare(connection, speech, false); }
        catch (IllegalArgumentException | IOException invalid) {
            return result(Status.NOT_SUBMITTED, null, null, "media_configuration_invalid");
        }
        if (connection.cancelled().getAsBoolean()) return result(Status.NOT_SUBMITTED, null, null, "media_cancelled_before_dispatch");
        // 宿主审计失败属于派发前技术失败，不能进入供应商 UNKNOWN 转换边界。
        connection.beforeDispatch().beforeDispatch();
        if (connection.cancelled().getAsBoolean()) return result(Status.NOT_SUBMITTED, null, null, "media_cancelled_before_dispatch");
        if (!clock.instant().isBefore(deadline)) return result(Status.NOT_SUBMITTED, null, null, "media_deadline_before_dispatch");
        try (var exchange = new MediaHttpExchange(http, clock, connection, prepared, deadline)) {
            var response = exchange.response();
            try (var body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    return httpFailure(response.statusCode());
                return decode(JSON.readValue(readBounded(body), Envelope.class));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(Status.UNKNOWN, null, null, "media_outcome_unknown");
        } catch (Exception failure) {
            return result(Status.UNKNOWN, null, null, "media_outcome_unknown");
        }
    }

    @Override public Flow.Publisher<SpeechEvent> streamSpeech(Connection connection, SpeechRequest request) {
        Objects.requireNonNull(connection, "connection"); Objects.requireNonNull(request, "request");
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

    /** 当前国内官方地址；拒绝国际站、重定向入口及携带查询参数的旧接口。 */
    public static URI nativeEndpoint(URI endpoint) {
        if (!"https".equals(endpoint.getScheme()) || !"api.minimax.cn".equals(endpoint.getHost())
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || endpoint.getPort() != -1 && endpoint.getPort() != 443
                || !List.of("", "/", "/v1", "/v1/").contains(endpoint.getPath()))
            throw new IllegalArgumentException("media_domestic_endpoint_required");
        return URI.create("https://api.minimax.cn/v1/t2a_v2");
    }

    private HttpRequest prepare(Connection connection, SpeechRequest request, boolean stream) throws IOException {
        if (request.text().codePointCount(0, request.text().length()) >= 10000)
            throw new IllegalArgumentException("media_speech_text_too_long");
        var body = new SpeechBody(request.model(), request.text(), stream,
                stream ? new StreamOptions(true) : null, new VoiceSetting(request.voice()),
                new AudioSetting(SAMPLE_RATE, 128000, FORMAT, 1), request.language(), "hex");
        return HttpRequest.newBuilder(nativeEndpoint(connection.endpoint()))
                .timeout(connection.timeout()).header("Authorization", "Bearer " + connection.credential())
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
    }

    private Result decode(Envelope envelope) {
        if (envelope == null) return result(Status.UNKNOWN, null, null, "media_response_incomplete");
        var usage = usage(envelope.extraInfo());
        var state = responseState(envelope);
        if (state != null) return result(state, envelope.traceId(), usage,
                state == Status.FAILED ? "media_provider_rejected" : "media_response_incomplete");
        var data = envelope.data();
        if (data == null || !Integer.valueOf(2).equals(data.status()) || !present(data.audio())
                || !requestedFormat(envelope.extraInfo()))
            return result(Status.UNKNOWN, envelope.traceId(), usage, "media_response_incomplete");
        byte[] audio = HexFormat.of().parseHex(data.audio());
        if (audio.length == 0) return result(Status.UNKNOWN, envelope.traceId(), usage, "media_response_incomplete");
        return new Result(Status.SUCCEEDED, envelope.traceId(), null,
                List.of(new Asset(AssetKind.AUDIO, new BytesSource(audio), "audio/mpeg")), usage, null, clock.instant());
    }

    /** null 表示供应商明确返回成功码，未知或缺失的状态不推定成功。 */
    private static Status responseState(Envelope envelope) {
        if (envelope.baseResp() == null || envelope.baseResp().statusCode() == null) return Status.UNKNOWN;
        return envelope.baseResp().statusCode() == 0 ? null : Status.FAILED;
    }

    private static boolean requestedFormat(ExtraInfo info) {
        return info == null || (info.audioFormat() == null || FORMAT.equals(info.audioFormat()))
                && (info.audioSampleRate() == null || info.audioSampleRate() == SAMPLE_RATE)
                && (info.audioChannel() == null || info.audioChannel() == 1);
    }

    private static Usage usage(ExtraInfo info) {
        if (info == null || info.usageCharacters() == null && info.audioLength() == null) return null;
        if (info.usageCharacters() != null && info.usageCharacters() < 0 || info.audioLength() != null && info.audioLength() < 0)
            throw new IllegalArgumentException("media_usage_invalid");
        return new Usage(null, null, null, null, info.usageCharacters(),
                info.audioLength() == null ? null : BigDecimal.valueOf(info.audioLength()).movePointLeft(3));
    }

    private Result result(Status status, String traceId, Usage usage, String reason) {
        return new Result(status, traceId, null, List.of(), usage, reason, clock.instant());
    }

    private Result httpFailure(int status) {
        return result(status >= 400 && status < 500 && status != 408 ? Status.FAILED : Status.UNKNOWN,
                null, null, status == 401 || status == 403 ? "media_authentication_rejected" : "media_provider_http_failure");
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    private byte[] readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(maximumResponseBytes);
        if (body.read() != -1) throw new IOException("media_response_too_large");
        return bytes;
    }

    private final class SpeechSubscription implements Flow.Subscription, Runnable {
        private final Connection connection;
        private final SpeechRequest request;
        private final Flow.Subscriber<? super SpeechEvent> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean started = new AtomicBoolean();
        private final Instant deadline;
        private volatile boolean cancelled;
        private volatile MediaHttpExchange exchange;

        SpeechSubscription(Connection connection, SpeechRequest request, Flow.Subscriber<? super SpeechEvent> subscriber) {
            this.connection = connection; this.request = request; this.subscriber = subscriber;
            deadline = clock.instant().plus(connection.timeout());
        }

        @Override public void request(long count) {
            if (cancelled) return;
            if (count <= 0) { cancel(); subscriber.onError(new IllegalArgumentException("media_stream_demand_invalid")); return; }
            demand.getAndUpdate(current -> Long.MAX_VALUE - current < count ? Long.MAX_VALUE : current + count);
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
                    if (!clock.instant().isBefore(deadline)) { cancel(); return false; }
                    wait(20);
                }
            }
            if (cancelled) return false;
            if (demand.get() != Long.MAX_VALUE) demand.decrementAndGet();
            subscriber.onNext(event);
            return !cancelled;
        }

        @Override public void run() {
            Result terminal = null;
            String traceId = null;
            Usage lastUsage = null;
            boolean sawAudio = false;
            var completeAudio = new ByteArrayOutputStream(Math.min(maximumResponseBytes, 8192));
            try {
                if (connection.cancelled().getAsBoolean())
                    terminal = result(Status.NOT_SUBMITTED, null, null, "media_cancelled_before_dispatch");
                else {
                    HttpRequest prepared = null;
                    try { prepared = prepare(connection, request, true); }
                    catch (IllegalArgumentException | IOException invalid) {
                        terminal = result(Status.NOT_SUBMITTED, null, null, "media_configuration_invalid");
                    }
                    if (prepared != null && connection.cancelled().getAsBoolean()) {
                        terminal = result(Status.NOT_SUBMITTED, null, null, "media_cancelled_before_dispatch");
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
                            terminal = result(Status.NOT_SUBMITTED, null, null, "media_cancelled_before_dispatch");
                        else if (!clock.instant().isBefore(deadline))
                            terminal = result(Status.NOT_SUBMITTED, null, null, "media_deadline_before_dispatch");
                        else try (var current = new MediaHttpExchange(http, clock, connection, prepared, deadline)) {
                            exchange = current;
                            if (cancelled) return;
                            var response = current.response();
                            if (response.statusCode() < 200 || response.statusCode() >= 300) terminal = httpFailure(response.statusCode());
                            else if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                                var envelope = JSON.readValue(readBounded(response.body()), Envelope.class);
                                terminal = envelope != null && responseState(envelope) == Status.FAILED
                                        ? result(Status.FAILED, envelope.traceId(), usage(envelope.extraInfo()), "media_provider_rejected")
                                        : result(Status.UNKNOWN, null, null, "media_speech_stream_not_returned");
                            } else try (var reader = new BufferedReader(new InputStreamReader(new BoundedStream(response.body(), maximumResponseBytes), StandardCharsets.UTF_8))) {
                                var data = new StringBuilder();
                                String line;
                                while (!cancelled && (line = reader.readLine()) != null) {
                                    if (line.startsWith("data:")) {
                                        if (!data.isEmpty()) data.append('\n');
                                        data.append(line.substring(5).stripLeading());
                                    }
                                    if (!line.isEmpty() || data.isEmpty()) continue;
                                    if ("[DONE]".contentEquals(data)) break;
                                    var envelope = JSON.readValue(data.toString(), Envelope.class); data.setLength(0);
                                    if (envelope == null) throw new IOException("media_speech_stream_invalid");
                                    if (present(envelope.traceId())) traceId = envelope.traceId();
                                    if (envelope.extraInfo() != null) lastUsage = usage(envelope.extraInfo());
                                    var state = responseState(envelope);
                                    if (state != null) {
                                        terminal = result(state, traceId, lastUsage, state == Status.FAILED ? "media_provider_rejected" : "media_speech_stream_incomplete");
                                        break;
                                    }
                                    var output = envelope.data();
                                    if (output == null || output.status() == null || output.status() != 1 && output.status() != 2
                                            || !requestedFormat(envelope.extraInfo())) throw new IOException("media_speech_stream_invalid");
                                    if (present(output.audio()) && (output.status() == 1 || !sawAudio)) {
                                        byte[] audio = HexFormat.of().parseHex(output.audio());
                                        if (audio.length > 0) {
                                            if (audio.length > maximumResponseBytes - completeAudio.size())
                                                throw new IOException("media_stream_too_large");
                                            completeAudio.writeBytes(audio);
                                            sawAudio = true;
                                            if (!emit(new SpeechEvent.AudioChunk(audio, FORMAT, SAMPLE_RATE, 1))) return;
                                        }
                                    }
                                    if (output.status() == 2) {
                                        terminal = sawAudio && !connection.cancelled().getAsBoolean()
                                                ? new Result(Status.SUCCEEDED, traceId, null,
                                                        List.of(new Asset(AssetKind.AUDIO, new BytesSource(completeAudio.toByteArray()), "audio/mpeg")),
                                                        lastUsage, null, clock.instant())
                                                : result(Status.UNKNOWN, traceId, lastUsage, "media_speech_stream_incomplete");
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
                terminal = result(Status.UNKNOWN, traceId, lastUsage, "media_speech_stream_incomplete");
            } finally { exchange = null; }
            if (cancelled) return;
            if (terminal == null) terminal = result(Status.UNKNOWN, traceId, lastUsage, "media_speech_stream_incomplete");
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

    private record SpeechBody(String model, String text, boolean stream, StreamOptions streamOptions,
            VoiceSetting voiceSetting, AudioSetting audioSetting, String languageBoost, String outputFormat) { }
    private record StreamOptions(boolean excludeAggregatedAudio) { }
    private record VoiceSetting(String voiceId) { }
    private record AudioSetting(int sampleRate, int bitrate, String format, int channel) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope(Data data, ExtraInfo extraInfo, String traceId, BaseResp baseResp) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Data(String audio, Integer status) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExtraInfo(Long audioLength, Integer audioSampleRate, String audioFormat, Integer audioChannel, Long usageCharacters) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BaseResp(Integer statusCode) { }
}
