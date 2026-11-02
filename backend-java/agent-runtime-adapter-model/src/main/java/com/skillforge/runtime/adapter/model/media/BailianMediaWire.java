package com.skillforge.runtime.adapter.model.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** 百炼公开 HTTP 协议的固定 wire 结构，字段映射只在本边界维护。 */
final class BailianMediaWire {
    private BailianMediaWire() { }
    record ImageBody(String model, ImageInput input, ImageParameters parameters) { }
    record ImageInput(List<Message> messages) { }
    record ImageParameters(String size, Integer n, String negativePrompt, Long seed,
            Boolean promptExtend, Boolean watermark) { }
    record Message(String role, List<Part> content) { }
    record Part(String text, String image) { }
    record VideoBody(String model, VideoInput input, VideoParameters parameters) { }
    record VideoInput(String prompt, List<VideoMedia> media) { }
    record VideoMedia(String type, String url) { }
    record VideoParameters(String resolution, String ratio, Integer duration, Boolean audio,
            Long seed, Boolean promptExtend, Boolean watermark) { }
    record TranscriptionBody(String model, TranscriptionInput input, TranscriptionParameters parameters) { }
    record TranscriptionInput(List<String> fileUrls) { }
    record TranscriptionParameters(List<Integer> channelId, List<String> languageHints,
            Boolean diarizationEnabled, Integer speakerCount) { }
    record QwenTranscriptionBody(String model, QwenTranscriptionInput input, QwenTranscriptionParameters parameters) { }
    record QwenTranscriptionInput(List<QwenAudioMessage> messages) { }
    record QwenAudioMessage(String role, List<QwenAudioPart> content) { }
    record QwenAudioPart(String audio) { }
    record QwenTranscriptionParameters(AsrOptions asrOptions) { }
    record AsrOptions(String language, Boolean enableItn) { }
    record QwenTranscript(List<QwenTranscriptTrack> transcripts) { }
    record QwenTranscriptTrack(String text, List<AudioAnnotation> annotations) { }
    record SpeechBody(String model, SpeechInput input) { }
    record SpeechInput(String text, String voice, String languageType) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(String requestId, String code, String message, Integer statusCode,
            Output output, Usage usage) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Output(String taskId, String taskStatus, String code, String message,
            String finishReason, List<Choice> choices, Audio audio, String videoUrl,
            List<TranscriptResult> results, TaskMetrics taskMetrics) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(String finishReason, OutputMessage message) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OutputMessage(String role, List<OutputPart> content, List<AudioAnnotation> annotations) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AudioAnnotation(String type, String language, String emotion) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OutputPart(String image, String text) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Audio(String data, String url, String id, Long expiresAt) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranscriptResult(String fileUrl, String transcriptionUrl, String subtaskStatus,
            String code, String message) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TaskMetrics(@JsonProperty("TOTAL") Integer total,
            @JsonProperty("SUCCEEDED") Integer succeeded, @JsonProperty("FAILED") Integer failed) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(Long inputTokens, Long outputTokens, Long imageCount, Long videoCount,
            Long characters, BigDecimal duration, BigDecimal seconds,
            TokenDetails inputTokensDetails, TokenDetails outputTokensDetails) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenDetails(Long textTokens) { }
}
