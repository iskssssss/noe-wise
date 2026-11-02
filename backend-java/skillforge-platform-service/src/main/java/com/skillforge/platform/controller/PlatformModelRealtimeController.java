package com.skillforge.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.model.media.ModelMediaJob;
import com.skillforge.core.model.media.realtime.ModelRealtimeService;
import com.skillforge.core.model.media.realtime.ModelRealtimeSession;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 已鉴权平台管理员的实时语音入口；PCM 分片使用稳定 sequence，SSE 重读不会重新调用供应商。 */
@RestController
@RequestMapping("/api/v1/admin/models/media/realtime")
public class PlatformModelRealtimeController {
    private final ModelRealtimeService sessions;
    public PlatformModelRealtimeController(ModelRealtimeService sessions) { this.sessions = sessions; }

    @RequirePermission("foundation.models.run")
    @PostMapping("/asr/open")
    public ApiResponse<ModelRealtimeSession> open(@RequestBody ModelRealtimeSession.OpenAsr command) {
        return ApiResponses.wrap(sessions.openAsr(owner(), command));
    }

    @RequirePermission("foundation.models.run")
    @PostMapping(value = "/asr/audio", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponse<ModelRealtimeSession> audio(@RequestParam String id, @RequestParam long sequence,
            jakarta.servlet.http.HttpServletRequest request) throws IOException {
        byte[] pcm = request.getInputStream().readNBytes(1048577);
        return ApiResponses.wrap(sessions.audio(owner(), id, sequence, pcm));
    }

    @RequirePermission("foundation.models.run")
    @PostMapping("/asr/finish")
    public ApiResponse<ModelRealtimeSession> finish(@RequestBody SessionCommand command) {
        return ApiResponses.wrap(sessions.finish(owner(), command.id()));
    }

    @RequirePermission("foundation.models.read")
    @GetMapping("/detail")
    public ApiResponse<ModelRealtimeSession> detail(@RequestParam String id) {
        return ApiResponses.wrap(sessions.detail(owner(), id));
    }

    @RequirePermission("foundation.models.run")
    @PostMapping("/stop")
    public ApiResponse<ModelRealtimeSession> stop(@RequestBody SessionCommand command) {
        return ApiResponses.wrap(sessions.stop(owner(), command.id()));
    }

    @RequirePermission("foundation.models.read")
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam String id) throws Exception {
        var emitter = new SseEmitter(600000L);
        var registration = new AtomicReference<AutoCloseable>();
        var listener = listener(emitter, () -> close(registration.get()), ignored -> { });
        registration.set(sessions.listen(owner(), id, listener));
        emitter.onCompletion(() -> close(registration.get()));
        emitter.onTimeout(() -> close(registration.get()));
        return emitter;
    }

    @RequirePermission("foundation.models.run")
    @PostMapping(value = "/tts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter speech(@RequestBody ModelRealtimeSession.OpenSpeech command) {
        var actor = owner();
        var emitter = new SseEmitter(600000L);
        var id = new AtomicReference<String>();
        var listener = listener(emitter, () -> {
            String current = id.get();
            if (current != null) sessions.stop(actor, current);
        }, value -> id.set(value.id()));
        var snapshot = sessions.streamSpeech(actor, command, listener);
        id.set(snapshot.id());
        // 客户端终止响应只停止本地等待，SDK 负责取消连接，不声明云端撤销。
        emitter.onError(failure -> sessions.stop(actor, snapshot.id()));
        emitter.onTimeout(() -> sessions.stop(actor, snapshot.id()));
        return emitter;
    }

    @RequirePermission("foundation.models.read")
    @GetMapping("/assets/content")
    public ResponseEntity<StreamingResponseBody> content(@RequestParam String id) throws IOException {
        var download = sessions.download(owner(), id);
        StreamingResponseBody body = output -> { try (var input = download.content()) { input.transferTo(output); } };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType())).contentLength(download.sizeBytes())
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.name()).build().toString()).body(body);
    }

    private static ModelRealtimeService.Listener listener(SseEmitter emitter, Runnable disconnected,
            java.util.function.Consumer<ModelRealtimeSession> snapshotReceived) {
        return new ModelRealtimeService.Listener() {
            @Override public void snapshot(ModelRealtimeSession session) { snapshotReceived.accept(session); send("session", session); }
            @Override public void audio(ModelRealtimeSession.Audio chunk) { send("audio", chunk); }
            @Override public void complete() { emitter.complete(); }
            private void send(String name, Object value) {
                try { emitter.send(SseEmitter.event().name(name).data(value)); }
                catch (IOException failure) { disconnected.run(); emitter.completeWithError(failure); }
            }
        };
    }
    private static void close(AutoCloseable registration) {
        if (registration == null) return;
        try { registration.close(); }
        catch (Exception failure) { throw new IllegalStateException("model_realtime_listener_close_failed", failure); }
    }
    public record SessionCommand(String id) { }
    private static ModelMediaJob.Owner owner() { return new ModelMediaJob.Owner(null, StpUtil.getLoginIdAsString()); }
}
