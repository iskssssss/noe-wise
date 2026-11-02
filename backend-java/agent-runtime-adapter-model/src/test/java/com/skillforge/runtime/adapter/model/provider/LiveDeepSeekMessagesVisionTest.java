package com.skillforge.runtime.adapter.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolCall;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolDefinition;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.Selection;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.SupportLevel;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelToolKind;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

/** 显式付费视觉验收：两个场景各一次请求；SENT 先落盘，重跑不重发原操作。 */
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_MESSAGES_VISION", matches = "true")
class LiveDeepSeekMessagesVisionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROVIDER = "deepseek";
    private static final String SERVICE = "deepseek-api";
    private static final String MODEL = "deepseek-flash";
    private static final String COLOR_INSTRUCTION =
            "Name the dominant color of the supplied image. Answer with exactly one lowercase English color word.";
    private static final String IMAGE_TOOL = "fixture_image";
    private static final String IMAGE_CALL = "live-vision-image-call";
    private static final int DIMENSION = 256;
    private static final int OUTPUT_TOKENS = 64;
    private static final long INPUT_TOKEN_UPPER_BOUND = 2048;
    private static final int MAXIMUM_RESPONSE_BYTES = 128 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ModelImagePolicy IMAGE_POLICY = new ModelImagePolicy(true, true, 1,
            1024 * 1024, 2 * 1024 * 1024, DIMENSION, 512, 1024, 0, "deepseek-vision-upper-bound.v1");
    private static final ToolDefinition TOOL = new ToolDefinition(IMAGE_TOOL, "Return the local fixture image.",
            new TypedPayload("json-schema", """
                    {"type":"object","properties":{},"additionalProperties":false}
                    """), false, ModelToolKind.TOOL_CALL, false);

    @RegisterExtension
    static final LiveProviderAttemptMeter.Outcome LIVE_OUTCOME = new LiveProviderAttemptMeter.Outcome();
    @AfterEach void collectMeterEvidence() { LiveProviderAttemptMeter.snapshot(); }

    @Test void readsPureUserImage() throws Exception { verify("pure-user-image", false); }

    @Test void readsImageInsideNativeToolResult() throws Exception { verify("native-tool-result-image", true); }

    private void verify(String scenario, boolean toolImage) throws Exception {
        Path reports = reportsDirectory();
        Path sent = reports.resolve(scenario + ".sent");
        Path evidence = reports.resolve(scenario + ".json");
        assumeTrue(!Files.exists(sent) && !Files.exists(evidence),
                "Prior SENT or receipt exists; do not replay this paid case, including UNKNOWN outcomes.");
        String credential = System.getenv("SKILLFORGE_DEEPSEEK_API_KEY");
        assertTrue(credential != null && !credential.isBlank(), "SKILLFORGE_DEEPSEEK_API_KEY is required");
        var catalog = ModelProviderCatalog.standard();
        var resolved = catalog.resolve(new Selection(PROVIDER, SERVICE, "2026-09-13", MODEL,
                ModelProtocol.ANTHROPIC_MESSAGES, AuthenticationScheme.ANTHROPIC_API_KEY, null));
        var support = catalog.imageSupport(PROVIDER, SERVICE, resolved.protocol(), MODEL);
        assertEquals(SupportLevel.SUPPORTED, support.userImages());
        if (toolImage) assertEquals(SupportLevel.SUPPORTED, support.toolImages());
        assertTrue(IMAGE_POLICY.enabled() && IMAGE_POLICY.toolImages());
        assertEquals(1024, IMAGE_POLICY.baseTokens());
        assertEquals(0, IMAGE_POLICY.tileTokens());
        byte[] png = redImage();
        assertTrue(png.length <= IMAGE_POLICY.maxImageBytes());
        var media = new MediaRef(new SessionRef(new TenantId("live-vision"), scenario), "red-image",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)), "image/png",
                png.length, DIMENSION, DIMENSION);
        var image = new ModelContent.Image(media, ImageDetail.HIGH);
        List<Message> messages = toolImage ? List.of(
                new Message(Role.USER, COLOR_INSTRUCTION + " Inspect the image returned by fixture_image.", null, List.of()),
                new Message(Role.ASSISTANT, (String) null, null, List.of(new ToolCall(IMAGE_CALL, IMAGE_TOOL, "{}"))),
                new Message(Role.TOOL, List.of(image), IMAGE_CALL, List.of(), null)) : List.of(
                new Message(Role.SYSTEM, COLOR_INSTRUCTION, null, List.of()),
                new Message(Role.USER, List.of(image), null, List.of(), null));
        var ticket = LiveProviderAttemptMeter.prepare(PROVIDER, MODEL, "messages-vision:" + scenario,
                INPUT_TOKEN_UPPER_BOUND, OUTPUT_TOKENS, IMAGE_POLICY.maxRequestBytes());
        markSent(sent);
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var runtime = ModelProviderRuntime.runtime(LiveProviderAttemptMeter.client(http, ticket),
                    Clock.systemUTC(), MAXIMUM_RESPONSE_BYTES);
            var request = new InvocationRequest(resolved.endpoint(), credential, MODEL,
                    new InvocationProfile(resolved.serviceId(), resolved.profileVersion(), resolved.protocol(),
                            resolved.authenticationScheme(), resolved.dialectVersion()), messages,
                    new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.BEST_EFFORT, "live-messages-vision"),
                    new GenerationOptions(OptionalInt.of(OUTPUT_TOKENS), OptionalDouble.empty()), ReasoningPolicy.DISABLED,
                    toolImage ? List.of(TOOL) : List.of(), ToolChoice.NONE, TIMEOUT, () -> false,
                    ref -> { assertEquals(media, ref); return png; }, IMAGE_POLICY.maxRequestBytes(), () -> { });
            InvocationResult result;
            try { result = runtime.generate(PROVIDER, request); }
            catch (RuntimeException failure) {
                writeEvidence(evidence, new Evidence(Status.UNKNOWN, null, null));
                throw new AssertionError("Live call has no classified receipt; retained SENT forbids automatic replay.");
            }
            Evidence receipt = evidence(result);
            writeEvidence(evidence, receipt);
            LiveProviderAttemptMeter.result(ticket, result);
            assertEquals(Status.PASSED, receipt.status(),
                    "Expected one complete red-color answer with measured usage; do not retry this paid case.");
        }
    }

    private static byte[] redImage() throws IOException {
        var image = new BufferedImage(DIMENSION, DIMENSION, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[DIMENSION * DIMENSION];
        Arrays.fill(pixels, 0xff0000);
        image.setRGB(0, 0, DIMENSION, DIMENSION, pixels, 0, DIMENSION);
        var bytes = new ByteArrayOutputStream();
        // 显式内存输出，避免 ImageIO 的默认磁盘缓存越过 E 盘写入约束。
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            assertTrue(ImageIO.write(image, "png", output), "PNG writer is required");
        }
        return bytes.toByteArray();
    }

    private static Path reportsDirectory() throws IOException {
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (System.getProperty("os.name").startsWith("Windows") && !workspace.toString().startsWith("E:"))
            throw new IllegalStateException("live_messages_vision_requires_e_drive_workspace");
        return Files.createDirectories(workspace.resolve("build/reports/live-deepseek-messages-vision"));
    }

    private static void markSent(Path sent) throws IOException {
        try (var channel = FileChannel.open(sent, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var content = ByteBuffer.wrap("SENT\n".getBytes(StandardCharsets.UTF_8));
            while (content.hasRemaining()) channel.write(content);
            channel.force(true);
        } catch (FileAlreadyExistsException existing) {
            assumeTrue(false, "SENT was already recorded; do not replay the original paid case.");
        }
    }

    private static void writeEvidence(Path path, Evidence evidence) throws IOException {
        Files.writeString(path, JSON.writeValueAsString(evidence) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static Evidence evidence(InvocationResult result) {
        if (result instanceof InvocationResult.Completed completed) {
            boolean passed = "stop".equals(completed.finishReasonCode()) && completed.toolCalls().isEmpty()
                    && completed.content() != null && "red".equalsIgnoreCase(completed.content().strip())
                    && completed.usage().inputTokens().orElse(0) > 0 && completed.usage().outputTokens().orElse(0) > 0;
            return new Evidence(passed ? Status.PASSED : Status.FAILED, completed.content(), Usage.from(completed.usage()));
        }
        if (result instanceof InvocationResult.Rejected rejected)
            return new Evidence(Status.FAILED, null, Usage.from(rejected.usage()));
        return new Evidence(Status.UNKNOWN, null, Usage.from(((InvocationResult.OutcomeUnknown) result).usage()));
    }

    private enum Status { PASSED, FAILED, UNKNOWN }
    private record Evidence(Status status, String reply, Usage usage) { }
    private record Usage(Long inputTokens, Long outputTokens, Long cachedInputTokens, Long cacheWriteInputTokens,
            Long reasoningOutputTokens) {
        static Usage from(ModelUsage usage) {
            return new Usage(value(usage.inputTokens()), value(usage.outputTokens()), value(usage.cachedInputTokens()),
                    value(usage.cacheWriteInputTokens()), value(usage.reasoningOutputTokens()));
        }
        private static Long value(OptionalLong value) { return value.isPresent() ? value.getAsLong() : null; }
    }
}
