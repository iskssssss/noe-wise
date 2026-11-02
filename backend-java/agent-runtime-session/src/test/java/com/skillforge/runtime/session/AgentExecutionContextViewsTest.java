package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionAdmissionFacts;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
import com.skillforge.runtime.session.spi.SessionContextSummary;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionPrompt;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AgentExecutionContextViewsTest {
    private final AgentExecutionCodec codec = new AgentExecutionCodec(SessionFixtures.LIMITS);
    private final ResultRef context = new ResultRef("exact-context");
    private final Fixture fixture = new Fixture();

    @Test void effectiveSummaryResetsCooldownWhenFixedContextKeepsRequestAbovePressure() {
        var original = new ArrayList<ModelMessage>();
        original.add(new ModelMessage("fixed-rules", ModelRole.SYSTEM, "规".repeat(9_500)));
        original.addAll(history("fixed-pressure", 4));
        var candidate = reduce(original, original, 31);
        long tokens = fixture.measure(fixture.config, candidate.messages(), fixture.config.modelTools()).totalTokens();
        assertTrue(tokens >= 15_000 && tokens <= 20_000);
        var fixed = List.of(original.getFirst(), original.get(original.size() - 2), original.getLast());
        long fixedTokens = fixture.measure(fixture.config, fixed, fixture.config.modelTools()).totalTokens();
        assertTrue(tokens - fixedTokens <= fixture.requests.getFirst().maxOutputTokens());
        assertEquals("SUMMARY", view(marker(candidate)).path("kind").asText());
        assertEquals(0, attempt(candidate).path("failures").asInt());
        assertEquals(SessionFixtures.NOW.toString(), attempt(candidate).path("until").asText());
    }

    @Test void lowYieldSummaryAboveItsBudgetStillEntersCooldown() {
        var original = new ArrayList<ModelMessage>();
        original.add(new ModelMessage("fixed-rules", ModelRole.SYSTEM, "规".repeat(9_500)));
        original.addAll(history("low-yield", 4));
        fixture.summary = request -> available(request, "摘要".repeat(4_000));
        var candidate = reduce(original, original, 32);
        assertEquals("SUMMARY", view(marker(candidate)).path("kind").asText());
        var fixed = List.of(original.getFirst(), original.get(original.size() - 2), original.getLast());
        long fixedTokens = fixture.measure(fixture.config, fixed, fixture.config.modelTools()).totalTokens();
        assertTrue(fixture.measure(fixture.config, candidate.messages(), fixture.config.modelTools()).totalTokens()
                - fixedTokens > fixture.requests.getFirst().maxOutputTokens());
        assertEquals(1, attempt(candidate).path("failures").asInt());
        assertEquals(SessionFixtures.NOW.plusSeconds(30).toString(), attempt(candidate).path("until").asText());
    }

    @Test void unavailableSummaryAbovePressureDoesNotResetCooldownForReferenceFallback() {
        var original = new ArrayList<ModelMessage>();
        original.add(new ModelMessage("fixed-rules", ModelRole.SYSTEM, "规".repeat(9_500)));
        original.addAll(history("unavailable", 4));
        fixture.summary = request -> SessionContextSummary.Outcome.unavailable("SUMMARY_DISABLED");
        var candidate = reduce(original, original, 33);
        assertEquals("REFERENCES", view(marker(candidate)).path("kind").asText());
        assertTrue(fixture.measure(fixture.config, candidate.messages(), fixture.config.modelTools()).totalTokens() >= 15_000);
        assertEquals(1, attempt(candidate).path("failures").asInt());
    }

    @Test void summarizesOnlyClosedOldGroupsAndRestoresWithoutAnotherSummaryOrMeasurement() {
        var original = history("first", 4);
        var candidate = reduce(original, original, 1);
        assertEquals(1, fixture.requests.size());
        var requested = fixture.requests.getFirst();
        assertEquals(original.subList(0, 6), requested.history());
        assertEquals(3, requested.history().stream().filter(message -> message.role() == ModelRole.TOOL && message.textContent().length() == 6_000).count());
        assertTrue(requested.maxOutputTokens() > 0);
        var marker = marker(candidate);
        var view = view(marker);
        assertEquals("SUMMARY", view.path("kind").asText()); assertEquals("完整历史摘要", view.path("summary").asText());
        assertEquals("sf_read_context_reference_7874abcd",view.path("readToolWireName").asText());
        assertTrue(view.path("notice").asText().contains("通过 sf_read_context_reference_7874abcd 的 source_event_id"));
        assertFalse(view.path("notice").asText().contains("通过 read_context_reference 的"));
        assertEquals(requested.digest(), view.path("sourceDigest").asText());
        assertEquals(original.subList(6, 8), candidate.messages().subList(1, 3));
        assertTrue(fixture.measure(fixture.config, candidate.messages(), fixture.config.modelTools()).totalTokens() < 20_000);
        var material = candidate.events().stream().filter(event -> event.type().equals(AgentExecutionContextViews.EVENT)).findFirst().orElseThrow();
        var stored = codec.parse(material.data(), AgentExecutionContextViews.SCHEMA);
        assertEquals(original.subList(0, 6), codec.decodeInput(AgentExecutionCodec.readPayload(stored.get("messages"))));
        int measurements = fixture.measurements;
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(original, candidate.messages(), context, 1, codec));
        assertEquals(1, fixture.requests.size()); assertEquals(measurements, fixture.measurements);
        assertEquals("AVAILABLE", attempt(candidate).path("summaryStatus").asText());
    }

    @Test void unavailableSummaryUsesDeterministicReferencesAndKeepsItsOriginalDiagnostic() {
        fixture.summary = request -> SessionContextSummary.Outcome.unavailable("SUMMARY_DISABLED");
        var original = history("unavailable", 4);
        var candidate = reduce(original, original, 2);
        assertEquals("REFERENCES", view(marker(candidate)).path("kind").asText());
        assertTrue(view(marker(candidate)).path("notice").asText().contains("通过 sf_read_context_reference_7874abcd 的 source_event_id"));
        assertEquals("UNAVAILABLE", attempt(candidate).path("summaryStatus").asText());
        assertEquals("SUMMARY_DISABLED", attempt(candidate).path("summaryReason").asText());
        assertTrue(attempt(candidate).path("configurationRef").isNull());
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(original, candidate.messages(), context, 2, codec));
        assertEquals(original.subList(6, 8), candidate.messages().subList(1, 3));
    }

    @Test void summaryWithoutTokenBenefitFallsBackAndRetainsRejectionAndFrozenReferences() {
        fixture.summary = request -> available(request, "无收益".repeat(15_000));
        var original = history("no-benefit", 4);
        var candidate = reduce(original, original, 3);
        assertEquals("REFERENCES", view(marker(candidate)).path("kind").asText());
        var diagnostic = attempt(candidate);
        assertEquals("REJECTED", diagnostic.path("summaryStatus").asText());
        assertEquals("SUMMARY_NO_TOKEN_REDUCTION", diagnostic.path("summaryReason").asText());
        assertEquals("summary-configuration", diagnostic.path("configurationRef").asText());
        assertEquals("summary-invocation", diagnostic.path("invocationRef").asText());
    }

    @Test void actualProjectedPreviewsDoNotReplaceTheCompleteSummarySource() {
        var original = new ArrayList<ModelMessage>(); original.add(new ModelMessage("rules", ModelRole.SYSTEM, "规".repeat(8_000)));
        original.addAll(history("full", 4));
        var projected = original.stream().map(message -> message.role() == ModelRole.TOOL
                ? new ModelMessage(message.messageId(), message.role(), "预".repeat(4_000), message.toolCalls(), message.toolCallId(),
                        message.reasoningContent(), message.protocolState()) : message).toList();
        var candidate = reduce(original, projected, 4);
        var request = fixture.requests.getFirst();
        assertEquals(original.subList(1, 7), request.history());
        assertTrue(request.history().stream().filter(message -> message.role() == ModelRole.TOOL)
                .allMatch(message -> message.textContent().length() == 6_000));
        assertEquals(projected.getLast(), candidate.messages().getLast());
        assertEquals("SUMMARY", view(marker(candidate)).path("kind").asText());
    }

    @Test void allCurrentUserCorrectionsAreIncludedAndDynamicObservationsDoNotChangeSourceDigest() {
        var goal = new ModelMessage("goal", ModelRole.USER, "整理5项，保留精确值和来源");
        var correction = new ModelMessage("correction", ModelRole.USER, "置顶不计入5项");
        var latest = new ModelMessage("latest-user", ModelRole.USER, "已核验信息可以复用");
        var rules = new ModelMessage("rules", ModelRole.SYSTEM, "必须遵守的冻结规则");
        var first = new ArrayList<>(List.of(rules, goal, correction, latest)); first.addAll(history("stable", 4));
        first.add(new ModelMessage("time-context:5", ModelRole.SYSTEM, "UTC 2026-10-27T00:00:00Z"));
        first.add(new ModelMessage("execution-budget:5", ModelRole.SYSTEM, "余额100"));
        first.add(new ModelMessage("execution-output-policy:5", ModelRole.SYSTEM, "原成果观察"));
        var second = new ArrayList<>(first.subList(0, first.size() - 3));
        second.add(new ModelMessage("time-context:5", ModelRole.SYSTEM, "UTC 2026-10-27T00:01:00Z"));
        second.add(new ModelMessage("execution-budget:5", ModelRole.SYSTEM, "余额90"));
        second.add(new ModelMessage("execution-output-policy:5", ModelRole.SYSTEM, "新成果观察"));
        reduce(first, first, 5); reduce(second, second, 5);
        assertEquals(2, fixture.requests.size());
        for (var request : fixture.requests) assertEquals(List.of(rules, goal, correction, latest), request.current());
        assertEquals(fixture.requests.get(0).digest(), fixture.requests.get(1).digest());
        assertEquals(fixture.requests.get(0).history(), fixture.requests.get(1).history());
    }

    @Test void nextCompactionCombinesPreviousSummaryWithNewOldGroupsWithoutAStackOfMarkers() {
        var first = history("original", 4);
        var initial = reduce(first, first, 6);
        var originalMarker = marker(initial);
        var second = new ArrayList<>(initial.messages()); second.addAll(history("new", 3));
        fixture.summary = request -> available(request, "已合并旧摘要及新增精确事实");
        var candidate = reduce(second, second, 7);
        assertEquals(originalMarker, fixture.requests.getLast().history().getFirst());
        assertEquals(7, fixture.requests.getLast().history().size());
        assertEquals(1, candidate.messages().stream().filter(message -> message.messageId().startsWith("execution-context-reference:")).count());
        var view = view(marker(candidate));
        assertEquals("已合并旧摘要及新增精确事实", view.path("summary").asText());
        assertFalse(view.has("retainedSummary"));
        assertEquals(12, view.path("sources").size());
        assertTrue(candidate.messages().stream().noneMatch(message -> message.equals(originalMarker)));
        assertEquals(second.subList(second.size() - 2, second.size()), candidate.messages().subList(candidate.messages().size() - 2, candidate.messages().size()));
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(second, candidate.messages(), context, 7, codec));
    }

    @Test void referenceFallbackAfterPreviousSummaryRetainsItVerbatimAndFlattensTheOldIndex() {
        var first = history("old-summary", 4);
        var initial = reduce(first, first, 8);
        var second = new ArrayList<>(initial.messages()); second.addAll(history("new-fallback", 3));
        fixture.summary = request -> SessionContextSummary.Outcome.unavailable("SUMMARY_RESULT_UNKNOWN", new ResultRef("configuration"), new ResultRef("invocation"));
        var candidate = reduce(second, second, 9);
        var view = view(marker(candidate));
        assertEquals("REFERENCES", view.path("kind").asText());
        assertEquals("完整历史摘要", view.path("retainedSummary").asText());
        assertEquals(12, view.path("sources").size());
        assertEquals("SUMMARY_RESULT_UNKNOWN", attempt(candidate).path("summaryReason").asText());
        assertEquals(1, candidate.messages().stream().filter(message -> message.messageId().startsWith("execution-context-reference:")).count());
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(second, candidate.messages(), context, 9, codec));
    }

    @Test void compactsCompleteTextRoundsAndKeepsLatestPairCurrentInputAndCorrections() {
        var original = new ArrayList<ModelMessage>();
        var rules = new ModelMessage("rules", ModelRole.SYSTEM, "当前权限不变，未知结果不得重试");
        original.add(rules); original.addAll(textHistory(4));
        var input = new ModelMessage("input:current", ModelRole.USER, "按刚才范围整理，仍不含置顶项目");
        var correction = new ModelMessage("execution-steering:1", ModelRole.USER, "数量改为3，仍需保留来源");
        original.add(input); original.add(correction);
        var candidate = reduce(original, original, 10);
        var request = fixture.requests.getFirst();
        assertEquals(original.subList(1, 7), request.history());
        assertEquals(List.of(rules, original.get(7), input, correction), request.current());
        assertFalse(request.current().contains(original.get(1)), "覆盖历史用户消息不能再次作为当前上下文注入");
        assertTrue(candidate.messages().containsAll(original.subList(7, original.size())));
        var event = candidate.events().stream().filter(value -> value.type().equals(AgentExecutionContextViews.EVENT)).findFirst().orElseThrow();
        var payload = codec.parse(event.data(), AgentExecutionContextViews.SCHEMA);
        assertEquals(request.history(), codec.decodeInput(AgentExecutionCodec.readPayload(payload.get("messages"))));
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(original, candidate.messages(), context, 10, codec));
        assertEquals(1, fixture.requests.size());
    }

    @Test void mixedHistoricalToolRoundIsCoveredAsOneCompleteUserRound() {
        var original = new ArrayList<ModelMessage>();
        for (int index = 0; index < 3; index++) {
            original.add(new ModelMessage("history:user-" + index, ModelRole.USER, "问题".repeat(1_500)));
            var call = new ModelToolCall("call-" + index, "query", new TypedPayload("arguments", "{}"));
            original.add(new ModelMessage("history:call-" + index, ModelRole.ASSISTANT, "查询", List.of(call), Optional.empty(), Optional.empty()));
            original.add(new ModelMessage("history:tool-" + index, ModelRole.TOOL, "结果".repeat(3_000), List.of(), Optional.of(call.callId()), Optional.empty()));
            original.add(new ModelMessage("history:answer-" + index, ModelRole.ASSISTANT, "结论".repeat(1_500)));
        }
        original.add(new ModelMessage("input:current", ModelRole.USER, "沿用范围"));
        var candidate = reduce(original, original, 11);
        assertEquals(original.subList(0, 8), fixture.requests.getFirst().history());
        assertTrue(candidate.messages().containsAll(original.subList(8, original.size())));
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(original, candidate.messages(), context, 11, codec));
    }

    @Test void partialSummaryCannotCoverHalfOfAHistoricalUserRound() {
        fixture.summary = request -> SessionContextSummary.Outcome.available(new SessionContextSummary.Result(
                new ResultRef("summary-configuration"), new ResultRef("summary-invocation"), request.digest(), 1, "半轮摘要"));
        var original = textHistory(4);
        var candidate = reduce(original, original, 12);
        assertEquals("REFERENCES", view(marker(candidate)).path("kind").asText());
        assertEquals("SUMMARY_COVERAGE_INVALID", attempt(candidate).path("summaryReason").asText());
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(original, candidate.messages(), context, 12, codec));
    }

    @Test void restoreRejectsChangedHistoricalUserSourceAndTamperedRoundBoundary() {
        var original = textHistory(4);
        var candidate = reduce(original, original, 13);
        var changed = new ArrayList<>(original);
        changed.set(0, new ModelMessage(original.getFirst().messageId(), ModelRole.USER, "原问题被修改"));
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionContextViews.restore(changed, candidate.messages(), context, 13, codec));
        var marker = marker(candidate);
        var altered = (com.fasterxml.jackson.databind.node.ObjectNode) view(marker);
        altered.put("end", "5");
        var frozen = new ArrayList<>(candidate.messages());
        frozen.set(0, new ModelMessage(marker.messageId(), ModelRole.USER, codec.encode("deterministic-context-reference.v4", altered).canonicalJson()));
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionContextViews.restore(original, frozen, context, 13, codec));
    }

    @Test void latestHistoricalPairRemainsAnchoredWhileLaterToolGroupsCanStillCompact() {
        var original = new ArrayList<>(textHistory(1));
        original.add(new ModelMessage("input:current", ModelRole.USER, "把上轮内容转成文档"));
        original.addAll(history("current-tools", 4));
        var candidate = reduce(original, original, 14);
        assertEquals(original.subList(3, 9), fixture.requests.getFirst().history());
        assertEquals(original.subList(0, 3), candidate.messages().subList(0, 3));
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(original, candidate.messages(), context, 14, codec));
    }

    @Test void unclosedHistoricalUserAndCurrentConfirmationNeverBecomeSummaryHistory() {
        var original = new ArrayList<>(textHistory(3));
        var unresolved = new ModelMessage("history:unanswered", ModelRole.USER, "上次问题尚未答复，结果仍未知");
        var confirmation = new ModelMessage("input:confirmation", ModelRole.USER, "仅确认读取，不批准外发");
        original.add(unresolved); original.add(confirmation);
        var candidate = reduce(original, original, 15);
        assertTrue(candidate.messages().contains(unresolved)); assertTrue(candidate.messages().contains(confirmation));
        assertTrue(fixture.requests.getFirst().current().containsAll(List.of(unresolved, confirmation)));
        assertFalse(fixture.requests.getFirst().history().contains(unresolved));
    }

    @Test void oversizedSingleHistoryRoundFallsBackToReferencesWithoutCallingSummary() {
        var original = List.of(
                new ModelMessage("history:user-large", ModelRole.USER, "原问题".repeat(20_000)),
                new ModelMessage("history:answer-large", ModelRole.ASSISTANT, "原答复".repeat(20_000)),
                new ModelMessage("history:user-latest", ModelRole.USER, "最近问题"),
                new ModelMessage("history:answer-latest", ModelRole.ASSISTANT, "最近答复"),
                new ModelMessage("input:current", ModelRole.USER, "继续处理"));
        var candidate = reduce(original, original, 18);
        assertTrue(fixture.requests.isEmpty());
        assertEquals("SUMMARY_INPUT_TOO_LARGE", attempt(candidate).path("summaryReason").asText());
        assertEquals("REFERENCES", view(marker(candidate)).path("kind").asText());
        assertEquals(original.subList(2, 5), candidate.messages().subList(1, 4));
        var event = candidate.events().stream().filter(value -> value.type().equals(AgentExecutionContextViews.EVENT)).findFirst().orElseThrow();
        var payload = codec.parse(event.data(), AgentExecutionContextViews.SCHEMA);
        assertEquals(original.subList(0, 2), codec.decodeInput(AgentExecutionCodec.readPayload(payload.get("messages"))));
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(original, candidate.messages(), context, 18, codec));
    }

    @Test void ordinaryTurnsBelowPressureDoNotCauseExtraSummaryCalls() {
        var original = textHistory(2);
        var candidate = reduce(original, original, 16);
        assertEquals(original, candidate.messages()); assertTrue(candidate.events().isEmpty());
        assertTrue(fixture.requests.isEmpty());
    }

    @Test void softPressureKeepsAllRecentGroupsWhenTheyFitTheRecentBudget() {
        var original = new ArrayList<ModelMessage>();
        original.add(new ModelMessage("fixed-rules", ModelRole.SYSTEM, "规".repeat(15_000)));
        for (int index = 0; index < 2; index++) {
            original.add(new ModelMessage("history:user-" + index, ModelRole.USER, "问".repeat(500)));
            original.add(new ModelMessage("history:answer-" + index, ModelRole.ASSISTANT, "答".repeat(500)));
        }
        original.add(new ModelMessage("input:current", ModelRole.USER, "继续"));
        var candidate = reduce(original, original, 19);
        assertEquals(original, candidate.messages());
        assertTrue(candidate.events().isEmpty());
        assertTrue(fixture.requests.isEmpty(), "近期内容未超过预算时不额外调用摘要模型");
    }

    @Test void hardOverflowCanStillCompactOlderGroupsBelowRecentBudget() {
        var original = new ArrayList<ModelMessage>();
        original.add(new ModelMessage("fixed-rules", ModelRole.SYSTEM, "规".repeat(19_000)));
        for (int index = 0; index < 2; index++) {
            original.add(new ModelMessage("history:user-" + index, ModelRole.USER, "问".repeat(600)));
            original.add(new ModelMessage("history:answer-" + index, ModelRole.ASSISTANT, "答".repeat(600)));
        }
        original.add(new ModelMessage("input:current", ModelRole.USER, "继续"));
        var candidate = reduce(original, original, 20);
        assertFalse(candidate.events().isEmpty());
        assertEquals(original.subList(3, original.size()), candidate.messages().subList(2, candidate.messages().size()));
        assertEquals(candidate.messages(), AgentExecutionContextViews.restore(original, candidate.messages(), context, 20, codec));
    }

    @Test void arbitraryUserMessagesAreNotClassifiedAsTrustedHistoricalRounds() {
        var original = textHistory(4).stream().map(message -> new ModelMessage(
                message.messageId().replace("history:", "current:"), message.role(), message.textContent())).toList();
        var candidate = reduce(original, original, 17);
        assertEquals(original, candidate.messages()); assertTrue(candidate.events().isEmpty());
        assertTrue(fixture.requests.isEmpty());
    }

    private static List<ModelMessage> textHistory(int count) {
        var messages = new ArrayList<ModelMessage>();
        for (int index = 0; index < count; index++) {
            messages.add(new ModelMessage("history:user-" + index, ModelRole.USER, "问题与范围".repeat(600)));
            messages.add(new ModelMessage("history:answer-" + index, ModelRole.ASSISTANT, "答复与限制".repeat(600)));
        }
        return List.copyOf(messages);
    }

    private AgentExecutionContextViews.Candidate reduce(List<ModelMessage> original, List<ModelMessage> projected, long ordinal) {
        return AgentExecutionContextViews.reduce(original, projected, context, ordinal, fixture.config, fixture,
                fixture.config.modelTools(), SessionFixtures.NOW, fixture.run, true, codec);
    }
    private ModelMessage marker(AgentExecutionContextViews.Candidate candidate) {
        return candidate.messages().stream().filter(message -> message.messageId().startsWith("execution-context-reference:")).findFirst().orElseThrow();
    }
    private JsonNode view(ModelMessage marker) {
        return codec.parse(new TypedPayload("deterministic-context-reference.v4", marker.textContent()), "deterministic-context-reference.v4");
    }
    private JsonNode attempt(AgentExecutionContextViews.Candidate candidate) {
        return candidate.events().stream().filter(event -> event.type().equals("agent.context-compaction-attempt"))
                .map(event -> codec.parse(event.data(), "skillforge.runtime.execution.context-compaction-attempt.v1")).findFirst().orElseThrow();
    }
    private static SessionContextSummary.Outcome available(SummaryInput input, String content) {
        return SessionContextSummary.Outcome.available(new SessionContextSummary.Result(new ResultRef("summary-configuration"),
                new ResultRef("summary-invocation"), input.digest(), input.history().size(), content));
    }
    private static List<ModelMessage> history(String prefix, int count) {
        var messages = new ArrayList<ModelMessage>();
        for (int index = 0; index < count; index++) {
            var call = new ModelToolCall(prefix + ":call:" + index, "query", new TypedPayload("arguments", "{\"query\":\"原参数\"}"));
            messages.add(new ModelMessage(prefix + ":assistant:" + index, ModelRole.ASSISTANT, "查询", List.of(call), Optional.empty(), Optional.of("原推理")));
            messages.add(new ModelMessage(prefix + ":tool:" + index, ModelRole.TOOL, Integer.toString(index).repeat(6_000), List.of(), Optional.of(call.callId()), Optional.empty()));
        }
        return List.copyOf(messages);
    }
    private record SummaryInput(List<ModelMessage> history, List<ModelMessage> current, String digest, int maxOutputTokens) { }

    /** 只按Unicode字符计量的显式纯夹具；不调用Provider、持久存储或当前配置。 */
    private static final class Fixture implements AgentExecutionProgram.Sources {
        private final AgentExecutionProgram.Configuration config;
        private final ExecutionStore.Run run;
        private final List<SummaryInput> requests = new ArrayList<>();
        private Function<SummaryInput, SessionContextSummary.Outcome> summary = request -> available(request, "完整历史摘要");
        private int measurements;
        private Fixture() {
            var target = new FrozenModelTarget(new ModelBindingRef(SessionFixtures.TENANT, "binding", 1), "model",
                    new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("capabilities")), Duration.ofSeconds(30), new ResultRef("resolution"));
            var profile = new SessionPreparations.Profile(new ResultRef("profile"), SessionPreparations.PROFILE_VERSION,
                    SessionPreparations.AGENT_INPUT_RENDERER_VERSION, SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                    new SessionPrompt(List.of(new SessionPrompt.Module("system", SessionPrompt.Type.HOST, "v1", "系统"))), target.binding(),
                    new RequestedModelStrategy("strategy", "v1"), new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "native"),
                    new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DEFAULT, PortalCallKind.MODEL_GENERATE,
                    new ResultRef("tokens"), SessionContextPolicy.defaults(), 20_000, 100_000, 100, false, Set.of(InboundKind.MESSAGE));
            var tool = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:read-context"), new CapabilityRef(CapabilityType.TOOL, "read-context", "v1"),
                    "deployment", 1, new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"), ToolEffect.READ_ONLY,
                    true, Duration.ofSeconds(1), true, ToolPortal.ToolConfirmationPolicy.NEVER, Set.of(), Set.of(), false);
            var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.PLATFORM, "context", "read_context_reference");
            var entry = new SessionToolDirectory.Entry(tool, binding, new SessionToolDirectory.Discovery(binding.serverIdentity(), binding.serverIdentity() + "/" + binding.toolName(),
                    binding.toolName(), Optional.empty(), "读取上下文原文"), true);
            var declaration = new ModelTool("sf_read_context_reference_7874abcd", tool.handle().value(), "读取上下文原文", tool.inputSchema(), false, ModelToolKind.TOOL_CALL, true);
            config = new AgentExecutionProgram.Configuration(profile, target, List.of(declaration), List.of(entry), SessionFixtures.BUDGET, SessionFixtures.CAPABILITY,
                    new ResultRef("capabilities"), List.of(new ModelMessage("user", ModelRole.USER, "输入")), SessionFixtures.ADMISSION,
                    List.of(), List.of(), Set.of(), List.of(), PlanPort.State.absent(), false);
            run = new ExecutionStore.Run(SessionFixtures.IDENTITY, profile.reference(), RunStatus.RUNNING, 1, 0, false, ExecutionStore.Phase.READY,
                    new ExecutionStore.Progress(0, 0, 0, new ResultRef("context")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    SessionFixtures.ADMISSION.verificationRef(), Optional.empty(), SessionFixtures.NOW, SessionFixtures.NOW);
        }
        @Override public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages, List<ModelTool> tools) {
            measurements++; ModelPort.validateConversation(messages);
            var profile = new SessionTokenCounter.Profile(new ResultRef("tokens"), "v1", "fixture", "fixture", configuration.target(),
                    new SessionTokenCounter.RoleCosts(0, 0, 0, 0), 0, 0, 1_000, 1_000_000);
            var counts = messages.stream().map(message -> (long) message.accountingText().codePointCount(0, message.accountingText().length())).toList();
            return new SessionTokenCounter.Measurement(profile, messages, counts, counts.stream().mapToLong(Long::longValue).sum(), 0, 0, tools, tools.isEmpty() ? 0 : 1);
        }
        @Override public SessionContextSummary.Outcome summarizeContext(ExecutionStore.Run run, AgentExecutionProgram.Configuration config,
                List<ModelMessage> history, List<ModelMessage> current, String digest, int maxOutputTokens, long ordinal) {
            var request = new SummaryInput(List.copyOf(history), List.copyOf(current), digest, maxOutputTokens); requests.add(request); return summary.apply(request);
        }
        @Override public AgentExecutionProgram.Configuration configuration(ExecutionStore.Run run, ResultRef input) { throw new AssertionError(); }
        @Override public List<Optional<ExecutionStore.Material>> materials(ExecutionStore.Run run, List<ResultRef> references) { throw new AssertionError("unexpected material batch read"); }
        @Override public ExecutionStore.Material material(ExecutionStore.Run run, ResultRef reference) { throw new AssertionError(); }
        @Override public AgentExecutionModelMedia.Prepared prepareModelMessages(ExecutionStore.Run run, AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages) { throw new AssertionError(); }
        @Override public AgentExecutionTime.Sample sampleModelTime(ExecutionStore.Run run) { throw new AssertionError(); }
        @Override public Duration elapsed(ExecutionStore.Run run) { throw new AssertionError(); }
    }
}
