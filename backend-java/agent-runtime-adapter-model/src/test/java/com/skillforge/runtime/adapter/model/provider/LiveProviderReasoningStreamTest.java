package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.openai.CustomOpenAiCompatibleModelProvider;
import com.skillforge.runtime.adapter.model.aliyun_bailian.AliyunBailianModelProvider;
import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationProfile;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Message;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.Role;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolChoice;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.ToolDefinition;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort.EnforcementLevel;
import com.skillforge.runtime.api.port.ModelPort.GenerationOptions;
import com.skillforge.runtime.api.port.ModelPort.ModelToolKind;
import com.skillforge.runtime.api.port.ModelPort.ReasoningPolicy;
import com.skillforge.runtime.api.port.ModelPort.ResponseContract;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormat;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式付费验收：每个显式选择的case两次流式请求，不重试；只执行无副作用的本地求和。 */
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_REASONING_STREAM", matches = "true")
class LiveProviderReasoningStreamTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int OUTPUT_TOKENS = 4096;
    private static final String SUM = "test_sum";
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveProviderAttemptMeter.Outcome liveMeterOutcome = new LiveProviderAttemptMeter.Outcome();
    @org.junit.jupiter.api.AfterEach void collectEvidence() { LiveProviderAttemptMeter.snapshot(); }

    @Test void deepSeekChatReasoningToolRoundTrip() throws Exception {
        verify(new DeepSeekModelProvider(), "deepseek-api", ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                "SKILLFORGE_DEEPSEEK", "deepseek-flash", ReasoningPolicy.LOW);
    }
    @Test void deepSeekMessagesReasoningToolRoundTrip() throws Exception {
        verify(new DeepSeekModelProvider(), "deepseek-api", ModelProtocol.ANTHROPIC_MESSAGES,
                "SKILLFORGE_DEEPSEEK", "deepseek-flash", ReasoningPolicy.LOW);
    }
    /** 独立HIGH诊断，不替代LOW失败样本，也不改变其断言。 */
    @Test void deepSeekMessagesHighReasoningToolRoundTrip() throws Exception {
        verify(new DeepSeekModelProvider(), "deepseek-api", ModelProtocol.ANTHROPIC_MESSAGES,
                "SKILLFORGE_DEEPSEEK", "deepseek-flash", ReasoningPolicy.HIGH);
    }    @Test void bailianBeijingChatReasoningToolRoundTrip() throws Exception {
        verify(new AliyunBailianModelProvider(), "bailian-cn-beijing", ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                "SKILLFORGE_BAILIAN", "qwen-plus", ReasoningPolicy.ENABLED);
    }

    /** 通用Responses协议的显式端点验收，不向DeepSeek品牌目录添加能力声明。 */
    @Test void customResponsesAtDeepSeekReasoningToolRoundTrip() throws Exception {
        verify(new CustomOpenAiCompatibleModelProvider(), "custom-openai-compatible", ModelProtocol.OPENAI_RESPONSES,
                "SKILLFORGE_DEEPSEEK", "deepseek-flash", ReasoningPolicy.LOW, "https://api.deepseek.com");
    }
    private void verify(ModelProvider catalog, String serviceId, ModelProtocol protocolId,
            String envPrefix, String defaultModel, ReasoningPolicy reasoning) throws Exception {
        verify(catalog, serviceId, protocolId, envPrefix, defaultModel, reasoning, null);
    }

    private void verify(ModelProvider catalog, String serviceId, ModelProtocol protocolId,
            String envPrefix, String defaultModel, ReasoningPolicy reasoning, String requestedEndpoint) throws Exception {
        String credential = required(envPrefix + "_API_KEY");
        String override = System.getenv(envPrefix + "_MODEL");
        String model = override == null || override.isBlank() ? defaultModel : override;
        var service = catalog.preset().services().stream().filter(s -> s.serviceId().equals(serviceId))
                .findFirst().orElseThrow();
        var protocol = service.protocols().stream().filter(p -> p.protocol() == protocolId).findFirst().orElseThrow();
        var authentication = protocol.authenticationSchemes().stream().findFirst().orElseThrow();
        var profile = new InvocationProfile(serviceId, service.profileVersion(), protocolId,
                authentication, protocol.dialectVersion());
        var tool = new ToolDefinition(SUM, "Return the exact sum of integers a and b; no external effects.",
                new TypedPayload("json-schema", """
                        {"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},
                         "required":["a","b"],"additionalProperties":false}
                        """), false, ModelToolKind.TOOL_CALL, false);
        var messages = new ArrayList<Message>();
        messages.add(new Message(Role.USER,
                "Use the test_sum tool exactly once with a=17 and b=25. Do not calculate instead of calling it. "
                        + "After its result arrives, answer with only the resulting integer and call no further tools.", null, List.of()));
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var first = stream(http, catalog, profile, credential, model, reasoning, messages, List.of(tool), 1, requestedEndpoint);
            assertEquals("tool_calls", first.finishReasonCode(), "first stream must finish as a tool turn");
            assertEquals(1, first.toolCalls().size(), "expected exactly one local sum call");
            var call = first.toolCalls().getFirst();
            assertTrue(SUM.equals(call.name()), "unexpected tool name");
            var args = JSON.readTree(call.argumentsJson());
            assertTrue(args != null && args.isObject() && args.size() == 2
                    && args.path("a").isIntegralNumber() && args.path("b").isIntegralNumber()
                    && args.path("a").intValue() == 17 && args.path("b").intValue() == 25,
                    "sum arguments must match the controlled task");
            if (protocolId == ModelProtocol.ANTHROPIC_MESSAGES) safeMessagesMetadata(first, reasoning);
            // Messages 真实响应可返回带签名的空 thinking 块；验证原生块与回放，不要求模型每轮生成推理文字。
            if (protocolId == ModelProtocol.ANTHROPIC_MESSAGES) {
                assertTrue(first.protocolState().isPresent(), "Messages must retain native output blocks");
                boolean signedThinking = false;
                for (var item : first.protocolState().orElseThrow().items()) {
                    var block = JSON.readTree(item.canonicalJson());
                    if ("thinking".equals(block.path("type").asText())) {
                        assertTrue(block.path("thinking").isTextual() && block.path("signature").isTextual(),
                                "Messages thinking block must preserve text and signature");
                        signedThinking = true;
                    }
                }
                assertTrue(signedThinking, "reasoning-enabled Messages turn must retain its thinking block");
            } else {
                assertTrue(first.reasoningContent() != null && !first.reasoningContent().isBlank(),
                        "reasoning-enabled tool turn did not expose reasoning");
            }
            if (protocolId == ModelProtocol.ANTHROPIC_MESSAGES || protocolId == ModelProtocol.OPENAI_RESPONSES)
                assertTrue(first.protocolState().isPresent(), "native reasoning output must retain protocol state");
            // 原样回传供应商推理与原生状态；不得由测试重新构造thinking签名。
            List<ModelContent> contents = first.content() == null || first.content().isEmpty()
                    ? List.of() : List.of(new ModelContent.Text(first.content()));
            messages.add(new Message(Role.ASSISTANT, contents, null, first.toolCalls(),
                    first.reasoningContent(), first.protocolState()));
            int result = Math.addExact(args.path("a").intValue(), args.path("b").intValue());
            messages.add(new Message(Role.TOOL, Integer.toString(result), call.callId(), List.of()));
            var second = stream(http, catalog, profile, credential, model, reasoning, messages, List.of(tool), 2, requestedEndpoint);
            assertEquals("stop", second.finishReasonCode(), "second stream must finish the answer");
            assertTrue(second.toolCalls().isEmpty(), "tool chain exceeds the two-request bound");
            assertTrue("42".equals(second.content().strip()), "final answer must be the observed local sum");
            System.out.printf("LIVE_REASONING_STREAM_PASS provider=%s protocol=%s requests=2 inputTokens=%d outputTokens=%d%n",
                    catalog.preset().providerId(), protocolId,
                    Math.addExact(first.usage().inputTokens().orElseThrow(), second.usage().inputTokens().orElseThrow()),
                    Math.addExact(first.usage().outputTokens().orElseThrow(), second.usage().outputTokens().orElseThrow()));
        }
    }

    private InvocationResult.Completed stream(HttpClient http, ModelProvider catalog, InvocationProfile profile,
            String credential, String model, ReasoningPolicy reasoning, List<Message> messages,
            List<ToolDefinition> tools, int round, String requestedEndpoint) throws Exception {
        String providerId = catalog.preset().providerId();
        var ticket = LiveProviderAttemptMeter.prepare(providerId, model,
                "reasoning-stream:" + profile.protocol() + ":" + round, 16384, OUTPUT_TOKENS, 65536);
        var runtime = ModelProviderRuntime.runtime(LiveProviderAttemptMeter.client(http, ticket), Clock.systemUTC(), 2 * 1024 * 1024);
        var request = new InvocationRequest(catalog.resolveEndpoint(catalog.preset().services().stream()
                .filter(s -> s.serviceId().equals(profile.serviceId())).findFirst().orElseThrow(), requestedEndpoint),
                credential, model, profile, List.copyOf(messages),
                new ResponseContract(new ResponseFormat.Text(), EnforcementLevel.STRICT, "live-reasoning-stream"),
                new GenerationOptions(OptionalInt.of(OUTPUT_TOKENS), OptionalDouble.empty()),
                reasoning, tools, ToolChoice.AUTO, TIMEOUT, () -> false, () -> { });
        var probe = new Probe();
        try {
            runtime.stream(providerId, request).subscribe(LiveProviderAttemptMeter.observe(ticket, probe));
            assertTrue(probe.done.await(120, TimeUnit.SECONDS), "stream exceeded the 120s bound");
            assertTrue(probe.failureType == null, "stream failed without a classified terminal");
            var terminal = probe.events.stream().filter(e -> e instanceof StreamEvent.Completed
                    || e instanceof StreamEvent.Rejected || e instanceof StreamEvent.OutcomeUnknown).toList();
            assertEquals(1, terminal.size(), "exactly one classified terminal required");
            String terminalCode = switch (terminal.getFirst()) {
                case StreamEvent.Rejected rejected -> rejected.reasonCode();
                case StreamEvent.OutcomeUnknown unknown -> unknown.reasonCode();
                default -> "completed";
            };
            System.out.printf("LIVE_REASONING_STREAM_TERMINAL provider=%s protocol=%s round=%d code=%s%n",
                    providerId, profile.protocol(), round, terminalCode);
            assertTrue(terminal.getFirst() instanceof StreamEvent.Completed,
                    "provider did not complete; no retry permitted");
            var complete = ((StreamEvent.Completed) terminal.getFirst()).result();
            String text = probe.events.stream().filter(StreamEvent.ContentDelta.class::isInstance)
                    .map(StreamEvent.ContentDelta.class::cast).map(StreamEvent.ContentDelta::content)
                    .collect(java.util.stream.Collectors.joining());
            String thoughts = probe.events.stream().filter(StreamEvent.ReasoningDelta.class::isInstance)
                    .map(StreamEvent.ReasoningDelta.class::cast).map(StreamEvent.ReasoningDelta::content)
                    .collect(java.util.stream.Collectors.joining());
            // boolean断言避免JUnit失败报告泄露原始正文/推理/工具参数。
            assertTrue(text.equals(complete.content() == null ? "" : complete.content()), "content deltas differ from Completed");
            assertTrue(thoughts.equals(complete.reasoningContent() == null ? "" : complete.reasoningContent()),
                    "reasoning deltas differ from Completed");
            var usage = probe.events.stream().filter(StreamEvent.UsageDelta.class::isInstance)
                    .map(StreamEvent.UsageDelta.class::cast).toList();
            assertFalse(usage.isEmpty(), "usage events missing");
            assertEquals(complete.usage(), usage.getLast().usage(), "last cumulative usage differs from Completed");
            assertTrue(complete.usage().inputTokens().isPresent() && complete.usage().outputTokens().isPresent(),
                    "input/output usage missing");
            assertTrue(complete.usage().inputTokens().orElseThrow() > 0 && complete.usage().outputTokens().orElseThrow() > 0,
                    "nonempty live response requires positive measured usage");
            for (int index = 0; index < complete.toolCalls().size(); index++) {
                int selectedIndex = index;
                var deltas = probe.events.stream().filter(StreamEvent.ToolCallDelta.class::isInstance)
                        .map(StreamEvent.ToolCallDelta.class::cast).filter(d -> d.index() == selectedIndex).toList();
                assertFalse(deltas.isEmpty(), "tool delta stream missing");
                var call = complete.toolCalls().get(index);
                assertTrue(deltas.stream().filter(d -> d.callId() != null && !d.callId().isEmpty())
                        .allMatch(d -> d.callId().equals(call.callId())), "tool call identity changed");
                assertTrue(deltas.stream().filter(d -> d.name() != null && !d.name().isEmpty())
                        .allMatch(d -> d.name().equals(call.name())), "tool name changed");
                String arguments = deltas.stream().map(d -> d.argumentsDelta() == null ? "" : d.argumentsDelta())
                        .collect(java.util.stream.Collectors.joining());
                assertTrue(JSON.readTree(arguments).equals(JSON.readTree(call.argumentsJson())), "tool arguments differ from Completed");
            }
            return complete;
        } finally {
            if (probe.subscription != null) probe.subscription.cancel();
        }
    }

    /** 只记录长度和协议块类别；绝不输出正文、签名或原生状态JSON。 */
    private static void safeMessagesMetadata(InvocationResult.Completed result, ReasoningPolicy policy) {
        System.out.printf("LIVE_MESSAGES_DIAGNOSTIC policy=%s reasoningCharacters=%d protocolStatePresent=%s%n",
                policy, result.reasoningContent() == null ? 0 : result.reasoningContent().length(),
                result.protocolState().isPresent());
        if (result.protocolState().isEmpty()) return;
        int index = 0;
        for (var item : result.protocolState().orElseThrow().items()) {
            try {
                var block = JSON.readTree(item.canonicalJson());
                String rawType = block.path("type").asText("");
                String type = switch (rawType) {
                    case "thinking", "redacted_thinking", "text", "tool_use" -> rawType;
                    default -> "other";
                };
                System.out.printf("LIVE_MESSAGES_BLOCK index=%d type=%s thinkingLength=%d signaturePresent=%s%n",
                        index, type, block.path("thinking").isTextual() ? block.path("thinking").textValue().length() : 0,
                        block.path("signature").isTextual() && !block.path("signature").textValue().isEmpty());
            } catch (Exception invalid) {
                System.out.printf("LIVE_MESSAGES_BLOCK index=%d type=unparseable thinkingLength=0 signaturePresent=false%n", index);
            }
            index++;
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "missing environment variable " + name);
        return value;
    }
    private static final class Probe implements Flow.Subscriber<StreamEvent> {
        final List<StreamEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile Flow.Subscription subscription;
        volatile String failureType;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        @Override public void onNext(StreamEvent value) { events.add(value); }
        @Override public void onError(Throwable failure) { failureType = failure.getClass().getSimpleName(); done.countDown(); }
        @Override public void onComplete() { done.countDown(); }
    }
}
