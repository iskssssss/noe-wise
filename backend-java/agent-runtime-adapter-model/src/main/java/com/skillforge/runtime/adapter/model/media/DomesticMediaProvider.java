package com.skillforge.runtime.adapter.model.media;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/** 国内媒体模型的一次调用或原任务查询；宿主拥有身份、授权、预算、持久化和媒体下载。 */
public interface DomesticMediaProvider {
    /** 只派发一次；网络或不完整回执返回 UNKNOWN，不自动重试、轮询或重建任务。 */
    Result execute(Connection connection, Request request);

    /** 逐段返回真实音频；仅 Completed 中的结果可以声明完整合成成功。 */
    Flow.Publisher<SpeechEvent> streamSpeech(Connection connection, SpeechRequest request);

    /** 凭据仅供可信宿主装配，不进入日志或公开 API。endpoint 为已选服务地址。 */
    record Connection(URI endpoint, String credential, Duration timeout, BooleanSupplier cancelled,
            DispatchObserver beforeDispatch) {
        public Connection {
            Objects.requireNonNull(endpoint, "endpoint");
            requireText(credential, "credential");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("media_timeout_invalid");
            Objects.requireNonNull(cancelled, "cancelled");
            Objects.requireNonNull(beforeDispatch, "beforeDispatch");
        }
        /** 直接使用 SDK 且不接宿主审计时，明确采用无操作观察器。 */
        public Connection(URI endpoint, String credential, Duration timeout, BooleanSupplier cancelled) {
            this(endpoint, credential, timeout, cancelled, DispatchObserver.NOOP);
        }
        @Override public String toString() { return "MediaConnection[REDACTED]"; }
    }

    /** HTTP 实际发送前同步调用一次；宿主登记失败须抛出异常以阻止发送，SDK 不重试。 */
    @FunctionalInterface
    interface DispatchObserver {
        DispatchObserver NOOP = () -> { };
        void beforeDispatch();
    }

    sealed interface Request permits ImageRequest, VideoRequest, TranscriptionRequest, QwenTranscriptionRequest, SpeechRequest, TaskQuery { }

    /** referenceImages 保序；空列表表示文生图，非空表示图片编辑或参考图生成。 */
    record ImageRequest(String model, String prompt, List<String> referenceImages,
            String size, Integer count, String negativePrompt, Long seed,
            Boolean promptExtend, Boolean watermark) implements Request {
        public ImageRequest {
            requireText(model, "model"); requireText(prompt, "prompt");
            referenceImages = List.copyOf(referenceImages);
            if (count != null && count <= 0) throw new IllegalArgumentException("media_image_count_invalid");
        }
        @Override public String toString() { return "ImageRequest[model=" + model + "]"; }
    }

    /** Wan 3 原生媒体素材类型，不把文件、首尾帧和普通参考图混为一类。 */
    enum VideoInputKind { FIRST_FRAME, LAST_FRAME, REFERENCE_IMAGE, REFERENCE_VIDEO, REFERENCE_AUDIO, FILE, LINK }
    record VideoInput(VideoInputKind kind, String source) {
        public VideoInput { Objects.requireNonNull(kind, "kind"); requireText(source, "source"); }
        @Override public String toString() { return "VideoInput[kind=" + kind + "]"; }
    }

    /** 视频任务显式使用 Wan 3 的 input.media 契约；不按型号猜测旧版请求格式。 */
    record VideoRequest(String model, String prompt, List<VideoInput> media,
            String resolution, String ratio, Integer durationSeconds, Boolean audio,
            Long seed, Boolean promptExtend, Boolean watermark) implements Request {
        /** 由供应商根据输入素材选择输出时长，不表示取消或未知时长。 */
        public static final int AUTOMATIC_DURATION_SECONDS = -1;

        public VideoRequest {
            requireText(model, "model"); media = List.copyOf(media);
            if ((prompt == null || prompt.isBlank()) && media.isEmpty())
                throw new IllegalArgumentException("media_video_input_required");
            if (durationSeconds != null && durationSeconds != AUTOMATIC_DURATION_SECONDS && durationSeconds <= 0)
                throw new IllegalArgumentException("media_video_duration_invalid");
        }
        @Override public String toString() { return "VideoRequest[model=" + model + "]"; }
    }

    /** 文件语音识别使用稳定远端资源；结果为原任务的受控转写结果文件。 */
    record TranscriptionRequest(String model, List<String> fileUrls, List<Integer> channels,
            List<String> languageHints, Boolean diarization, Integer speakerCount) implements Request {
        public TranscriptionRequest {
            requireText(model, "model"); fileUrls = List.copyOf(fileUrls);
            if (fileUrls.size() != 1) throw new IllegalArgumentException("media_audio_file_count_invalid");
            channels = channels == null ? null : List.copyOf(channels);
            languageHints = languageHints == null ? null : List.copyOf(languageHints);
            if (channels != null && channels.stream().anyMatch(channel -> channel == null || channel < 0))
                throw new IllegalArgumentException("media_audio_channel_invalid");
            if (speakerCount != null && speakerCount <= 0) throw new IllegalArgumentException("media_speaker_count_invalid");
        }
        @Override public String toString() { return "TranscriptionRequest[model=" + model + "]"; }
    }

    /** Qwen 原生同步识别；由宿主精确服务选择，不根据 model 名称切换文件识别协议。 */
    record QwenTranscriptionRequest(String model, String audio, String language, Boolean enableItn) implements Request {
        /** 官方 10 MB 输入限制按编码后长度从严约束，包含 Data URI 前缀。 */
        public static final int MAXIMUM_ENCODED_AUDIO_BYTES = 10_000_000;
        public QwenTranscriptionRequest {
            requireText(model, "model"); requireText(audio, "audio");
            if (language != null) requireText(language, "language");
        }
        @Override public String toString() { return "QwenTranscriptionRequest[model=" + model + "]"; }
    }

    record SpeechRequest(String model, String text, String voice, String language) implements Request {
        public SpeechRequest { requireText(model, "model"); requireText(text, "text"); requireText(voice, "voice"); }
        @Override public String toString() { return "SpeechRequest[model=" + model + "]"; }
    }

    enum TaskKind { VIDEO, TRANSCRIPTION }
    record TaskQuery(TaskKind kind, String taskId) implements Request {
        public TaskQuery {
            Objects.requireNonNull(kind, "kind"); requireText(taskId, "task_id");
            if (!taskId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("media_task_id_invalid");
        }
    }

    enum Status { NOT_SUBMITTED, PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }
    enum AssetKind { IMAGE, VIDEO, AUDIO, TRANSCRIPT }
    sealed interface AssetSource permits UrlSource, BytesSource { }
    /** URL 可能带供应商签名，只能交给授权下载组件，不能直接写入普通日志。 */
    record UrlSource(URI url) implements AssetSource {
        public UrlSource { Objects.requireNonNull(url, "url"); }
        @Override public String toString() { return "MediaUrlSource[REDACTED]"; }
    }
    /** 原始媒体由宿主受控落盘；SDK 不创建临时文件，也不伪造资源 URL。 */
    record BytesSource(byte[] bytes) implements AssetSource {
        public BytesSource { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "MediaBytesSource[byteLength=" + bytes.length + "]"; }
    }
    record Asset(AssetKind kind, AssetSource source, String mediaType) {
        public Asset { Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(source, "source"); }
        public Asset(AssetKind kind, URI url, String mediaType) { this(kind, new UrlSource(url), mediaType); }
        @Override public String toString() { return "MediaAsset[kind=" + kind + "]"; }
    }
    /** 未报告的量保持 null；秒数保留供应商返回的十进制精度，不推测费用。 */
    record Usage(Long inputTokens, Long outputTokens, Long imageCount, Long videoCount,
            Long characters, BigDecimal durationSeconds) { }
    record Result(Status status, String requestId, String taskId, List<Asset> assets,
            Usage usage, String reasonCode, Instant observedAt) {
        public Result {
            Objects.requireNonNull(status, "status"); assets = List.copyOf(assets);
            Objects.requireNonNull(observedAt, "observedAt");
        }
        @Override public String toString() { return "MediaResult[status=" + status + ",assetCount=" + assets.size() + "]"; }
    }

    sealed interface SpeechEvent permits SpeechEvent.AudioChunk, SpeechEvent.Completed {
        /** 音频分片保留实际编码和原始顺序；wav 表示同一条 WAV 流的连续片段，单片不保证是独立文件。 */
        record AudioChunk(byte[] bytes, String encoding, int sampleRateHz, int channels) implements SpeechEvent {
            public AudioChunk {
                bytes = bytes.clone(); requireText(encoding, "audio_encoding");
                if (sampleRateHz <= 0 || channels <= 0) throw new IllegalArgumentException("media_audio_format_invalid");
            }
            @Override public byte[] bytes() { return bytes.clone(); }
            @Override public String toString() { return "AudioChunk[byteLength=" + bytes.length + "]"; }
        }
        record Completed(Result result) implements SpeechEvent {
            public Completed { Objects.requireNonNull(result, "result"); }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("media_" + field + "_required");
    }
}
