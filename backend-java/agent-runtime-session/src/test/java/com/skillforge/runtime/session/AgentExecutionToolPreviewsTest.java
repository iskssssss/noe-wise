package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionAdmissionFacts;
import com.skillforge.runtime.session.spi.SessionContextPolicy;
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
import org.junit.jupiter.api.Test;

class AgentExecutionToolPreviewsTest {
    private final AgentExecutionCodec codec = new AgentExecutionCodec(SessionFixtures.LIMITS);
    private final ResultRef context = new ResultRef("original-context");
    private final Fixture fixture = new Fixture(SessionContextPolicy.defaults());

    @Test void smallTokenBudgetTrimsResultsShorterThanDefaultPreview() {
        var small = new Fixture(SessionContextPolicy.forInputBudget(1_000));
        var originals = group("small-window", List.of("query"), List.of("查询结果".repeat(600)));
        var projected = AgentExecutionToolPreviews.prepare(originals, context, small.config, small, small.config.modelTools(), 19, codec);
        assertNotEquals(originals.getLast(), projected.messages().getLast());
        assertEquals(originals.getFirst(), projected.messages().getFirst());
        assertTrue(small.measure(small.config, projected.messages(), List.of()).tokensForMessage(1) <= 999);
        assertEquals(1, projected.events().size());
        assertEquals(projected.messages(), AgentExecutionToolPreviews.restore(originals, projected.messages(), context, 19, codec));
    }

    @Test void immediatePreviewKeepsBatchProtocolAndPersistsOneCompleteSafeGroup() {
        var originals = group("batch", List.of("query", "query"), List.of("头😀".repeat(10_000), "尾😀".repeat(10_000)));
        var projected = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 7, codec);

        assertEquals(1, projected.events().size());
        assertEquals(originals.getFirst(), projected.messages().getFirst());
        assertEquals(3, projected.messages().size());
        for (int index = 1; index < originals.size(); index++) {
            var before = originals.get(index); var after = projected.messages().get(index);
            assertEquals(before.messageId(), after.messageId()); assertEquals(before.role(), after.role());
            assertEquals(before.toolCallId(), after.toolCallId()); assertEquals(before.toolCalls(), after.toolCalls());
            assertEquals(before.reasoningContent(), after.reasoningContent()); assertEquals(before.protocolState(), after.protocolState());
            assertTrue(characters(after.textContent()) <= fixture.config.profile().contextPolicy().pressureToolCharacters());
            var body = codec.parse(new TypedPayload("preview", after.textContent()), "preview");
            assertEquals(projected.events().getFirst().id().value(), body.path("source_event_id").asText());
            assertEquals("sf_read_context_reference_7874abcd",body.path("readToolWireName").asText());
            assertTrue(body.path("notice").asText().contains("通过 sf_read_context_reference_7874abcd 的 source_event_id"));
            assertFalse(body.path("notice").asText().contains("通过 read_context_reference 的"));
            assertFalse(body.path("previewComplete").asBoolean()); assertTrue(body.path("sourceComplete").isNull());
            assertEquals(characters(before.textContent()), body.path("characters").asInt());
            assertTrue(body.path("omittedEnd").asInt() > body.path("omittedStart").asInt());
            assertEquals(body.path("headCharacters").asInt(), body.path("tailCharacters").asInt());
        }
        var event = projected.events().getFirst();
        assertEquals(AgentExecutionContextViews.EVENT, event.type());
        var material = codec.parse(event.data(), AgentExecutionContextViews.SCHEMA);
        assertEquals(context.value(), material.path("sourceContext").asText());
        assertEquals(originals, codec.decodeInput(AgentExecutionCodec.readPayload(material.get("messages"))));
        ModelPort.validateConversation(projected.messages());
        int measured = fixture.measurements;
        assertEquals(projected.messages(), AgentExecutionToolPreviews.restore(originals, projected.messages(), context, 7, codec));
        assertEquals(measured, fixture.measurements);
    }

    @Test void pressureUsesConfiguredHeadTailAndCountsEscapedWrapperInCharacterLimit() {
        String text = "\"\\😀".repeat(3_000);
        var originals = new ArrayList<ModelMessage>();
        originals.add(new ModelMessage("system", ModelRole.SYSTEM, "s".repeat(8_000)));
        originals.addAll(group("pressure", List.of("query"), List.of(text)));
        var projected = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 3, codec);
        assertEquals(1, projected.events().size());
        var preview = projected.messages().getLast();
        var body = codec.parse(new TypedPayload("preview", preview.textContent()), "preview");
        assertTrue(characters(preview.textContent()) <= 8_192, "JSON引号和省略封套也计入字符限制");
        assertTrue(body.path("headCharacters").asInt() <= 4_096);
        assertTrue(body.path("tailCharacters").asInt() <= 1_024);
        assertEquals(text.substring(0, text.offsetByCodePoints(0, body.path("headCharacters").asInt())), body.path("head").asText());
        assertEquals(text.substring(text.offsetByCodePoints(0, characters(text) - body.path("tailCharacters").asInt())), body.path("tail").asText());
    }

    @Test void doesNotPreviewSmallResultsOrCropLargeAssistantArguments() {
        var originals = group("small", List.of("query"), List.of("完整结果"));
        var assistant = originals.getFirst();
        var call = assistant.toolCalls().getFirst();
        var largeArguments = new ModelToolCall(call.callId(), call.wireName(), new TypedPayload("arguments", "{\"text\":\"" + "x".repeat(20_000) + "\"}"));
        var input = List.of(new ModelMessage(assistant.messageId(), assistant.role(), assistant.contents(), List.of(largeArguments),
                assistant.toolCallId(), Optional.of("原推理"), Optional.of(new NativeProtocolState(NativeProtocol.RESPONSES,
                        "v1", "model", List.of(new TypedPayload("native", "{}"))))), originals.getLast());
        var projected = AgentExecutionToolPreviews.prepare(input, context, fixture.config, fixture, fixture.config.modelTools(), 1, codec);
        assertEquals(input, projected.messages()); assertTrue(projected.events().isEmpty());
        assertEquals(input, AgentExecutionToolPreviews.restore(input, input, context, 1, codec));
    }

    @Test void latestReadbackRemainsCompleteEvenWhenTimeMessageFollowsItAndOlderReadbackCanAge() {
        var large = "r".repeat(9_000);
        var originals = new ArrayList<>(group("older", List.of("sf_read_context_reference_7874abcd"), List.of(large)));
        originals.addAll(group("latest", List.of("sf_read_context_reference_7874abcd"), List.of(large)));
        originals.add(new ModelMessage("time", ModelRole.SYSTEM, "可信时间"));
        var projected = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 2, codec);
        assertNotEquals(originals.get(1), projected.messages().get(1));
        assertEquals(originals.get(3), projected.messages().get(3));
        assertEquals(1, projected.events().size());
        var immediate = group("read", List.of("sf_read_context_reference_7874abcd"), List.of("x".repeat(20_000)));
        assertEquals(immediate, AgentExecutionToolPreviews.prepare(immediate, context, fixture.config, fixture, fixture.config.modelTools(), 2, codec).messages());
    }

    @Test void oversizedReadbackCanBeOffloadedAfterANewerToolGroup() {
        var originals = new ArrayList<>(group("old-read", List.of("sf_read_context_reference_7874abcd"), List.of("完整原文".repeat(8_000))));
        originals.addAll(group("new-read", List.of("sf_read_context_reference_7874abcd"), List.of("最新原文".repeat(8_000))));
        var projected = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 3, codec);
        assertNotEquals(originals.get(1), projected.messages().get(1));
        assertEquals(originals.get(3), projected.messages().get(3));
        assertEquals(1, projected.events().size());
        var material = codec.parse(projected.events().getFirst().data(), AgentExecutionContextViews.SCHEMA);
        assertEquals(originals.subList(0, 2), codec.decodeInput(AgentExecutionCodec.readPayload(material.get("messages"))));
        assertEquals(projected.messages(), AgentExecutionToolPreviews.restore(originals, projected.messages(), context, 3, codec));
    }

    @Test void aWireNameThatResemblesReadbackDoesNotGetPlatformReadProtection() {
        var originals = group("spoof", List.of("read_context_reference"), List.of("x".repeat(20_000)));
        var projected = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 2, codec);
        assertNotEquals(originals.getLast(), projected.messages().getLast());
        assertEquals(1, projected.events().size());
    }

    @Test void hardWindowShrinksLatestPreviewAndRestoresAfterAnEarlierGroupWasReplaced() {
        var fixed = new ModelMessage("fixed", ModelRole.SYSTEM, "规".repeat(16_000));
        var originals = new ArrayList<>(List.of(fixed)); originals.addAll(group("prefix", List.of("query"), List.of("small")));
        var latest = group("latest-large", List.of("query"), List.of("数😀".repeat(10_000))); originals.addAll(latest);
        var prepared = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 8, codec);
        var reference = new ModelMessage("prefix-reference", ModelRole.USER, "原前缀已回收");
        var reduced = new ArrayList<>(List.of(fixed, reference)); reduced.addAll(prepared.messages().subList(3, 5));
        assertTrue(fixture.measure(fixture.config, reduced, fixture.config.modelTools()).totalTokens() > 20_000);
        var before = codec.parse(new TypedPayload("preview", reduced.getLast().textContent()), "preview");

        var shrunk = AgentExecutionToolPreviews.shrinkToFit(originals, reduced, context, fixture.config, fixture, fixture.config.modelTools(), 8, codec);
        assertTrue(fixture.measure(fixture.config, shrunk.messages(), fixture.config.modelTools()).totalTokens() <= 20_000);
        assertEquals(reduced.subList(0, 3), shrunk.messages().subList(0, 3));
        var after = codec.parse(new TypedPayload("preview", shrunk.messages().getLast().textContent()), "preview");
        assertEquals(before.path("source_event_id"), after.path("source_event_id"));
        assertTrue(after.path("headCharacters").asInt() < before.path("headCharacters").asInt());
        assertTrue(after.path("tailCharacters").asInt() < before.path("tailCharacters").asInt());
        assertEquals(latest.getLast().toolCallId(), shrunk.messages().getLast().toolCallId());
        assertTrue(shrunk.events().isEmpty(), "继续复用prepare已有原文事件，不重复提交同一个event");
        var reducedFull = new ArrayList<>(List.of(fixed, reference)); reducedFull.addAll(latest);
        int measurements = fixture.measurements;
        assertEquals(shrunk.messages(), AgentExecutionToolPreviews.restore(reducedFull, shrunk.messages(), context, 8, codec));
        assertEquals(measurements, fixture.measurements);
    }

    @Test void hardWindowStopsAtAnEmptyPreviewWithoutDroppingFixedContentOrOriginalReference() {
        var fixed = new ModelMessage("too-large-fixed", ModelRole.SYSTEM, "规".repeat(21_000));
        var originals = new ArrayList<>(List.of(fixed)); originals.addAll(group("unfit", List.of("query"), List.of("x".repeat(20_000))));
        var prepared = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 9, codec);
        var shrunk = AgentExecutionToolPreviews.shrinkToFit(originals, prepared.messages(), context, fixture.config, fixture, fixture.config.modelTools(), 9, codec);
        assertEquals(fixed, shrunk.messages().getFirst());
        assertEquals(originals.get(1), shrunk.messages().get(1));
        var body = codec.parse(new TypedPayload("preview", shrunk.messages().getLast().textContent()), "preview");
        assertEquals(0, body.path("headCharacters").asInt()); assertEquals(0, body.path("tailCharacters").asInt());
        assertEquals(prepared.events().getFirst().id().value(), body.path("source_event_id").asText());
        assertTrue(fixture.measure(fixture.config, shrunk.messages(), fixture.config.modelTools()).totalTokens() > 20_000);
    }

    @Test void recoveryRejectsChangedBodySourceContextOrdinalAndDuplicatePreview() {
        var originals = group("replay", List.of("query"), List.of("x".repeat(20_000)));
        var projected = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 4, codec);
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionToolPreviews.restore(originals, projected.messages(), new ResultRef("other"), 4, codec));
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionToolPreviews.restore(originals, projected.messages(), context, 5, codec));
        var changed = group("replay", List.of("query"), List.of("y".repeat(20_000)));
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionToolPreviews.restore(changed, projected.messages(), context, 4, codec));
        var duplicate = new ArrayList<>(projected.messages()); duplicate.add(projected.messages().getLast());
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionToolPreviews.restore(originals, duplicate, context, 4, codec));
    }

    @Test void recoveryUsesMessageIdentityWhenASeparatePrefixGroupHasBeenReplaced() {
        var originals = group("surviving", List.of("query"), List.of("x".repeat(20_000)));
        var withPrefix = new ArrayList<>(group("prefix", List.of("query"), List.of("small"))); withPrefix.addAll(originals);
        var projected = AgentExecutionToolPreviews.prepare(withPrefix, context, fixture.config, fixture, fixture.config.modelTools(), 6, codec);
        var replacement = new ModelMessage("reference", ModelRole.USER, "已冻结前缀引用");
        var reduced = new ArrayList<>(List.of(replacement)); reduced.addAll(originals);
        var frozen = new ArrayList<>(List.of(replacement)); frozen.addAll(projected.messages().subList(2, projected.messages().size()));
        assertEquals(frozen, AgentExecutionToolPreviews.restore(reduced, frozen, context, 6, codec));
    }

    @Test void imageBearingToolResultsKeepOriginalOrderedContent() {
        var original = group("image", List.of("query"), List.of("x".repeat(20_000)));
        var tool = original.getLast();
        var image = new MediaRef(SessionFixtures.OWNER.sessionRef(), "image", "a".repeat(64), "image/png", 10, 1, 1);
        var mixed = new ModelMessage(tool.messageId(), tool.role(), List.of(new ModelContent.Text(tool.textContent()),
                new ModelContent.Image(image, ImageDetail.HIGH)), tool.toolCalls(), tool.toolCallId(), tool.reasoningContent());
        var originals = List.of(original.getFirst(), mixed);
        var projected = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, fixture.config.modelTools(), 1, codec);
        assertEquals(originals, projected.messages()); assertTrue(projected.events().isEmpty());
    }

    @Test void missingReferenceReadCapabilityOrClosingCatalogPreservesOriginalWithoutMeasuring() {
        var originals = group("unreadable", List.of("query"), List.of("x".repeat(20_000)));
        var closed = AgentExecutionToolPreviews.prepare(originals, context, fixture.config, fixture, List.of(), 1, codec);
        assertEquals(originals, closed.messages()); assertTrue(closed.events().isEmpty());
        assertEquals(0, fixture.measurements);
        var c = fixture.config;
        var missing = new AgentExecutionProgram.Configuration(c.profile(), c.target(), List.of(), List.of(), c.budget(),
                c.capabilitySnapshot(), c.capabilityRef(), c.initialMessages(), c.actor(), List.of(), List.of(), Set.of(), List.of(),
                PlanPort.State.absent(), false);
        var noMount = AgentExecutionToolPreviews.prepare(originals, context, missing, fixture, c.modelTools(), 1, codec);
        assertEquals(originals, noMount.messages()); assertTrue(noMount.events().isEmpty());
        assertEquals(0, fixture.measurements);
    }

    @Test void externalToolWithSameLogicalNameCannotSupplyTheReferenceReadWireName() {
        var c=fixture.config;var original=c.tools().getFirst();
        for(var provider:List.of(ToolPortal.ToolBinding.ProviderType.MCP,ToolPortal.ToolBinding.ProviderType.CONNECTOR,ToolPortal.ToolBinding.ProviderType.TEST_MOCK)) {
            var binding=new ToolPortal.ToolBinding(provider,"external","read_context_reference");
            var entry=new SessionToolDirectory.Entry(original.target(),binding,new SessionToolDirectory.Discovery(binding.serverIdentity(),
                    binding.serverIdentity()+"/"+binding.toolName(),binding.toolName(),Optional.empty(),"同名外部工具"),true);
            var config=new AgentExecutionProgram.Configuration(c.profile(),c.target(),c.modelTools(),List.of(entry),c.budget(),c.capabilitySnapshot(),
                    c.capabilityRef(),c.initialMessages(),c.actor(),List.of(),List.of(),Set.of(),List.of(),PlanPort.State.absent(),false);
            assertTrue(AgentExecutionContextViews.referenceReaderWireName(config,config.modelTools()).isEmpty());
            var originals=group("external",List.of("query"),List.of("x".repeat(20_000)));
            assertEquals(originals,AgentExecutionToolPreviews.prepare(originals,context,config,fixture,config.modelTools(),1,codec).messages());
        }
        assertEquals(0,fixture.measurements);
    }

    private static List<ModelMessage> group(String id, List<String> names, List<String> outputs) {
        var calls = new ArrayList<ModelToolCall>();
        for (int index = 0; index < names.size(); index++) calls.add(new ModelToolCall(id + ":" + index, names.get(index), new TypedPayload("arguments", "{\"scope\":\"original\"}")));
        var result = new ArrayList<ModelMessage>();
        result.add(new ModelMessage(id + ":assistant", ModelRole.ASSISTANT, "原调用说明", calls, Optional.empty(), Optional.of("原推理")));
        for (int index = 0; index < outputs.size(); index++) result.add(new ModelMessage(id + ":tool:" + index, ModelRole.TOOL,
                outputs.get(index), List.of(), Optional.of(calls.get(index).callId()), Optional.empty()));
        return List.copyOf(result);
    }

    private static int characters(String value) { return value.codePointCount(0, value.length()); }

    /** 显式按Unicode字符计Token的纯夹具；在所有计量点验证闭合协议，不模拟真实供应商分词。 */
    private static final class Fixture implements AgentExecutionProgram.Sources {
        private final AgentExecutionProgram.Configuration config;
        private int measurements;
        private Fixture(SessionContextPolicy policy) {
            var target = new FrozenModelTarget(new ModelBindingRef(SessionFixtures.TENANT, "binding", 1), "model",
                    new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("capabilities")),
                    Duration.ofSeconds(30), new ResultRef("resolution"));
            var profile = new SessionPreparations.Profile(new ResultRef("profile"), SessionPreparations.PROFILE_VERSION,
                    SessionPreparations.AGENT_INPUT_RENDERER_VERSION, SessionAdmissionFacts.ContextKind.AGENT_INPUT,
                    new SessionPrompt(List.of(new SessionPrompt.Module("system", SessionPrompt.Type.HOST, "v1", "系统"))),
                    target.binding(), new RequestedModelStrategy("strategy", "v1"), new ResponseContract(new ResponseFormat.NativeConversation(), EnforcementLevel.STRICT, "native"),
                    new GenerationOptions(OptionalInt.of(100), OptionalDouble.empty()), ReasoningPolicy.DEFAULT, PortalCallKind.MODEL_GENERATE,
                    new ResultRef("tokens"), policy, 20_000, 100_000, 100, false, Set.of(InboundKind.MESSAGE));
            var tool = new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:read-context"),
                    new CapabilityRef(CapabilityType.TOOL, "read-context", "v1"), "deployment", 1,
                    new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"),
                    ToolEffect.READ_ONLY, true, Duration.ofSeconds(1), true, ToolPortal.ToolConfirmationPolicy.NEVER,
                    Set.of(), Set.of(), false);
            var binding = new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.PLATFORM, "context", "read_context_reference");
            var entry = new SessionToolDirectory.Entry(tool, binding,
                    new SessionToolDirectory.Discovery(binding.serverIdentity(), binding.serverIdentity() + "/" + binding.toolName(), binding.toolName(), Optional.empty(), "读取上下文原文"), true);
            var declaration = new ModelTool("sf_read_context_reference_7874abcd", tool.handle().value(), "读取上下文原文", tool.inputSchema(), false, ModelToolKind.TOOL_CALL, true);
            config = new AgentExecutionProgram.Configuration(profile, target, List.of(declaration), List.of(entry), SessionFixtures.BUDGET,
                    SessionFixtures.CAPABILITY, new ResultRef("capabilities"), List.of(new ModelMessage("user", ModelRole.USER, "输入")),
                    SessionFixtures.ADMISSION, List.of(), List.of(), Set.of(), List.of(), PlanPort.State.absent(), false);
        }
        @Override public SessionTokenCounter.Measurement measure(AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages, List<ModelTool> tools) {
            measurements++; ModelPort.validateConversation(messages);
            var profile = new SessionTokenCounter.Profile(new ResultRef("tokens"), "v1", "fixture", "fixture", configuration.target(),
                    new SessionTokenCounter.RoleCosts(0, 0, 0, 0), 0, 0, 1_000, 1_000_000);
            var counts = messages.stream().map(message -> (long) characters(message.accountingText())).toList();
            return new SessionTokenCounter.Measurement(profile, messages, counts, counts.stream().mapToLong(Long::longValue).sum(), 0, 0, tools, tools.isEmpty() ? 0 : 1);
        }
        @Override public AgentExecutionProgram.Configuration configuration(ExecutionStore.Run run, ResultRef input) { throw new AssertionError(); }
        @Override public List<Optional<ExecutionStore.Material>> materials(ExecutionStore.Run run, List<ResultRef> references) { throw new AssertionError("unexpected material batch read"); }
        @Override public ExecutionStore.Material material(ExecutionStore.Run run, ResultRef reference) { throw new AssertionError(); }
        @Override public AgentExecutionModelMedia.Prepared prepareModelMessages(ExecutionStore.Run run, AgentExecutionProgram.Configuration configuration, List<ModelMessage> messages) { throw new AssertionError(); }
        @Override public AgentExecutionTime.Sample sampleModelTime(ExecutionStore.Run run) { throw new AssertionError(); }
        @Override public Duration elapsed(ExecutionStore.Run run) { throw new AssertionError(); }
    }
}
