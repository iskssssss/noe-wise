package com.skillforge.core.model.media;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import java.net.URI;
import java.util.List;

/** 平台与已授权工具共用的媒体输入；不接受宿主路径，模型身份来自冻结配置。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ModelMediaInput.Image.class, name = "image"),
        @JsonSubTypes.Type(value = ModelMediaInput.Video.class, name = "video"),
        @JsonSubTypes.Type(value = ModelMediaInput.Transcription.class, name = "transcription"),
        @JsonSubTypes.Type(value = ModelMediaInput.QwenTranscription.class, name = "qwen-transcription"),
        @JsonSubTypes.Type(value = ModelMediaInput.Speech.class, name = "speech")})
public sealed interface ModelMediaInput {
    String modelKind();
    DomesticMediaProvider.Request request(String model);

    record Image(String prompt, List<String> referenceImages, String size, Integer count,
            String negativePrompt, Long seed, Boolean promptExtend, Boolean watermark) implements ModelMediaInput {
        public Image { referenceImages = referenceImages == null ? List.of() : List.copyOf(referenceImages); }
        @Override public String modelKind() { return "IMAGE_GENERATION"; }
        @Override public DomesticMediaProvider.Request request(String model) {
            referenceImages.forEach(source -> requireSource(source, true));
            return new DomesticMediaProvider.ImageRequest(model, prompt, referenceImages, size, count,
                    negativePrompt, seed, promptExtend, watermark);
        }
        @Override public String toString() { return "MediaImageInput[REDACTED]"; }
    }

    record Video(String prompt, List<DomesticMediaProvider.VideoInput> media, String resolution,
            String ratio, Integer durationSeconds, Boolean audio, Long seed,
            Boolean promptExtend, Boolean watermark) implements ModelMediaInput {
        public Video { media = media == null ? List.of() : List.copyOf(media); }
        @Override public String modelKind() { return "VIDEO_GENERATION"; }
        @Override public DomesticMediaProvider.Request request(String model) {
            media.forEach(input -> requireSource(input.source(), false));
            return new DomesticMediaProvider.VideoRequest(model, prompt, media, resolution, ratio,
                    durationSeconds, audio, seed, promptExtend, watermark);
        }
        @Override public String toString() { return "MediaVideoInput[REDACTED]"; }
    }

    record Transcription(List<String> fileUrls, List<Integer> channels, List<String> languageHints,
            Boolean diarization, Integer speakerCount) implements ModelMediaInput {
        public Transcription {
            fileUrls = fileUrls == null ? List.of() : List.copyOf(fileUrls);
            channels = channels == null ? null : List.copyOf(channels);
            languageHints = languageHints == null ? null : List.copyOf(languageHints);
        }
        @Override public String modelKind() { return "SPEECH_RECOGNITION"; }
        @Override public DomesticMediaProvider.Request request(String model) {
            fileUrls.forEach(source -> requireSource(source, false));
            return new DomesticMediaProvider.TranscriptionRequest(model, fileUrls, channels, languageHints,
                    diarization, speakerCount);
        }
        @Override public String toString() { return "MediaTranscriptionInput[REDACTED]"; }
    }

    /** Qwen 原生同步识别输入；受控资产由宿主验证归属、限量读取后转换为 Data URI。 */
    record QwenTranscription(String audio, String language, Boolean enableItn) implements ModelMediaInput {
        @Override public String modelKind() { return "SPEECH_RECOGNITION"; }
        @Override public DomesticMediaProvider.Request request(String model) {
            if (audio == null || !audio.startsWith("data:")) requireSource(audio, false);
            return new DomesticMediaProvider.QwenTranscriptionRequest(model, audio, language, enableItn);
        }
        @Override public String toString() { return "MediaQwenTranscriptionInput[REDACTED]"; }
    }

    record Speech(String text, String voice, String language) implements ModelMediaInput {
        @Override public String modelKind() { return "SPEECH_SYNTHESIS"; }
        @Override public DomesticMediaProvider.Request request(String model) {
            return new DomesticMediaProvider.SpeechRequest(model, text, voice, language);
        }
        @Override public String toString() { return "MediaSpeechInput[REDACTED]"; }
    }

    private static void requireSource(String source, boolean allowImageData) {
        if (source == null) throw new IllegalArgumentException("media_source_required");
        if (allowImageData && source.startsWith("data:image/")) return;
        URI uri = URI.create(source);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("media_public_https_source_required");
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.matches("[0-9.]+") || host.indexOf(':') >= 0)
            throw new IllegalArgumentException("media_public_https_source_required");
    }
}
