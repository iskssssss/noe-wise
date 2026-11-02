package com.skillforge.core.model.media.realtime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import java.math.BigDecimal;
import java.time.Instant;

/** 实时连接的公开事实；连接是否仍由当前宿主持有与供应商终态分别表示。 */
public record ModelRealtimeSession(String id, String operationId, String kind, String modelId,
        long modelRevision, String providerTaskId, Status status, boolean connected, boolean finishing,
        long nextAudioSequence, String partialText, String finalText, String reasonCode,
        Instant createdAt, Instant deadline, Instant updatedAt, Usage usage, Asset asset) {
    public record OpenAsr(String operationId, String id, ModelWrite payload, int sampleRateHz, String language) {
        @Override public String toString() { return "RealtimeAsrOpen[REDACTED]"; }
    }
    public record OpenSpeech(String operationId, String id, ModelWrite payload,
            String text, String voice, String language) {
        @Override public String toString() { return "RealtimeSpeechOpen[REDACTED]"; }
    }
    public record Usage(Long inputTokens, Long outputTokens, Long characters,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal durationSeconds) { }
    public record Asset(String id, String mediaType, long sizeBytes, String name) { }
    /** SSE 音频分片保留真实编码；dataBase64 不表示完整音频文件。 */
    public record Audio(String dataBase64, String encoding, int sampleRateHz, int channels) {
        @Override public String toString() { return "RealtimeAudio[REDACTED]"; }
    }
}
