package com.skillforge.runtime.adapter.model.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.*;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式付费Provider验收；每场景仅两次请求，本地合成资源不代表Session/IAM/业务库验收。 */
@EnabledIfEnvironmentVariable(named = "SKILLFORGE_LIVE_NATIVE_MULTICALL", matches = "true")
@Timeout(600)
class LiveProviderNativeMultiCallTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "deepseek-flash";
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final int OUTPUT_TOKENS = 8192;
    @org.junit.jupiter.api.extension.RegisterExtension
    static final LiveProviderAttemptMeter.Outcome liveMeterOutcome = new LiveProviderAttemptMeter.Outcome();
    @org.junit.jupiter.api.AfterEach void collectLiveAttemptEvidence() { LiveProviderAttemptMeter.snapshot(); }

    @Test void serialReadonlySingleToolCanGenerateTwoCalls() throws Exception { verify(false); }
    @Test void readSkillResourceCanGenerateTwoCalls() throws Exception { verify(true); }

    @Test void undeclaredToolCorrectionCanContinueWithDeclaredTool() throws Exception {
        assertEquals("provider-smoke", System.getenv("LIVE_GRADLE_SUITE"), "durable provider meter required");
        String credential = System.getenv("DEEPSEEK_API_KEY");
        assertTrue(credential != null && !credential.isBlank(), "DEEPSEEK_API_KEY required");
        var provider = new DeepSeekModelProvider();
        var service = provider.preset().services().stream().filter(s -> s.serviceId().equals("deepseek-api")).findFirst().orElseThrow();
        var protocol = service.protocols().stream().filter(p -> p.protocol() == ModelProtocol.OPENAI_CHAT_COMPLETIONS).findFirst().orElseThrow();
        var profile = new InvocationProfile(service.serviceId(), service.profileVersion(), protocol.protocol(),
                protocol.authenticationSchemes().stream().findFirst().orElseThrow(), protocol.dialectVersion());
        var tool = new ToolDefinition("serial_readonly_sum", "Return exact a+b using a local readonly implementation.",
                new TypedPayload("json-schema", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\"],\"additionalProperties\":false}"),
                false, ModelToolKind.TOOL_CALL, true);
        // 合成的非法调用只用于确定纠错场景；它及对应 TOOL 结果不进入供应商续传历史。
        var rejected = new ToolCall("synthetic-rejected-id", "undeclared_sum", "{\"a\":17,\"b\":25}");
        assertNotEquals(tool.name(), rejected.name());
        var messages = new ArrayList<Message>();
        messages.add(new Message(Role.USER, "请使用可用的只读工具计算 17 加 25。收到工具结果后只回答数字。", null, List.of()));
        messages.add(new Message(Role.SYSTEM, "本次动作在执行前被拒绝，整组调用均未执行。原因码：tool_not_available。\n"
                + "只从本轮已声明的可用工具中选择；不要使用先前阶段或未声明的工具。整组调用均未执行，请重新提交所需调用。\n"
                + "已使用 1 / 2 次纠正机会。", null, List.of()));
        assertEquals(List.of(Role.USER, Role.SYSTEM), messages.stream().map(Message::role).toList());
        assertTrue(messages.stream().allMatch(message -> message.toolCalls().isEmpty() && message.toolCallId() == null));
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var sends = new AtomicInteger();
            var first = stream(http, provider, profile, service, credential, messages, tool, 1, "undeclared-correction", sends);
            assertEquals(1, sends.get());
            if (first.toolCalls().isEmpty()) {
                assertEquals("stop", first.finishReasonCode());
                assertEquals("42", first.content().strip(), "direct answer must solve the original task");
                return;
            }
            assertEquals("tool_calls", first.finishReasonCode());
            assertEquals(1, first.toolCalls().size(), "only one declared tool call is expected");
            var call = first.toolCalls().getFirst();
            assertEquals(tool.name(), call.name());
            var args = JSON.readTree(call.argumentsJson());
            assertTrue(args.isObject() && args.size() == 2 && args.path("a").isIntegralNumber()
                    && args.path("a").intValue() == 17 && args.path("b").isIntegralNumber() && args.path("b").intValue() == 25);
            assertTrue(first.reasoningContent() != null && !first.reasoningContent().isBlank());
            messages.add(new Message(Role.ASSISTANT, first.content() == null || first.content().isEmpty()
                    ? List.of() : List.of(new ModelContent.Text(first.content())), null, first.toolCalls(), first.reasoningContent(), first.protocolState()));
            messages.add(new Message(Role.TOOL, "42", call.callId(), List.of()));
            var second = stream(http, provider, profile, service, credential, messages, tool, 2, "undeclared-correction", sends);
            assertEquals(2, sends.get(), "at most two send admissions; no retry");
            assertEquals("stop", second.finishReasonCode());
            assertTrue(second.toolCalls().isEmpty());
            assertEquals("42", second.content().strip(), "answer must reflect the local tool receipt");
        }
    }

    private void verify(boolean skill) throws Exception {
        String credential = System.getenv("DEEPSEEK_API_KEY");
        assertTrue(credential != null && !credential.isBlank(), "DEEPSEEK_API_KEY required");
        var provider = new DeepSeekModelProvider();
        var service = provider.preset().services().stream().filter(s -> s.serviceId().equals("deepseek-api")).findFirst().orElseThrow();
        var protocol = service.protocols().stream().filter(p -> p.protocol() == ModelProtocol.OPENAI_CHAT_COMPLETIONS).findFirst().orElseThrow();
        var profile = new InvocationProfile(service.serviceId(), service.profileVersion(), protocol.protocol(),
                protocol.authenticationSchemes().stream().findFirst().orElseThrow(), protocol.dialectVersion());
        String name = skill ? "read_skill_resource" : "serial_readonly_sum";
        var schema = skill
                ? "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"enum\":[\"reference/alpha.txt\",\"reference/beta.txt\"]}},\"required\":[\"path\"],\"additionalProperties\":false}"
                : "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\"],\"additionalProperties\":false}";
        var tool = new ToolDefinition(name, skill
                ? "Read one exact path from synthetic frozen test resources; no external effects."
                : "Return exact a+b using a local readonly implementation. parallel_safe=false; host executes serially.",
                new TypedPayload("json-schema", schema), false, skill ? ModelToolKind.AGENT_ACTION : ModelToolKind.TOOL_CALL, true);
        var messages = new ArrayList<Message>();
        messages.add(new Message(Role.USER, skill
                ? "In your next single assistant turn call read_skill_resource exactly twice: once for reference/alpha.txt and once for reference/beta.txt. Both are synthetic frozen test resources. Do not guess their contents. After both tool results arrive answer only ALPHA=<alpha code>;BETA=<beta code> and make no further calls."
                : "In your next single assistant turn call serial_readonly_sum exactly twice, once with a=17,b=25 and once with a=9,b=14. These are two independent readonly calls; the host executes them serially. Do not calculate instead of calling. After both results arrive answer only FIRST=<first sum>;SECOND=<second sum> and make no further calls.", null, List.of()));
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var sends = new AtomicInteger();
            var first = stream(http, provider, profile, service, credential, messages, tool, 1, skill ? "skill-resource" : "serial-readonly", sends);
            assertEquals(1, sends.get(), "first request must cross the real send admission once");
            assertEquals("tool_calls", first.finishReasonCode());
            assertEquals(2, first.toolCalls().size(), "one assistant turn must contain exactly two calls");
            assertEquals(2, first.toolCalls().stream().map(ToolCall::callId).distinct().count(), "call IDs must remain distinct");
            assertTrue(first.reasoningContent() != null && !first.reasoningContent().isBlank(), "HIGH reasoning must retain native reasoning content");
            var replies = new LinkedHashMap<String, String>();
            var seen = new HashSet<String>();
            for (var call : first.toolCalls()) {
                assertTrue(name.equals(call.name()), "unexpected tool name");
                var args = JSON.readTree(call.argumentsJson());
                assertTrue(args != null && args.isObject(), "tool arguments must be an object");
                if (skill) {
                    assertTrue(args.size() == 1 && args.path("path").isTextual(), "resource requires one exact path");
                    String path = args.path("path").textValue();
                    assertTrue(Set.of("reference/alpha.txt", "reference/beta.txt").contains(path) && seen.add(path), "paths must be exact and distinct");
                    replies.put(call.callId(), path.equals("reference/alpha.txt")
                            ? "Synthetic frozen resource reference/alpha.txt: alpha code = A731."
                            : "Synthetic frozen resource reference/beta.txt: beta code = B946.");
                } else {
                    assertTrue(args.size() == 2 && args.path("a").isIntegralNumber() && args.path("b").isIntegralNumber(), "sum requires exact integer pair");
                    int a = args.path("a").intValue(), b = args.path("b").intValue();
                    String pair = a + ":" + b;
                    assertTrue(Set.of("17:25", "9:14").contains(pair) && seen.add(pair), "sum pairs must be exact and distinct");
                    replies.put(call.callId(), Integer.toString(Math.addExact(a, b)));
                }
            }
            // 原样续传推理、原生状态及全部call ID；本地按返回顺序串行生成真实结果。
            messages.add(new Message(Role.ASSISTANT, first.content() == null || first.content().isEmpty()
                    ? List.of() : List.of(new ModelContent.Text(first.content())), null, first.toolCalls(), first.reasoningContent(), first.protocolState()));
            replies.forEach((id, result) -> messages.add(new Message(Role.TOOL, result, id, List.of())));
            // 这里核对实际交给Provider的续传对象；请求wire另由loopback矩阵验证。
            var assistant = messages.get(1);
            assertTrue(assistant.role() == Role.ASSISTANT && assistant.toolCalls().equals(first.toolCalls()), "continuation must retain all original calls");
            assertTrue(Objects.equals(assistant.reasoningContent(), first.reasoningContent()) && assistant.protocolState().equals(first.protocolState()), "continuation must retain original reasoning and native state");
            int resultIndex = 2;
            for (var entry : replies.entrySet()) {
                var resultMessage = messages.get(resultIndex++);
                assertTrue(resultMessage.role() == Role.TOOL && entry.getKey().equals(resultMessage.toolCallId())
                        && resultMessage.contents().equals(List.of(new ModelContent.Text(entry.getValue()))), "each original call must receive its exact local result");
            }
            var second = stream(http, provider, profile, service, credential, messages, tool, 2, skill ? "skill-resource" : "serial-readonly", sends);
            assertEquals(2, sends.get(), "exactly two send admissions permitted");
            assertEquals("stop", second.finishReasonCode());
            assertTrue(second.toolCalls().isEmpty(), "no third request permitted");
            assertTrue((skill ? "ALPHA=A731;BETA=B946" : "FIRST=42;SECOND=23").equals(second.content().strip()), "answer must summarize both observed results");
            System.out.printf("LIVE_NATIVE_MULTICALL_PASS scenario=%s model=%s requests=2 callIDs=%s inputTokens=%d outputTokens=%d%n",
                    skill ? "skill-resource" : "serial-readonly", MODEL, String.join(",", replies.keySet()),
                    first.usage().inputTokens().orElseThrow() + second.usage().inputTokens().orElseThrow(),
                    first.usage().outputTokens().orElseThrow() + second.usage().outputTokens().orElseThrow());
        }
    }

    private InvocationResult.Completed stream(HttpClient http, DeepSeekModelProvider provider, InvocationProfile profile,
            ModelProviderCatalog.ServiceProfile service, String credential, List<Message> messages, ToolDefinition tool,
            int round, String scenario, AtomicInteger sends) throws Exception {
        var ticket = LiveProviderAttemptMeter.prepare(provider.preset().providerId(), MODEL,
                "native-multicall:" + scenario + ":" + round, 16384, OUTPUT_TOKENS, 65536);
        try (var runtime = ModelProviderRuntime.runtime(LiveProviderAttemptMeter.client(http, ticket), Clock.systemUTC(), 2 * 1024 * 1024)) {
            var request = new InvocationRequest(provider.resolveEndpoint(service, null), credential, MODEL, profile, List.copyOf(messages),
                    new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "live-native-multicall"),
                    new GenerationOptions(OptionalInt.of(OUTPUT_TOKENS), OptionalDouble.empty()), ReasoningPolicy.HIGH,
                    List.of(tool), ModelProvider.ToolChoice.AUTO, TIMEOUT, () -> false, () -> {
                        if (sends.incrementAndGet() > 2) throw new IllegalStateException("native_multicall_send_limit_exceeded");
                        // SDK beforeSend 是保守准入证据，不代表已经观察到网络包。
                        if (ticket != null) {
                            ticket.state.sent(ticket.id);
                            if (!ticket.sent.compareAndSet(false, true)) throw new IllegalStateException("provider_send_admission_repeated");
                        }
                    });
            var probe = new Probe();
            try {
                runtime.stream(provider.preset().providerId(), request).subscribe(LiveProviderAttemptMeter.observe(ticket, probe));
                assertTrue(probe.done.await(180, TimeUnit.SECONDS), "stream exceeded 180s");
                assertTrue(probe.failureType == null, "stream failed without classified terminal");
                var terminals = probe.events.stream().filter(e -> e instanceof StreamEvent.Completed || e instanceof StreamEvent.Rejected || e instanceof StreamEvent.OutcomeUnknown).toList();
                assertEquals(1, terminals.size(), "one classified terminal required");
                String terminalType = switch (terminals.getFirst()) {
                    case StreamEvent.Completed completed -> "Completed";
                    case StreamEvent.Rejected rejected -> "Rejected";
                    case StreamEvent.OutcomeUnknown unknown -> "OutcomeUnknown";
                    default -> throw new IllegalStateException("unexpected_stream_terminal");
                };
                String terminalCode = switch (terminals.getFirst()) {
                    case StreamEvent.Rejected rejected -> rejected.reasonCode();
                    case StreamEvent.OutcomeUnknown unknown -> unknown.reasonCode();
                    default -> "completed";
                };
                System.out.printf("LIVE_NATIVE_MULTICALL_TERMINAL scenario=%s model=%s round=%d type=%s code=%s%n",
                        scenario, MODEL, round, terminalType, terminalCode);
                if (terminals.getFirst() instanceof StreamEvent.Completed completed) {
                    var actual = completed.result();
                    System.out.printf("LIVE_NATIVE_MULTICALL_USAGE scenario=%s model=%s round=%d inputTokens=%d outputTokens=%d calls=%d%n",
                            scenario, MODEL, round,
                            actual.usage().inputTokens().orElse(-1L), actual.usage().outputTokens().orElse(-1L), actual.toolCalls().size());
                }
                assertTrue(terminals.getFirst() instanceof StreamEvent.Completed, "provider did not complete; no retry permitted");
                var result = ((StreamEvent.Completed) terminals.getFirst()).result();
                String text = probe.events.stream().filter(StreamEvent.ContentDelta.class::isInstance).map(StreamEvent.ContentDelta.class::cast).map(StreamEvent.ContentDelta::content).collect(java.util.stream.Collectors.joining());
                String reasoning = probe.events.stream().filter(StreamEvent.ReasoningDelta.class::isInstance).map(StreamEvent.ReasoningDelta.class::cast).map(StreamEvent.ReasoningDelta::content).collect(java.util.stream.Collectors.joining());
                assertTrue(text.equals(result.content() == null ? "" : result.content()), "text stream must equal terminal");
                assertTrue(reasoning.equals(result.reasoningContent() == null ? "" : result.reasoningContent()), "reasoning stream must equal terminal");
                for (int index = 0; index < result.toolCalls().size(); index++) {
                    int selected = index;
                    var deltas = probe.events.stream().filter(StreamEvent.ToolCallDelta.class::isInstance).map(StreamEvent.ToolCallDelta.class::cast).filter(d -> d.index() == selected).toList();
                    var call = result.toolCalls().get(index);
                    assertFalse(deltas.isEmpty(), "tool call deltas required");
                    assertTrue(deltas.stream().filter(d -> d.callId() != null && !d.callId().isEmpty()).allMatch(d -> d.callId().equals(call.callId())), "call ID must remain exact");
                    String arguments = deltas.stream().map(d -> d.argumentsDelta() == null ? "" : d.argumentsDelta()).collect(java.util.stream.Collectors.joining());
                    assertTrue(JSON.readTree(arguments).equals(JSON.readTree(call.argumentsJson())), "stream arguments must equal terminal");
                }
                assertTrue(result.usage().inputTokens().orElseThrow() > 0 && result.usage().outputTokens().orElseThrow() > 0, "positive actual usage required");
                return result;
            } finally {
                if (probe.subscription != null) probe.subscription.cancel();
                LiveProviderAttemptMeter.snapshot();
                System.out.printf("LIVE_NATIVE_MULTICALL_SEND_ADMISSION scenario=%s model=%s round=%d sendAdmissions=%d%n",
                        scenario, MODEL, round, sends.get());
            }
        }
    }

    private static final class Probe implements Flow.Subscriber<StreamEvent> {
        final List<StreamEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile Flow.Subscription subscription;
        volatile String failureType;
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        public void onNext(StreamEvent value) { events.add(value); }
        public void onError(Throwable failure) { failureType = failure.getClass().getSimpleName(); done.countDown(); }
        public void onComplete() { done.countDown(); }
    }

}
