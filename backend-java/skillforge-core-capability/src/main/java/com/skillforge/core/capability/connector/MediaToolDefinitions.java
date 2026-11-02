package com.skillforge.core.capability.connector;

import static com.skillforge.core.capability.connector.ToolSchemas.objectSchema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 国内媒体工具的固定参数与发布契约；模型和凭据只从宿主绑定读取。 */
public final class MediaToolDefinitions {
    private MediaToolDefinitions() { }
    public static final String GENERATE_IMAGE = "generate_image";
    public static final String GENERATE_VIDEO = "generate_video";
    public static final String TRANSCRIBE_AUDIO = "transcribe_audio";
    public static final String SYNTHESIZE_SPEECH = "synthesize_speech";
    public static final String QUERY_JOB = "query_model_media_job";
    private static final String PROMPT = "prompt", REFERENCES = "reference_images", SIZE = "size", COUNT = "count";
    private static final String NEGATIVE = "negative_prompt", SEED = "seed", EXTEND = "prompt_extend", WATERMARK = "watermark";
    private static final String URL = "url", JOB = "job_id", ASSET = "asset_id", MEDIA = "media", KIND = "kind", SOURCE = "source";
    private static final String RESOLUTION = "resolution", RATIO = "ratio", DURATION = "duration_seconds", AUDIO = "audio";
    private static final String FILE = "audio_source", CHANNELS = "channels", LANGUAGES = "language_hints";
    private static final String DIARIZATION = "diarization", SPEAKERS = "speaker_count", TEXT = "text", VOICE = "voice", LANGUAGE = "language";
    private static final String ENABLE_ITN = "enable_itn";

    static PlatformToolCatalog.Entry generateImage(ToolSchemaContributor schemas) {
        return entry(GENERATE_IMAGE, "生成或编辑图片",
                "使用宿主已绑定的国内图像模型生成或编辑图片。参考图可给公开HTTPS地址，或当前会话先前结果的job_id和asset_id。"
                + "本调用可能计费；PENDING/RUNNING时使用query_model_media_job核对原任务；UNKNOWN时不得重新创建。成功文件是候选，需检查后再确认交付。",
                objectSchema(Map.ofEntries(Map.entry(PROMPT, text()), Map.entry(REFERENCES, array(sourceSchema())),
                        Map.entry(SIZE, text()), Map.entry(COUNT, positiveInteger()), Map.entry(NEGATIVE, text()),
                        Map.entry(SEED, Map.of("type", "integer")), Map.entry(EXTEND, bool()), Map.entry(WATERMARK, bool())), List.of(PROMPT)), schemas, true);
    }

    static PlatformToolCatalog.Entry generateVideo(ToolSchemaContributor schemas) {
        var media = objectSchema(Map.of(KIND, Map.of("type", "string", "enum", List.of(
                "FIRST_FRAME", "LAST_FRAME", "REFERENCE_IMAGE", "REFERENCE_VIDEO", "REFERENCE_AUDIO", "FILE", "LINK")),
                SOURCE, sourceSchema()), List.of(KIND, SOURCE));
        return entry(GENERATE_VIDEO, "生成视频",
                "使用宿主已绑定的国内视频模型创建一次视频任务。媒体素材的kind必须匹配真实用途；prompt和media至少提供一项。"
                + "任务未完成或UNKNOWN时保留原job_id并用query_model_media_job查询原任务；UNKNOWN不得再次创建，停止本地等待不代表供应商已取消。",
                objectSchema(Map.ofEntries(Map.entry(PROMPT, text()), Map.entry(MEDIA, array(media)),
                        Map.entry(RESOLUTION, text()), Map.entry(RATIO, text()), Map.entry(DURATION, Map.of("type", "integer")),
                        Map.entry(AUDIO, bool()), Map.entry(SEED, Map.of("type", "integer")),
                        Map.entry(EXTEND, bool()), Map.entry(WATERMARK, bool())), List.of()), schemas, true);
    }

    static PlatformToolCatalog.Entry transcribeAudio(ToolSchemaContributor schemas) {
        return entry(TRANSCRIBE_AUDIO, "识别音频文字",
                "使用宿主已绑定的国内语音识别服务转写音频。Qwen支持公开HTTPS音频或本会话已完成的MP3/WAV资产，使用language与enable_itn；"
                + "Paraformer使用公开HTTPS或仍具有远程来源的音频资产，使用channels、language_hints、diarization与speaker_count，不能读取仅有本地正文的资产。"
                + "异步任务用query_model_media_job核对原job_id；UNKNOWN时保留原job_id并查询原任务，不得重新提交。转写文件作为受管候选提供。",
                objectSchema(Map.of(FILE, sourceSchema(), CHANNELS, array(Map.of("type", "integer", "minimum", 0)),
                        LANGUAGES, array(text()), DIARIZATION, bool(), SPEAKERS, positiveInteger(),
                        LANGUAGE, text(), ENABLE_ITN, bool()), List.of(FILE)), schemas, true);
    }

    static PlatformToolCatalog.Entry synthesizeSpeech(ToolSchemaContributor schemas) {
        return entry(SYNTHESIZE_SPEECH, "合成语音",
                "使用宿主已绑定的国内语音模型与指定音色合成文字。voice使用该服务的真实音色标识，不根据名称猜测。"
                + "本调用可能计费；UNKNOWN时保留原job_id并用query_model_media_job核对原任务，不得重新创建。生成音频须检查后再确认交付。",
                objectSchema(Map.of(TEXT, text(), VOICE, text(), LANGUAGE, text()), List.of(TEXT, VOICE)), schemas, true);
    }

    static PlatformToolCatalog.Entry queryJob(ToolSchemaContributor schemas) {
        return entry(QUERY_JOB, "核对媒体任务",
                "查询当前会话已创建媒体任务的原job_id，并收集已经生成的结果文件。只查询原任务，不重新生成；"
                + "UNKNOWN且缺少供应商任务身份时保持未知。成功收集的文件仍是候选，不能直接当作已确认交付。",
                objectSchema(Map.of(JOB, text()), List.of(JOB)), schemas, false);
    }

    private static PlatformToolCatalog.Entry entry(String name, String title, String description,
            Map<String, Object> input, ToolSchemaContributor schemas, boolean creates) {
        var output = objectSchema(Map.ofEntries(Map.entry("success", bool()), Map.entry("complete", bool()),
                Map.entry("status", text()), Map.entry(JOB, Map.of("type", List.of("string", "null"))),
                Map.entry("reason_code", Map.of("type", List.of("string", "null"))),
                Map.entry("assets", array(objectSchema(Map.of(ASSET, text(), KIND, text(), "available", bool()), List.of(ASSET, KIND, "available")))),
                Map.entry("artifacts", array(objectSchema(Map.of("id", text(), "paths", array(text())), List.of("id", "paths")))),
                Map.entry("public_presentation", schemas.codePublicPresentationSchema())), List.of("success", "complete", "status", "assets", "artifacts"));
        return PlatformToolCatalog.Entry.builder().name(name).title(title).publishedVersionKey("builtin:1")
                .description(description).parametersSchema(input).outputSchema(output)
                .effect(creates ? ToolEffect.NON_IDEMPOTENT_WRITE : ToolEffect.IDEMPOTENT_WRITE)
                .idempotent(!creates).confirmationPolicy(ToolConfirmationPolicy.NEVER).timeoutMs(600_000)
                .modelInvocable(true).userInvocable(true).parallelSafe(false).sensitiveFields(Set.of())
                .callIdentityPolicy(creates ? ToolCallIdentityPolicy.HANDLE : ToolCallIdentityPolicy.ARGUMENTS).build();
    }

    private static Map<String, Object> sourceSchema() {
        return Map.of("oneOf", List.of(objectSchema(Map.of(URL, text()), List.of(URL)),
                objectSchema(Map.of(JOB, text(), ASSET, text()), List.of(JOB, ASSET))));
    }
    private static Map<String, Object> text() { return Map.of("type", "string", "minLength", 1); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> positiveInteger() { return Map.of("type", "integer", "minimum", 1); }
    private static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }

    /** URL和受管资产引用二选一；资产必须由当前可信会话拥有。 */
    public record Source(@JsonProperty(URL) String url, @JsonProperty(JOB) String jobId, @JsonProperty(ASSET) String assetId) {
        public Source {
            boolean direct = url != null && !url.isBlank();
            boolean reference = jobId != null && !jobId.isBlank() && assetId != null && !assetId.isBlank();
            if (direct == reference || direct && (jobId != null || assetId != null) || !direct && (url != null || !reference))
                throw new IllegalArgumentException("media_source_invalid");
        }
    }
    public record ImageArguments(@JsonProperty(PROMPT) String prompt, @JsonProperty(REFERENCES) List<Source> referenceImages,
            @JsonProperty(SIZE) String size, @JsonProperty(COUNT) Integer count, @JsonProperty(NEGATIVE) String negativePrompt,
            @JsonProperty(SEED) Long seed, @JsonProperty(EXTEND) Boolean promptExtend, @JsonProperty(WATERMARK) Boolean watermark) { }
    public record VideoSource(@JsonProperty(KIND) String kind, @JsonProperty(SOURCE) Source source) { }
    public record VideoArguments(@JsonProperty(PROMPT) String prompt, @JsonProperty(MEDIA) List<VideoSource> media,
            @JsonProperty(RESOLUTION) String resolution, @JsonProperty(RATIO) String ratio, @JsonProperty(DURATION) Integer durationSeconds,
            @JsonProperty(AUDIO) Boolean audio, @JsonProperty(SEED) Long seed, @JsonProperty(EXTEND) Boolean promptExtend,
            @JsonProperty(WATERMARK) Boolean watermark) { }
    public record TranscriptionArguments(@JsonProperty(FILE) Source audioSource, @JsonProperty(CHANNELS) List<Integer> channels,
            @JsonProperty(LANGUAGES) List<String> languageHints, @JsonProperty(DIARIZATION) Boolean diarization,
            @JsonProperty(SPEAKERS) Integer speakerCount, @JsonProperty(LANGUAGE) String language,
            @JsonProperty(ENABLE_ITN) Boolean enableItn) { }
    public record SpeechArguments(@JsonProperty(TEXT) String text, @JsonProperty(VOICE) String voice, @JsonProperty(LANGUAGE) String language) { }
    public record QueryArguments(@JsonProperty(JOB) String jobId) { }
}
