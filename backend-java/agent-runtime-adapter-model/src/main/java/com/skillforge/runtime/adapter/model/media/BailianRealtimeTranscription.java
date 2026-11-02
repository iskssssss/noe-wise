package com.skillforge.runtime.adapter.model.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Connection;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Result;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Usage;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 百炼北京 Paraformer 实时识别任务：一个原任务 ID、一条 WebSocket、无自动重连。
 * 输入为单声道有符号 16 位小端 PCM；task-started 之后才发送音频，task-finished 才成功。
 * 官方协议：<a href="https://help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service">WebSocket</a>、
 * <a href="https://help.aliyun.com/en/model-studio/paraformer-client-events">客户端事件</a>、
 * <a href="https://help.aliyun.com/en/model-studio/paraformer-server-events">服务端事件</a>。
 */
public final class BailianRealtimeTranscription {
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient http;
    private final Clock clock;
    private final int maximumFrameBytes;

    public BailianRealtimeTranscription(HttpClient http, Clock clock, int maximumFrameBytes) {
        this.http = Objects.requireNonNull(http); this.clock = Objects.requireNonNull(clock);
        if (maximumFrameBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("media_transport_configuration_invalid");
        this.maximumFrameBytes = maximumFrameBytes;
    }

    /** taskId 由宿主登记后提供；采样率与输入 PCM 一致，不在 SDK 中偷偷重采样。 */
    public record RealtimeRequest(String taskId, String model, int sampleRateHz, List<String> languageHints) {
        public RealtimeRequest {
            Objects.requireNonNull(taskId, "taskId");
            if (!UUID.fromString(taskId).toString().equals(taskId)) throw new IllegalArgumentException("media_task_id_invalid");
            if (model == null || model.isBlank() || sampleRateHz <= 0) throw new IllegalArgumentException("media_transcription_configuration_invalid");
            languageHints = languageHints == null ? null : List.copyOf(languageHints);
        }
        @Override public String toString() { return "RealtimeTranscriptionRequest[model=" + model + "]"; }
    }

    public record Sentence(String text, Long beginMillis, Long endMillis, boolean finalSegment) {
        public Sentence { Objects.requireNonNull(text, "text"); }
        @Override public String toString() { return "TranscriptSentence[final=" + finalSegment + "]"; }
    }
    public record Outcome(Result result, String text, List<Sentence> finalSentences) {
        public Outcome { Objects.requireNonNull(result); Objects.requireNonNull(text); finalSentences = List.copyOf(finalSentences); }
        @Override public String toString() { return "TranscriptionOutcome[status=" + result.status() + "]"; }
    }
    public sealed interface TranscriptEvent permits TranscriptEvent.Started, TranscriptEvent.Segment, TranscriptEvent.Completed {
        record Started(String taskId) implements TranscriptEvent { }
        record Segment(Sentence sentence) implements TranscriptEvent { }
        record Completed(Outcome outcome) implements TranscriptEvent { }
    }

    /** 调用方必须保存 completion 的最终事实；cancel 不代表供应商撤销了已识别或已计费内容。 */
    public interface Session extends AutoCloseable {
        String taskId();
        CompletionStage<Void> ready();
        CompletionStage<Void> sendAudio(byte[] pcm);
        CompletionStage<Outcome> finish();
        CompletionStage<Outcome> completion();
        void cancel();
        @Override default void close() { cancel(); }
    }

    /** 创建一个实际会话；握手、发送和整个任务共用 connection.timeout 的单一总期限。 */
    public Session open(Connection connection, RealtimeRequest request, Consumer<TranscriptEvent> events) {
        Objects.requireNonNull(connection); Objects.requireNonNull(request); Objects.requireNonNull(events);
        var session = new RecognitionSession(connection, request, events);
        session.start();
        return session;
    }

    private final class RecognitionSession implements Session, WebSocket.Listener {
        private final Connection connection;
        private final RealtimeRequest request;
        private final Consumer<TranscriptEvent> events;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean finishing = new AtomicBoolean();
        private final List<Sentence> finalSentences = new ArrayList<>();
        private final StringBuilder frame = new StringBuilder();
        private final java.time.Instant deadline;
        private CompletableFuture<Void> writes = ready;
        private volatile WebSocket socket;
        private volatile CompletableFuture<WebSocket> connecting;
        private volatile Thread watchdog;
        private volatile boolean runSent;
        private volatile Usage lastUsage;

        RecognitionSession(Connection connection, RealtimeRequest request, Consumer<TranscriptEvent> events) {
            this.connection = connection; this.request = request; this.events = events;
            deadline = clock.instant().plus(connection.timeout());
        }

        void start() {
            if (connection.cancelled().getAsBoolean()) { end(Status.NOT_SUBMITTED, "media_cancelled_before_dispatch"); return; }
            URI endpoint;
            try {
                var nativeRoot = BailianMediaProvider.nativeEndpoint(connection.endpoint(), "/unused");
                endpoint = URI.create("wss://" + nativeRoot.getHost() + "/api-ws/v1/inference");
                connecting = http.newWebSocketBuilder().connectTimeout(connection.timeout())
                        .header("Authorization", "Bearer " + connection.credential()).buildAsync(endpoint, this);
                connecting.whenComplete((opened, failure) -> {
                    if (failure != null) end(runSent ? Status.UNKNOWN : Status.NOT_SUBMITTED, "media_transcription_connection_failed");
                    else if (terminal.get()) opened.abort();
                });
            } catch (IllegalArgumentException rejected) { end(Status.NOT_SUBMITTED, "media_configuration_invalid"); return; }
            watchdog = Thread.startVirtualThread(() -> {
                try {
                    while (!terminal.get()) {
                        if (connection.cancelled().getAsBoolean() || !clock.instant().isBefore(deadline)) {
                            end(runSent ? Status.UNKNOWN : Status.NOT_SUBMITTED, "media_transcription_outcome_unknown"); return;
                        }
                        Thread.sleep(20);
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }

        @Override public String taskId() { return request.taskId(); }
        @Override public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }
        @Override public CompletionStage<Outcome> completion() { return completion.minimalCompletionStage(); }

        @Override public synchronized CompletionStage<Void> sendAudio(byte[] pcm) {
            Objects.requireNonNull(pcm, "pcm");
            if (pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > maximumFrameBytes)
                return CompletableFuture.failedFuture(new IllegalArgumentException("media_pcm_frame_invalid"));
            if (finishing.get() || terminal.get()) return CompletableFuture.failedFuture(new IllegalStateException("media_transcription_input_closed"));
            byte[] frozen = pcm.clone();
            writes = writes.thenCompose(ignored -> {
                if (terminal.get()) return CompletableFuture.failedFuture(new IllegalStateException("media_transcription_closed"));
                return socket.sendBinary(ByteBuffer.wrap(frozen), true).thenApply(sent -> (Void) null);
            });
            writes.whenComplete((ignored, failure) -> { if (failure != null && !terminal.get()) end(Status.UNKNOWN, "media_transcription_send_unknown"); });
            return writes.minimalCompletionStage();
        }

        @Override public synchronized CompletionStage<Outcome> finish() {
            if (finishing.compareAndSet(false, true) && !terminal.get()) {
                writes = writes.thenCompose(ignored -> {
                    if (terminal.get()) return CompletableFuture.failedFuture(new IllegalStateException("media_transcription_closed"));
                    try {
                        return socket.sendText(JSON.writeValueAsString(new FinishCommand(
                                new CommandHeader("finish-task", request.taskId(), "duplex"), new FinishPayload(new EmptyInput()))), true).thenApply(sent -> (Void) null);
                    } catch (IOException invalid) { return CompletableFuture.failedFuture(invalid); }
                });
                writes.whenComplete((ignored, failure) -> { if (failure != null && !terminal.get()) end(Status.UNKNOWN, "media_transcription_finish_unknown"); });
            }
            return completion();
        }

        @Override public void cancel() { end(runSent ? Status.UNKNOWN : Status.NOT_SUBMITTED, "media_transcription_cancelled"); }

        @Override public void onOpen(WebSocket opened) {
            socket = opened;
            if (terminal.get()) { opened.abort(); return; }
            opened.request(1);
            try {
                var run = new RunCommand(new CommandHeader("run-task", request.taskId(), "duplex"),
                        new RunPayload("audio", "asr", "recognition", request.model(),
                                new Parameters("pcm", request.sampleRateHz(), request.languageHints()), new EmptyInput()));
                String command = JSON.writeValueAsString(run);
                runSent = true;
                opened.sendText(command, true).whenComplete((sent, failure) -> {
                    if (failure != null) end(Status.UNKNOWN, "media_transcription_start_unknown");
                });
            } catch (IOException invalid) { end(Status.NOT_SUBMITTED, "media_configuration_invalid"); }
        }

        @Override public CompletionStage<?> onText(WebSocket source, CharSequence data, boolean last) {
            if (terminal.get()) return null;
            try {
                frame.append(data);
                if (frame.length() > maximumFrameBytes) throw new IOException("media_transcription_frame_too_large");
                if (last) {
                    String text = frame.toString(); frame.setLength(0);
                    if (text.getBytes(StandardCharsets.UTF_8).length > maximumFrameBytes) throw new IOException("media_transcription_frame_too_large");
                    var message = JSON.readValue(text, ServerMessage.class);
                    if (message.header() == null || !request.taskId().equals(message.header().taskId())) {
                        end(Status.UNKNOWN, "media_task_identity_invalid"); return null;
                    }
                    switch (message.header().event()) {
                        case "task-started" -> {
                            if (ready.complete(null)) events.accept(new TranscriptEvent.Started(request.taskId()));
                        }
                        case "result-generated" -> {
                            if (!ready.isDone() || message.payload() == null || message.payload().output() == null
                                    || message.payload().output().sentence() == null) throw new IOException("media_transcription_result_invalid");
                            var sentence = message.payload().output().sentence();
                            if (message.payload().usage() != null)
                                lastUsage = new Usage(null, null, null, null, null, message.payload().usage().duration());
                            if (!Boolean.TRUE.equals(sentence.heartbeat())) {
                                if (sentence.text() == null || sentence.sentenceEnd() == null) throw new IOException("media_transcription_sentence_invalid");
                                var segment = new Sentence(sentence.text(), sentence.beginTime(), sentence.endTime(), sentence.sentenceEnd());
                                if (segment.finalSegment()) synchronized (finalSentences) { finalSentences.add(segment); }
                                events.accept(new TranscriptEvent.Segment(segment));
                            }
                        }
                        case "task-finished" -> {
                            if (!ready.isDone()) end(Status.UNKNOWN, "media_transcription_sequence_invalid");
                            else end(Status.SUCCEEDED, null);
                        }
                        case "task-failed" -> end(Status.FAILED, "media_transcription_provider_failed");
                        default -> throw new IOException("media_transcription_event_unknown");
                    }
                }
                if (!terminal.get()) source.request(1);
            } catch (Exception failure) { end(Status.UNKNOWN, "media_transcription_response_invalid"); }
            return null;
        }

        @Override public CompletionStage<?> onBinary(WebSocket source, ByteBuffer data, boolean last) {
            end(Status.UNKNOWN, "media_transcription_response_invalid"); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket source, int status, String reason) {
            end(Status.UNKNOWN, "media_transcription_stream_incomplete"); return null;
        }
        @Override public void onError(WebSocket source, Throwable failure) { end(Status.UNKNOWN, "media_transcription_outcome_unknown"); }

        private void end(Status status, String reason) {
            if (!terminal.compareAndSet(false, true)) return;
            var currentSocket = socket; if (currentSocket != null) currentSocket.abort();
            var currentConnecting = connecting; if (currentConnecting != null && !currentConnecting.isDone()) currentConnecting.cancel(true);
            var timer = watchdog; if (timer != null) timer.interrupt();
            ready.completeExceptionally(new IllegalStateException("media_transcription_closed"));
            List<Sentence> sentences;
            synchronized (finalSentences) { sentences = List.copyOf(finalSentences); }
            String text = String.join("", sentences.stream().map(Sentence::text).toList());
            var outcome = new Outcome(new Result(status, null, request.taskId(), List.of(), lastUsage, reason, clock.instant()), text, sentences);
            completion.complete(outcome);
            events.accept(new TranscriptEvent.Completed(outcome));
        }
    }

    private record CommandHeader(String action, String taskId, String streaming) { }
    private record EmptyInput() { }
    private record Parameters(String format, int sampleRate, List<String> languageHints) { }
    private record RunPayload(String taskGroup, String task, String function, String model, Parameters parameters, EmptyInput input) { }
    private record RunCommand(CommandHeader header, RunPayload payload) { }
    private record FinishPayload(EmptyInput input) { }
    private record FinishCommand(CommandHeader header, FinishPayload payload) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerHeader(String taskId, String event, String errorCode, String errorMessage) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerUsage(BigDecimal duration) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerSentence(String text, Long beginTime, Long endTime, Boolean sentenceEnd, Boolean heartbeat) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerOutput(ServerSentence sentence) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerPayload(ServerOutput output, ServerUsage usage) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServerMessage(ServerHeader header, ServerPayload payload) { }
}
