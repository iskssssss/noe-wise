package com.skillforge.runtime.adapter.model;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.session.spi.SessionTokenCounter.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JTokkitSessionTokenCounterTest {
    @Test void repeatedImmutableToolsetReusesProjectionAndPreservesCompleteMeasurement() throws Exception {
        int repeats = 4;
        var registered = profile(TARGET, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 100, 65_536);
        var tools = java.util.stream.IntStream.range(0, 40).mapToObj(index -> new ModelTool(
                "lookup_" + index, "tool-handle-" + index, "只读目录工具 " + index,
                new TypedPayload("schema", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}"),
                true, ModelToolKind.TOOL_CALL, true)).toList();
        var messages = List.of(new ModelMessage("user", ModelRole.USER, "读取目录并核对完整计量"));
        var measured = new ArrayList<Measurement>();
        var counter = counter(registered);
        for (int repeat = 0; repeat < repeats; repeat++) {
            measured.add(counter.measure(PROFILE, TARGET, messages, tools));
        }
        long projections = counter.toolProjectionsPerformed();
        long encodings = counter.encodingsPerformed();
        System.out.printf(Locale.ROOT, "tool_projection_measurement repeats=%d tools=%d projections=%d encodings=%d%n",
                repeats, tools.size(), projections, encodings);
        record CostSnapshot(List<Long> contentTokenCounts, long contentTokens, long roleTokens, long framingTokens,
                long toolTokens, List<Long> imageTokenCounts, List<Long> variantTokenCounts,
                long replyPrimingTokens, long totalTokens, List<Long> tokensForMessage) { }
        var first = measured.getFirst();
        var cost = new CostSnapshot(first.contentTokenCounts(), first.contentTokens(), first.roleTokens(),
                first.framingTokens(), first.toolTokens(), first.imageTokenCounts(), first.variantTokenCounts(),
                first.replyPrimingTokens(), first.totalTokens(), java.util.stream.IntStream.range(0, messages.size())
                        .mapToObj(first::tokensForMessage).toList());
        String costDigest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(cost.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        System.out.println("tool_projection_cost_snapshot=" + cost);
        System.out.println("tool_projection_cost_sha256=" + costDigest);
        var fresh = counter(registered).measure(PROFILE, TARGET, messages, tools);
        for (var result : measured) {
            assertEquals(registered, result.profile());
            assertEquals(messages, result.messages());
            assertEquals(tools, result.tools());
            assertEquals(measured.getFirst(), result);
            assertEquals(fresh, result);
        }
        assertEquals(1L, projections, "同一不可变工具集的四次完整计量只应实际构造一次工具投影");
    }

    @Test void toolCacheKeysIncludeEveryFrozenFieldAndOrderedToolIdentity() {
        var messages = List.of(new ModelMessage("user", ModelRole.USER, "核对"));
        var schema = new TypedPayload("schema.a", "{\"type\":\"object\"}");
        var base = new ModelTool("read_skill_resource", "handle", "description", schema, true, ModelToolKind.TOOL_CALL, true);
        var counter = counter(CONFIG);
        counter.measure(PROFILE, TARGET, messages, List.of(base));
        var variants = List.of(
                new ModelTool("other_name", base.toolHandle(), base.description(), schema, true, base.kind(), true),
                new ModelTool(base.wireName(), "other_handle", base.description(), schema, true, base.kind(), true),
                new ModelTool(base.wireName(), base.toolHandle(), "other_description", schema, true, base.kind(), true),
                new ModelTool(base.wireName(), base.toolHandle(), base.description(), new TypedPayload("schema.b", schema.canonicalJson()), true, base.kind(), true),
                new ModelTool(base.wireName(), base.toolHandle(), base.description(), new TypedPayload("schema.a", "{\"type\":\"object\",\"title\":\"changed\"}"), true, base.kind(), true),
                new ModelTool(base.wireName(), base.toolHandle(), base.description(), schema, false, base.kind(), true),
                new ModelTool(base.wireName(), base.toolHandle(), base.description(), schema, true, ModelToolKind.AGENT_ACTION, true),
                new ModelTool(base.wireName(), base.toolHandle(), base.description(), schema, true, base.kind(), false));
        for (var changed : variants) {
            long before = counter.toolProjectionsPerformed();
            var measured = counter.measure(PROFILE, TARGET, messages, List.of(changed));
            assertEquals(before + 1, counter.toolProjectionsPerformed());
            assertEquals(counter(CONFIG).measure(PROFILE, TARGET, messages, List.of(changed)), measured);
            assertEquals(measured, counter.measure(PROFILE, TARGET, messages, List.of(changed)));
            assertEquals(before + 1, counter.toolProjectionsPerformed());
        }
        var ordered = List.of(base, variants.getFirst());
        var reversed = List.of(variants.getFirst(), base);
        counter.measure(PROFILE, TARGET, messages, ordered);
        long before = counter.toolProjectionsPerformed();
        assertEquals(counter(CONFIG).measure(PROFILE, TARGET, messages, reversed),
                counter.measure(PROFILE, TARGET, messages, reversed));
        assertEquals(before + 1, counter.toolProjectionsPerformed());
        counter.measure(PROFILE, TARGET, messages, ordered);
        assertEquals(before + 1, counter.toolProjectionsPerformed());
    }

    @Test void encodingIsPartOfTheToolKeyAndEachWarmResultMatchesAnIndependentCounter() {
        var current = new java.util.concurrent.atomic.AtomicReference<>(CONFIG);
        var counter = new JTokkitSessionTokenCounter(ref -> Optional.of(current.get()));
        var messages = List.of(new ModelMessage("user", ModelRole.USER, "中文😀 café"));
        var tools = List.of(cacheTool("lookup", "handle"));
        var first = counter.measure(PROFILE, TARGET, messages, tools);
        current.set(profile(TARGET, "p50k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, 4096));
        var other = counter.measure(PROFILE, TARGET, messages, tools);
        assertEquals(counter(current.get()).measure(PROFILE, TARGET, messages, tools), other);
        assertEquals(2, counter.toolProjectionsPerformed());
        current.set(CONFIG);
        assertEquals(first, counter.measure(PROFILE, TARGET, messages, tools));
        assertEquals(2, counter.toolProjectionsPerformed());
    }

    @Test void emptyClosingSubsetsAndMutableCallerListsNeverReuseTheWrongToolset() {
        var messages = List.of(new ModelMessage("user", ModelRole.USER, "hello"));
        var first = cacheTool("first", "first");
        var second = cacheTool("second", "second");
        var tools = new ArrayList<>(List.of(first, second));
        var counter = counter(CONFIG);
        var full = counter.measure(PROFILE, TARGET, messages, tools);
        tools.removeLast();
        var closing = counter.measure(PROFILE, TARGET, messages, tools);
        assertEquals(counter(CONFIG).measure(PROFILE, TARGET, messages, List.of(first)), closing);
        assertEquals(List.of(first, second), full.tools());
        assertEquals(2, counter.toolProjectionsPerformed());
        tools.clear();
        var empty = counter.measure(PROFILE, TARGET, messages, tools);
        assertEquals(counter(CONFIG).measure(PROFILE, TARGET, messages), empty);
        assertEquals(0, empty.toolTokens());
        assertEquals(2, counter.toolProjectionsPerformed());
        tools.addAll(List.of(first, second));
        assertEquals(full, counter.measure(PROFILE, TARGET, messages, tools));
        assertEquals(2, counter.toolProjectionsPerformed());
    }

    @Test void oversizedRawSchemaAndInvisibleHandleAreMeasuredButNeverRetained() {
        int limit = 2 * 1024 * 1024;
        var registered = profile(TARGET, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, limit * 3);
        var messages = List.of(new ModelMessage("user", ModelRole.USER, "hello"));
        var small = cacheTool("lookup", "small");
        // 原始schema只加JSON空白，投影后仍是小对象；opaque handle本就不进入模型正文，避免巨量tokenize压力。
        var largeSchema = new ModelTool("schema", "small", "description",
                new TypedPayload("schema", " ".repeat(limit + 1) + "{\"type\":\"object\"}"), true, ModelToolKind.TOOL_CALL, false);
        var hugeHandle = cacheTool("handle", "h".repeat(limit + 1));
        var counter = counter(registered);
        var baseline = counter.measure(PROFILE, TARGET, messages, List.of(small));
        for (var tool : List.of(largeSchema, hugeHandle)) {
            long before = counter.toolProjectionsPerformed();
            var actual = counter.measure(PROFILE, TARGET, messages, List.of(tool));
            assertEquals(counter(registered).measure(PROFILE, TARGET, messages, List.of(tool)), actual);
            assertEquals(actual, counter.measure(PROFILE, TARGET, messages, List.of(tool)));
            assertEquals(before + 2, counter.toolProjectionsPerformed());
        }
        long before = counter.toolProjectionsPerformed();
        assertEquals(baseline, counter.measure(PROFILE, TARGET, messages, List.of(small)));
        assertEquals(before, counter.toolProjectionsPerformed(), "不留存超大key也不应冲掉原有小key");
    }

    @Test void textAndToolsShareTheCharacterBudgetAndTheSameEntryLru() {
        var registered = profile(TARGET, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, 4 * 1024 * 1024);
        var messages = List.of(new ModelMessage("user", ModelRole.USER, "shared text"));
        var counter = counter(registered);
        var first = cacheTool("lookup", "h".repeat(1_100_000));
        var second = cacheTool("lookup", "j".repeat(1_100_000));
        var actual = counter.measure(PROFILE, TARGET, messages, List.of(first));
        counter.measure(PROFILE, TARGET, messages, List.of(second));
        assertEquals(actual, counter.measure(PROFILE, TARGET, messages, List.of(first)));
        assertEquals(3, counter.toolProjectionsPerformed(), "两key总字符超2Mi时旧tool必须淘汰");
        var characters = counter(registered);
        characters.measure(PROFILE, TARGET, messages, List.of(first));
        // 一百万空白文本编码简单；与不可见handle共同越过字符预算，不构造复杂巨型schema投影。
        var largeText = List.of(new ModelMessage("large", ModelRole.USER, " ".repeat(1_000_000)));
        assertEquals(counter(registered).measure(PROFILE, TARGET, largeText, List.of(first)),
                characters.measure(PROFILE, TARGET, largeText, List.of(first)));
        assertEquals(2, characters.toolProjectionsPerformed(), "文本key同样参与工具key的2Mi字符预算");
        var entries = counter(CONFIG);
        for (int index = 0; index < 2047; index++)
            entries.measure(PROFILE, TARGET, messages, List.of(cacheTool("lookup", "handle-" + index)));
        long before = entries.toolProjectionsPerformed();
        entries.measure(PROFILE, TARGET, messages, List.of(cacheTool("lookup", "handle-0")));
        assertEquals(before, entries.toolProjectionsPerformed());
        entries.measure(PROFILE, TARGET, messages, List.of(cacheTool("lookup", "handle-2047")));
        entries.measure(PROFILE, TARGET, messages, List.of(cacheTool("lookup", "handle-0")));
        var evicted = entries.measure(PROFILE, TARGET, messages, List.of(cacheTool("lookup", "handle-1")));
        assertEquals(before + 2, entries.toolProjectionsPerformed(), "2048总entry包含消息文本；热tool0保留，冷tool1淘汰");
        assertEquals(counter(CONFIG).measure(PROFILE, TARGET, messages, List.of(cacheTool("lookup", "handle-1"))), evicted);
    }

    @Test void warmToolHitsStillRejectMidMeasurementProfileProviderDriftAndChangedLimits() {
        var messages = List.of(new ModelMessage("user", ModelRole.USER, "hello world"));
        var tools = List.of(cacheTool("lookup", "handle"));
        var changed = profile(TARGET, "cl100k_base", "future", 10, 4096);
        var reads = new AtomicInteger();
        var profileDrift = new JTokkitSessionTokenCounter(ref -> Optional.of(reads.incrementAndGet() <= 3 ? CONFIG : changed));
        profileDrift.measure(PROFILE, TARGET, messages, tools);
        assertThrows(IllegalStateException.class, () -> profileDrift.measure(PROFILE, TARGET, messages, tools));
        assertEquals(1, profileDrift.toolProjectionsPerformed());
        var provider = messagesProfile();
        var otherProvider = new OpenAiModelPort.ProviderProfile(provider.providerId(), provider.serviceId(), "future",
                provider.protocol(), provider.authenticationScheme(), provider.dialectVersion());
        var providerReads = new AtomicInteger();
        var providerDrift = new JTokkitSessionTokenCounter(ref -> Optional.of(CONFIG),
                target -> providerReads.incrementAndGet() <= 3 ? provider : otherProvider);
        providerDrift.measure(PROFILE, TARGET, messages, tools);
        assertThrows(IllegalStateException.class, () -> providerDrift.measure(PROFILE, TARGET, messages, tools));
        assertEquals(1, providerDrift.toolProjectionsPerformed());
        var current = new java.util.concurrent.atomic.AtomicReference<>(CONFIG);
        var limited = new JTokkitSessionTokenCounter(ref -> Optional.of(current.get()));
        limited.measure(PROFILE, TARGET, messages, tools);
        current.set(profile(TARGET, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, 5));
        assertThrows(IllegalArgumentException.class, () -> limited.measure(PROFILE, TARGET, messages, tools));
        assertEquals(1, limited.toolProjectionsPerformed());
    }

    private static ModelTool cacheTool(String name, String handle) {
        return new ModelTool(name, handle, "description", new TypedPayload("schema", "{\"type\":\"object\"}"),
                true, ModelToolKind.TOOL_CALL, false);
    }

    @Test void nativeMessagesToolImagesUseFrozenVisualBoundWithoutChatBridge() {
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4, 1024, 4096,
                2048, 512, 1024, 0, "deepseek-vision-bound.v1");
        var target = new FrozenModelTarget(TARGET.binding(), TARGET.effectiveModelId(),
                new ModelCapabilitySnapshot(TARGET.capabilities().responseFormats(), false, false,
                        TARGET.capabilities().capabilityFactRef(), policy), TARGET.timeout(), TARGET.resolutionRef());
        var owner = new com.skillforge.runtime.api.SessionRef(new TenantId("tenant"), "session");
        var media = new com.skillforge.runtime.api.media.MediaRef(owner, "media", "0".repeat(64), "image/png", 100, 513, 512);
        var image = new com.skillforge.runtime.api.media.ModelContent.Image(media, com.skillforge.runtime.api.media.ImageDetail.HIGH);
        var result = new ModelMessage("tool", ModelRole.TOOL,
                List.of(new com.skillforge.runtime.api.media.ModelContent.Text("result"), image),
                List.of(), Optional.of("call-1"), Optional.empty());
        var registered = profile(target, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 1, 4096);
        var measured = new JTokkitSessionTokenCounter(ref -> Optional.of(registered), frozen -> messagesProfile())
                .measure(PROFILE, target, List.of(result));
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        assertEquals(List.of(1024L), measured.imageTokenCounts());
        assertEquals(List.of(0L), measured.variantTokenCounts());
        assertEquals(1024L + encoding.encodeOrdinary(result.accountingText()).size(), measured.contentTokens());
        assertEquals(1024L, ModelImageAccounting.tokens(new com.skillforge.runtime.api.media.ModelContent.Image(
                media, com.skillforge.runtime.api.media.ImageDetail.LOW), policy));
        assertEquals(WireCapacityExceeded.Resource.MESSAGES, assertThrows(WireCapacityExceeded.class,
                () -> counter(registered).measure(PROFILE, target, List.of(result))).resource());
    }

    @Test void metersFrozenImageTilesAndToolBridgeSeparatelyFromText() {
        var policy = new com.skillforge.runtime.api.media.ModelImagePolicy(true, true, 4, 1024, 4096,
                2048, 512, 85, 170, "vision-test.v1");
        var target = new FrozenModelTarget(TARGET.binding(), TARGET.effectiveModelId(),
                new ModelCapabilitySnapshot(TARGET.capabilities().responseFormats(), false, false,
                        TARGET.capabilities().capabilityFactRef(), policy), TARGET.timeout(), TARGET.resolutionRef());
        var registered = profile(target, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, 4096);
        var owner = new com.skillforge.runtime.api.SessionRef(new TenantId("tenant"), "session");
        var media = new com.skillforge.runtime.api.media.MediaRef(owner, "media", "0".repeat(64), "image/png", 100, 513, 512);
        var image = new com.skillforge.runtime.api.media.ModelContent.Image(media, com.skillforge.runtime.api.media.ImageDetail.HIGH);
        var pure = new ModelMessage("pure", ModelRole.USER, List.of(image));
        var result = new ModelMessage("tool", ModelRole.TOOL,
                List.of(new com.skillforge.runtime.api.media.ModelContent.Text("result"), image),
                List.of(), Optional.of("call-1"), Optional.empty());
        var measured = counter(registered).measure(PROFILE, target, List.of(pure, result));
        assertEquals(List.of(425L, 425L), measured.imageTokenCounts());
        assertEquals(0L, measured.variantTokenCounts().getFirst());
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        long bridge = encoding.encodeOrdinary(ModelImageAccounting.toolImageAnchor("call-1") + "result").size()
                + registered.roleCosts().user() + registered.perMessageFramingTokens();
        assertEquals(bridge, measured.variantTokenCounts().getLast());
        assertEquals(425L, measured.contentTokenCounts().getFirst());
        assertEquals(425L + bridge + encoding.encodeOrdinary(result.accountingText()).size(), measured.contentTokenCounts().getLast());
        assertEquals(WireCapacityExceeded.Resource.MESSAGES, assertThrows(WireCapacityExceeded.class,
                () -> counter(profile(target, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 1, 4096))
                        .measure(PROFILE,target,List.of(result))).resource());
        assertEquals(WireCapacityExceeded.Resource.CONTENT_CODE_UNITS, assertThrows(WireCapacityExceeded.class,
                () -> counter(profile(target, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10,
                        result.accountingText().length())).measure(PROFILE,target,List.of(result))).resource());
        assertThrows(IllegalArgumentException.class, () -> counter(CONFIG).measure(PROFILE, TARGET, List.of(pure)));
        assertEquals(85L, ModelImageAccounting.tokens(new com.skillforge.runtime.api.media.ModelContent.Image(
                media, com.skillforge.runtime.api.media.ImageDetail.LOW), policy));
        var noTools = new com.skillforge.runtime.api.media.ModelImagePolicy(true, false, 4, 1024,
                4096, 2048, 512, 85, 170, "vision-no-tools.v1");
        var noToolsTarget = new FrozenModelTarget(target.binding(), target.effectiveModelId(),
                new ModelCapabilitySnapshot(target.capabilities().responseFormats(), false, false,
                        target.capabilities().capabilityFactRef(), noTools), target.timeout(), target.resolutionRef());
        assertEquals("session_tokenizer_profile_rejected", assertThrows(IllegalStateException.class,
                () -> counter(profile(noToolsTarget, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION,
                        10, 4096)).measure(PROFILE, noToolsTarget, List.of(result))).getMessage());
    }
    @Test void repeatedExactContentReusesEncodingButStillChecksProfileAndTarget() {
        var reads = new AtomicInteger();
        var counter = new JTokkitSessionTokenCounter(ref -> { reads.incrementAndGet(); return Optional.of(CONFIG); });
        var message = new ModelMessage("one", ModelRole.USER, "精确计量😀");
        var tool = new ModelTool("lookup", "handle", "查询", new TypedPayload("schema", "{\"type\":\"object\"}"), true, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false);
        var first = counter.measure(PROFILE, TARGET, List.of(message), List.of(tool));
        long encodings = counter.encodingsPerformed();
        assertEquals(first, counter.measure(PROFILE, TARGET, List.of(message), List.of(tool)));
        assertEquals(encodings, counter.encodingsPerformed());
        assertEquals(4, reads.get());
        var changed = new ModelTool("lookup", "handle", "不同定义", tool.inputSchema(), true, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false);
        var measured = counter.measure(PROFILE, TARGET, List.of(message), List.of(changed));
        assertEquals(encodings + 1, counter.encodingsPerformed());
        assertEquals(counter(CONFIG).measure(PROFILE, TARGET, List.of(message), List.of(changed)), measured);
        var changedRole = counter.measure(PROFILE, TARGET, List.of(new ModelMessage("two", ModelRole.SYSTEM, message.contents())), List.of(changed));
        assertEquals(encodings + 1, counter.encodingsPerformed());
        assertEquals(first.contentTokenCounts(), changedRole.contentTokenCounts());
        assertNotEquals(first.roleTokens(), changedRole.roleTokens());
    }
    private static final ResultRef PROFILE = new ResultRef("registered-profile");
    private static final FrozenModelTarget TARGET = new FrozenModelTarget(new ModelBindingRef(new TenantId("tenant"), "binding", 1),
            "explicit-vendor-model", new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("capabilities")),
            Duration.ofSeconds(5), new ResultRef("resolution"));
    private static final Profile CONFIG = profile(TARGET, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, 4096);

    @Test void measuresFrozenCallReasoningAndToolResultAssociation() {
        var messages = List.of(new ModelMessage("assistant", ModelRole.ASSISTANT, "",
                        List.of(new ModelToolCall("call-1", "frozen_wire",
                                new TypedPayload("args.v1", "{\"query\":\"天气\"}"))),
                        Optional.empty(), Optional.of("先分析再调用")),
                new ModelMessage("result", ModelRole.TOOL, "结果", List.of(),
                        Optional.of("call-1"), Optional.empty()));
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        var measured = counter(CONFIG).measure(PROFILE, TARGET, messages);
        assertEquals(messages.stream().map(message -> (long) encoding.encodeOrdinary(message.accountingText()).size())
                .toList(), measured.contentTokenCounts());
        assertTrue(measured.contentTokenCounts().getFirst() > 0);
        assertTrue(measured.contentTokenCounts().getLast() > encoding.encodeOrdinary("结果").size());
        assertEquals(5, measured.roleTokens());
    }

    @Test void countsTopLevelToolsSeparatelyAndChargesFirstMessage() {
        var messages = List.of(new ModelMessage("system", ModelRole.SYSTEM, "指令"),
                new ModelMessage("user", ModelRole.USER, "问题"));
        var tool = new ModelTool("lookup", "opaque-handle", "查询天气",
                new TypedPayload("schema.v1", "{\"type\":\"object\"}"), true, com.skillforge.runtime.api.port.ModelPort.ModelToolKind.TOOL_CALL, false);
        var plain = counter(CONFIG).measure(PROFILE, TARGET, messages);
        var measured = counter(CONFIG).measure(PROFILE, TARGET, messages, List.of(tool));
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        String visible = "{\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\","
                + "\"description\":\"查询天气\",\"parameters\":{\"type\":\"object\"},\"strict\":true}}]}";
        assertEquals(encoding.encodeOrdinary(visible).size(), measured.toolTokens());
        assertEquals(List.of(tool), measured.tools());
        assertEquals(plain.contentTokens(), measured.contentTokens());
        assertEquals(plain.totalTokens() + measured.toolTokens(), measured.totalTokens());
        assertEquals(plain.tokensForMessage(0) + measured.toolTokens(), measured.tokensForMessage(0));
        assertEquals(plain.tokensForMessage(1), measured.tokensForMessage(1));
        assertThrows(IllegalArgumentException.class, () -> counter(profile(TARGET, "cl100k_base",
                JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, 10))
                .measure(PROFILE, TARGET, messages, List.of(tool)));
    }

    @Test void actualContentTokensRemainSeparateFromRoleFramingAndReplyPriming() {
        var messages = List.of(new ModelMessage("system", ModelRole.SYSTEM, "hello world"),
                new ModelMessage("user", ModelRole.USER, ""), new ModelMessage("assistant", ModelRole.ASSISTANT, ""));
        var measured = counter(CONFIG).measure(PROFILE, TARGET, messages);
        // cl100k_base官方普通编码示例：hello world -> [15339,1917]，不是字符比例估计。
        assertEquals(List.of(2L, 0L, 0L), measured.contentTokenCounts());
        assertEquals(2, measured.contentTokens()); assertEquals(6, measured.roleTokens());
        assertEquals(9, measured.framingTokens()); assertEquals(4, measured.replyPrimingTokens());
        assertEquals(6, measured.tokensForMessage(0)); assertEquals(5, measured.tokensForMessage(1));
        assertEquals(6, measured.tokensForMessage(2)); assertEquals(21, measured.totalTokens());
        assertEquals(CONFIG, measured.profile()); assertEquals(messages, measured.messages());
    }

    @Test void unicodeAndSpecialTokenSpellingsAreOrdinaryTextNotControlTokens() {
        var unicode = "中文😀 café e\u0301";
        var special = "hello <|endoftext|> world";
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        var measured = counter(CONFIG).measure(PROFILE, TARGET, List.of(new ModelMessage("one", ModelRole.USER, unicode),
                new ModelMessage("two", ModelRole.USER, special)));
        assertEquals((long) encoding.encodeOrdinary(unicode).size(), measured.contentTokenCounts().get(0));
        assertEquals(unicode, encoding.decode(encoding.encodeOrdinary(unicode)));
        assertEquals(8L, measured.contentTokenCounts().get(1));
        assertThrows(UnsupportedOperationException.class, () -> encoding.encode(special));
    }

    @Test void reconstructionKeepsFullTargetMessagesAndProfileWhileDefensivelyCopyingInput() {
        var messages = new ArrayList<>(List.of(new ModelMessage("one", ModelRole.USER, "hello world")));
        var first = counter(CONFIG).measure(PROFILE, TARGET, messages);
        assertEquals(first, counter(CONFIG).measure(PROFILE, TARGET, messages));
        messages.set(0, new ModelMessage("different-id", ModelRole.USER, "hello world"));
        var changed = counter(CONFIG).measure(PROFILE, TARGET, messages);
        assertEquals(first.contentTokens(), changed.contentTokens()); assertNotEquals(first, changed);
        assertEquals("one", first.messages().getFirst().messageId());
        assertThrows(UnsupportedOperationException.class, () -> first.contentTokenCounts().set(0, 999L));
        assertThrows(IllegalArgumentException.class, () -> new Measurement(CONFIG, first.messages(), first.contentTokenCounts(), 99,
                first.roleTokens(), first.framingTokens()));
    }

    @Test void missingChangedProfileOrAnyTargetDriftIsRejectedWithoutModelNameInference() {
        var message = List.of(new ModelMessage("one", ModelRole.USER, "hello world"));
        var absent = new JTokkitSessionTokenCounter(ref -> Optional.empty());
        assertThrows(IllegalStateException.class, () -> absent.measure(PROFILE, TARGET, message));
        for (var changed : List.of(profile(TARGET, "unknown", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, 4096),
                profile(TARGET, "cl100k_base", "jtokkit:future", 10, 4096))) {
            assertThrows(IllegalStateException.class, () -> counter(changed).measure(PROFILE, TARGET, message));
        }
        var otherTarget = new FrozenModelTarget(TARGET.binding(), TARGET.effectiveModelId(), TARGET.capabilities(), TARGET.timeout().plusNanos(1), TARGET.resolutionRef());
        assertThrows(IllegalStateException.class, () -> counter(CONFIG).measure(PROFILE, otherTarget, message));
        var calls = new AtomicInteger();
        var changing = new JTokkitSessionTokenCounter(ref -> calls.incrementAndGet() == 1 ? Optional.of(CONFIG) : Optional.empty());
        assertThrows(IllegalStateException.class, () -> changing.measure(PROFILE, TARGET, message));
    }

    @Test void aggregateResourceLimitsDuplicateIdsAndMalformedUnicodeStopMeasurement() {
        var constrained = counter(profile(TARGET, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 2, 4));
        assertThrows(IllegalArgumentException.class, () -> constrained.measure(PROFILE, TARGET,
                List.of(new ModelMessage("one", ModelRole.USER, "abc"), new ModelMessage("two", ModelRole.USER, "de"))));
        assertThrows(IllegalArgumentException.class, () -> constrained.measure(PROFILE, TARGET,
                List.of(new ModelMessage("one", ModelRole.USER, ""), new ModelMessage("two", ModelRole.USER, ""), new ModelMessage("three", ModelRole.USER, ""))));
        for (var messages : List.of(List.of(new ModelMessage("one", ModelRole.USER, "\ud800")),
                List.of(new ModelMessage("one", ModelRole.USER, "a"), new ModelMessage("one", ModelRole.USER, "b")))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> counter(CONFIG).measure(PROFILE, TARGET, messages));
            assertEquals("session_token_measurement_invalid", failure.getMessage());
        }
    }

    @Test void metersCumulativeMessagesSystemSnapshotsWithoutChangingMeasurementIdentity() {
        var prefix=new PromptPrefixCapabilities(CapabilitySupportStatus.SUPPORTED,ToolUpdateMode.NONE,CapabilitySupportStatus.SUPPORTED);
        var capabilities=new ModelCapabilitySnapshot(TARGET.capabilities().responseFormats(),false,false,
                TARGET.capabilities().capabilityFactRef(),prefix);
        var target = new FrozenModelTarget(TARGET.binding(), "future-account-model", capabilities,
                TARGET.timeout(), TARGET.resolutionRef());
        var registered = profile(target, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION, 10, 4096);
        var messages = List.of(new ModelMessage("core", ModelRole.SYSTEM, "原始主指令必须一直保留"),
                new ModelMessage("user", ModelRole.USER, "继续"),
                new ModelMessage("time", ModelRole.SYSTEM, "当前时间事实"));
        var provider = messagesProfile();
        var counter = new JTokkitSessionTokenCounter(ref -> Optional.of(registered), frozen -> provider);
        var plain = counter(registered).measure(PROFILE, target, messages);
        var measured = counter.measure(PROFILE, target, messages);
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        long extra = encoding.encodeOrdinary("原始主指令必须一直保留\n\n当前时间事实").size()
                - encoding.encodeOrdinary("当前时间事实").size();
        assertEquals(messages, measured.messages());
        assertEquals(plain.totalTokens() + extra, measured.totalTokens());
        assertEquals(extra, measured.variantTokenCounts().getLast());
        assertEquals(plain.roleTokens(), measured.roleTokens());
        assertEquals(plain.framingTokens(), measured.framingTokens());
        // 同一任意型号若没有冻结in-history能力，只计完整leading快照，不能按名字恢复历史SYSTEM优化。
        for(var status:List.of(CapabilitySupportStatus.UNSUPPORTED,CapabilitySupportStatus.UNVERIFIED)) {
            var leadingCaps=new ModelCapabilitySnapshot(TARGET.capabilities().responseFormats(),false,false,
                    TARGET.capabilities().capabilityFactRef(),new PromptPrefixCapabilities(status,ToolUpdateMode.NONE,CapabilitySupportStatus.SUPPORTED));
            var leadingTarget=new FrozenModelTarget(target.binding(),target.effectiveModelId(),leadingCaps,target.timeout(),target.resolutionRef());
            var leadingProfile=profile(leadingTarget,"cl100k_base",JTokkitSessionTokenCounter.TOKENIZER_VERSION,10,4096);
            var leadingPlain=counter(leadingProfile).measure(PROFILE,leadingTarget,messages);
            var leadingMeasured=new JTokkitSessionTokenCounter(ref->Optional.of(leadingProfile),frozen->provider).measure(PROFILE,leadingTarget,messages);
            long leadingExtra=Math.max(0,encoding.encodeOrdinary("原始主指令必须一直保留\n\n当前时间事实").size()
                    -encoding.encodeOrdinary("原始主指令必须一直保留").size()-encoding.encodeOrdinary("当前时间事实").size());
            assertEquals(leadingPlain.totalTokens()+leadingExtra,leadingMeasured.totalTokens());
            assertEquals(messages,leadingMeasured.messages());
        }

        var chat = new OpenAiModelPort.ProviderProfile("deepseek", "deepseek-api", "2026-09-13",
                com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme.BEARER_API_KEY,
                "openai-compatible-chat-v1");
        assertEquals(plain, new JTokkitSessionTokenCounter(ref -> Optional.of(registered), frozen -> chat)
                .measure(PROFILE, target, messages), "相同能力与消息按冻结协议选择投影，不按型号猜测");
        var unsupported = new OpenAiModelPort.ProviderProfile("other-provider", provider.serviceId(),
                provider.profileVersion(), provider.protocol(), provider.authenticationScheme(), provider.dialectVersion());
        assertThrows(IllegalStateException.class, () -> new JTokkitSessionTokenCounter(
                ref -> Optional.of(registered), frozen -> unsupported).measure(PROFILE, target, messages));
        var units = profile(target, "cl100k_base", JTokkitSessionTokenCounter.TOKENIZER_VERSION,
                10, messages.stream().mapToInt(message -> message.accountingText().length()).sum());
        assertEquals(WireCapacityExceeded.Resource.CONTENT_CODE_UNITS,
                assertThrows(WireCapacityExceeded.class, () -> new JTokkitSessionTokenCounter(
                        ref -> Optional.of(units), frozen -> provider).measure(PROFILE, target, messages)).resource());
    }

    @Test void rejectsChangedFrozenProviderProfileAfterMeasurement() {
        var reads = new AtomicInteger();
        var original = messagesProfile();
        var changed = new OpenAiModelPort.ProviderProfile(original.providerId(), original.serviceId(),
                "future", original.protocol(), original.authenticationScheme(), original.dialectVersion());
        var counter = new JTokkitSessionTokenCounter(ref -> Optional.of(CONFIG),
                target -> reads.incrementAndGet() == 1 ? original : changed);
        assertThrows(IllegalStateException.class, () -> counter.measure(PROFILE, TARGET,
                List.of(new ModelMessage("user", ModelRole.USER, "hello"))));
    }

    private static OpenAiModelPort.ProviderProfile messagesProfile() {
        return new OpenAiModelPort.ProviderProfile("deepseek", "deepseek-api", "2026-09-13",
                com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol.ANTHROPIC_MESSAGES,
                com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme.ANTHROPIC_API_KEY,
                "deepseek-messages-v1");
    }

    private static Profile profile(FrozenModelTarget target, String encoding, String tokenizerVersion, int messages, int units) {
        return new Profile(PROFILE, "chat-profile-v1", tokenizerVersion, encoding, target, new RoleCosts(1, 2, 3), 3, 4, messages, units);
    }
    private static JTokkitSessionTokenCounter counter(Profile profile) {
        return new JTokkitSessionTokenCounter(ref -> ref.equals(profile.reference()) ? Optional.of(profile) : Optional.empty());
    }
}
