package com.skillforge.core.model.media;

import static com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.media.BailianMediaProvider;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 默认关闭的真实供应商和生产FileStore验收；不证明任务数据库、权限入口或UI已验收。
 * 每次付费请求先在E盘登记，再单次派发；已有登记及UNKNOWN均不自动重提。
 * 不读取.env；执行者仅将已有北京密钥注入SKILLFORGE_BAILIAN_API_KEY。
 */
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_DOMESTIC_MEDIA", matches = "true")
class LiveDomesticMediaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PHRASE = "你好，这是语音测试。";

    @Test
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_MEDIA_CASE", matches = "tts-asr")
    void bailianTtsCollectedByFileStoreAndReopenedForQwenAsr() throws Exception {
        Path directory = ePath(required("SKILLFORGE_LIVE_MEDIA_EVIDENCE_DIR"));
        Files.createDirectories(directory);
        var endpoint = URI.create(System.getenv().getOrDefault("SKILLFORGE_BAILIAN_ENDPOINT", "https://dashscope.aliyuncs.com/api/v1"));
        BailianMediaProvider.nativeEndpoint(endpoint, "/services/aigc/multimodal-generation/generation");
        var connection = new Connection(endpoint, required("SKILLFORGE_BAILIAN_API_KEY"), Duration.ofSeconds(60), () -> false);
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var provider = new DomesticMediaProviders(http, Clock.systemUTC(), 1_048_576).require("aliyun-bailian");
            var store = new FileModelMediaAssetStore(http, directory.resolve("assets"), 1_048_576);
            byte[] phrase = PHRASE.getBytes(StandardCharsets.UTF_8);
            var speech = once(directory, "tts", "qwen3-tts-flash", provider, connection,
                    new SpeechRequest("qwen3-tts-flash", PHRASE, "Cherry", "Chinese"), hash(phrase), phrase.length,
                    new BigDecimal("0.002"), "https://help.aliyun.com/zh/model-studio/qwen3-tts-flash");
            assertEquals(Status.SUCCEEDED, speech.status(), speech.reasonCode());
            assertNotNull(speech.requestId(), "live_supplier_request_id_missing");
            assertEquals(1, speech.assets().size());
            var audio = speech.assets().getFirst(); assertEquals(AssetKind.AUDIO, audio.kind());
            var source = assertInstanceOf(UrlSource.class, audio.source());
            var stored = store.importAsset("91001", audio.kind(), source.url(), audio.mediaType(), Duration.ofSeconds(30), () -> false);
            var reopened = new FileModelMediaAssetStore(http, directory.resolve("assets"), 1_048_576);
            byte[] bytes;
            try (var input = reopened.open("91001")) { bytes = input.readNBytes(1_048_577); }
            assertEquals(stored.sizeBytes(), bytes.length); assertEquals(stored.sha256(), hash(bytes));
            verifyShortWav(bytes);
            append(directory.resolve("bailian-tts.jsonl"), new Collected("BODY_COLLECTED", stored.mediaType(),
                    bytes.length, stored.sha256(), Instant.now().toString()), false);

            recognizeCollected(directory, provider, connection, store, reopened, bytes);
        }
    }

    /** 只读取已成功收集的原音频；缺失或核对失败时不会创建新的TTS请求。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_MEDIA_CASE", matches = "asr-from-collected")
    void bailianAsrUsesPreviouslyCollectedTtsWithoutResynthesis() throws Exception {
        Path directory = ePath(required("SKILLFORGE_LIVE_MEDIA_EVIDENCE_DIR"));
        Path original = directory.resolve("assets/91001.body");
        assertTrue(Files.isRegularFile(original), "live_original_tts_body_required");
        assertTrue(Files.size(original) <= 1_048_576, "live_audio_fixture_exceeds_one_mib");
        String expected = required("SKILLFORGE_LIVE_ASR_EXPECTED_SHA256");
        assertTrue(expected.matches("[0-9a-f]{64}"), "live_original_tts_sha256_required");
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var store = new FileModelMediaAssetStore(http, directory.resolve("assets"), 1_048_576);
            var reopened = new FileModelMediaAssetStore(http, directory.resolve("assets"), 1_048_576);
            byte[] bytes;
            try (var input = reopened.open("91001")) { bytes = input.readNBytes(1_048_577); }
            assertEquals(expected, hash(bytes), "live_original_tts_body_changed");
            verifyShortWav(bytes);
            var endpoint = URI.create(System.getenv().getOrDefault("SKILLFORGE_BAILIAN_ENDPOINT", "https://dashscope.aliyuncs.com/api/v1"));
            BailianMediaProvider.nativeEndpoint(endpoint, "/services/aigc/multimodal-generation/generation");
            var connection = new Connection(endpoint, required("SKILLFORGE_BAILIAN_API_KEY"), Duration.ofSeconds(60), () -> false);
            var provider = new DomesticMediaProviders(http, Clock.systemUTC(), 1_048_576).require("aliyun-bailian");
            recognizeCollected(directory, provider, connection, store, reopened, bytes);
        }
    }

    private static void recognizeCollected(Path directory, DomesticMediaProvider provider, Connection connection,
            FileModelMediaAssetStore store, FileModelMediaAssetStore reopened, byte[] bytes) throws Exception {
        var recognized = once(directory, "asr", "qwen3-asr-flash", provider, connection,
                new QwenTranscriptionRequest("qwen3-asr-flash", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(bytes), "zh", false),
                hash(bytes), bytes.length, new BigDecimal("0.003"), "https://help.aliyun.com/zh/model-studio/qwen3-asr-flash");
        assertEquals(Status.SUCCEEDED, recognized.status(), recognized.reasonCode());
        assertNotNull(recognized.requestId(), "live_supplier_request_id_missing"); assertEquals(1, recognized.assets().size());
        var transcript = recognized.assets().getFirst(); assertEquals(AssetKind.TRANSCRIPT, transcript.kind());
        assertEquals("application/json", transcript.mediaType());
        byte[] transcriptBytes = assertInstanceOf(BytesSource.class, transcript.source()).bytes();
        var saved = store.storeBytes("91002", transcript.kind(), transcriptBytes, transcript.mediaType(), Duration.ofSeconds(10), () -> false);
        byte[] readBack;
        try (var input = reopened.open("91002")) { readBack = input.readNBytes(1_048_577); }
        assertArrayEquals(transcriptBytes, readBack); assertEquals(saved.sha256(), hash(readBack));
        append(directory.resolve("bailian-asr.jsonl"), new Collected("BODY_COLLECTED", saved.mediaType(),
                readBack.length, saved.sha256(), Instant.now().toString()), false);
        var tracks = JSON.readTree(readBack).path("transcripts"); assertEquals(1, tracks.size());
        String text = tracks.get(0).path("text").asText();
        assertTrue(text.contains("语音") && text.contains("测试"), "live_fixed_fixture_words_missing");
    }

    private static void verifyShortWav(byte[] bytes) throws Exception {
        assertTrue(bytes.length >= 44 && bytes.length <= 1_048_576
                && "RIFF".equals(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                && "WAVE".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII)), "live_audio_fixture_must_be_wav");
        try (var audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
            var format = audio.getFormat(); assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
            assertEquals(1, format.getChannels()); assertEquals(16, format.getSampleSizeInBits());
            assertEquals(2, format.getFrameSize());
            assertTrue(Float.isFinite(format.getFrameRate()) && format.getFrameRate() > 0, "live_audio_frame_rate_invalid");
            // 流式WAV可使用未定长度头；预算按真正读到的PCM帧数核对，不能按占位frameLength。
            long decodedBytes = 0;
            byte[] buffer = new byte[8192]; int read;
            while ((read = audio.read(buffer)) != -1) decodedBytes = Math.addExact(decodedBytes, read);
            assertTrue(decodedBytes > 0 && decodedBytes % format.getFrameSize() == 0
                    && decodedBytes / format.getFrameSize() <= format.getFrameRate() * 10,
                    "live_audio_must_be_at_most_ten_seconds");
        }
    }

    private static Result once(Path directory, String scenario, String model, DomesticMediaProvider provider,
            Connection connection, Request request, String inputSha256, long inputBytes,
            BigDecimal estimatedMaximumYuan, String priceSource) throws Exception {
        Path ledger = directory.resolve("bailian-" + scenario + ".jsonl");
        String operation = UUID.randomUUID().toString();
        append(ledger, new Attempt(operation, "DISPATCH_RECORDED", "UNKNOWN", model, inputSha256, inputBytes,
                estimatedMaximumYuan, priceSource, Instant.now().toString()), true);
        var result = provider.execute(connection, request);
        var facts = new ArrayList<AssetFact>();
        for (var asset : result.assets()) facts.add(switch (asset.source()) {
            case UrlSource source -> new AssetFact(asset.kind().name(), asset.mediaType(), "URL", null,
                    hash(source.url().toString().getBytes(StandardCharsets.UTF_8)), source.url().getHost());
            case BytesSource source -> new AssetFact(asset.kind().name(), asset.mediaType(), "BYTES", (long) source.bytes().length,
                    hash(source.bytes()), null);
        });
        append(ledger, new Receipt(operation, "RETURNED", result.status().name(), result.requestId(), result.taskId(),
                result.reasonCode(), result.usage(), facts, result.observedAt().toString()), false);
        return result;
    }
    private static void append(Path ledger, Object value, boolean first) throws Exception {
        byte[] bytes = (JSON.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
        try (var channel = FileChannel.open(ledger, StandardOpenOption.WRITE,
                first ? StandardOpenOption.CREATE_NEW : StandardOpenOption.APPEND)) {
            var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static Path ePath(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertTrue(path.getRoot() != null && "E:\\".equalsIgnoreCase(path.getRoot().toString()), "live_evidence_must_be_on_e_drive");
        return path;
    }
    private static String required(String name) {
        String value = System.getenv(name); assertTrue(value != null && !value.isBlank(), "missing_environment_" + name); return value;
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private record Attempt(String operationId, String phase, String status, String model, String inputSha256,
            long inputBytes, BigDecimal estimatedMaximumYuan, String priceSource, String observedAt) { }
    private record Receipt(String operationId, String phase, String status, String requestId, String taskId,
            String reasonCode, Usage usage, List<AssetFact> assets, String observedAt) { }
    private record Collected(String phase, String mediaType, long sizeBytes, String sha256, String observedAt) { }
    private record AssetFact(String kind, String mediaType, String sourceKind, Long sizeBytes, String sourceSha256, String supplierHost) { }
}
