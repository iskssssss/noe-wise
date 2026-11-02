package com.skillforge.runtime.adapter.model;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.adapter.model.deepseek.messages.DeepSeekMessagesProjection;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** 使用钉扎JTokkit字典实际编码普通内容；消息协议开销仅采用独立登记的显式配置。 */
public final class JTokkitSessionTokenCounter implements SessionTokenCounter {
    public static final String TOKENIZER_VERSION = "jtokkit:1.1.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Function<ResultRef, Optional<Profile>> profiles;
    private final Function<FrozenModelTarget, OpenAiModelPort.ProviderProfile> providerProfiles;
    private final EncodingRegistry encodings = Encodings.newLazyEncodingRegistry();
    private static final long CACHE_CHARACTERS = 2L * 1024 * 1024;
    private static final int CACHE_ENTRIES = 2048;
    private final java.util.LinkedHashMap<CacheKey, CacheEntry> contentCounts = new java.util.LinkedHashMap<>(32, .75f, true);
    private long retainedCharacters;
    private long encodingsPerformed;
    private long toolProjectionsPerformed;

    private sealed interface CacheKey permits TextKey, ToolKey { }
    private record TextKey(EncodingType encoding, String text) implements CacheKey { }
    private record ToolKey(EncodingType encoding, List<ModelTool> tools) implements CacheKey { }
    private record CacheEntry(long tokens, long retainedCharacters) { }

    /** 只缓存纯文本编码结果；配置、目标绑定和准入检查每次仍执行。 */
    private long count(EncodingType type, String text) {
        var key = new TextKey(type, text);
        synchronized (contentCounts) {
            var cached = contentCounts.get(key);
            if (cached != null) return cached.tokens();
        }
        long result = encodings.getEncoding(type).encodeOrdinary(text).size();
        remember(key, result, text.length());
        return result;
    }

    /** 完整有序冻结工具集命中后只复用纯成本；不再保留第二份投影正文文本项。 */
    private long countTools(EncodingType type, List<ModelTool> frozenTools) {
        var key = new ToolKey(type, frozenTools);
        synchronized (contentCounts) {
            var cached = contentCounts.get(key);
            if (cached != null) return cached.tokens();
        }
        long result = encodings.getEncoding(type).encodeOrdinary(toolDefinitions(frozenTools)).size();
        long characters = 0;
        for (var tool : frozenTools) characters = Math.addExact(characters, (long) tool.wireName().length()
                + tool.toolHandle().length() + tool.description().length()
                + tool.inputSchema().schemaId().length() + tool.inputSchema().canonicalJson().length());
        remember(key, result, characters);
        return result;
    }

    /** 记录真实编码次数，并让文本与工具key在同一LRU字符和项数预算内竞争。 */
    private void remember(CacheKey key, long tokens, long characters) {
        synchronized (contentCounts) {
            encodingsPerformed++;
            if (characters > CACHE_CHARACTERS || contentCounts.containsKey(key)) return;
            while (!contentCounts.isEmpty() && (contentCounts.size() >= CACHE_ENTRIES
                    || retainedCharacters + characters > CACHE_CHARACTERS)) {
                var eldest = contentCounts.firstEntry();
                retainedCharacters -= eldest.getValue().retainedCharacters();
                contentCounts.remove(eldest.getKey());
            }
            contentCounts.put(key, new CacheEntry(tokens, characters));
            retainedCharacters += characters;
        }
    }

    long encodingsPerformed() { synchronized (contentCounts) { return encodingsPerformed; } }
    long toolProjectionsPerformed() { synchronized (contentCounts) { return toolProjectionsPerformed; } }

    /** profiles由受信装配提供独立原配置；调用者只传ref，不以候选Profile自证登记。 */
    public JTokkitSessionTokenCounter(Function<ResultRef, Optional<Profile>> profiles) {
        this(profiles, null);
    }

    /** 供应商协议从精确冻结绑定读取；无 resolver 的桌面纯 Chat 入口不进行协议转换。 */
    public JTokkitSessionTokenCounter(Function<ResultRef, Optional<Profile>> profiles,
            Function<FrozenModelTarget, OpenAiModelPort.ProviderProfile> providerProfiles) {
        this.profiles = Objects.requireNonNull(profiles);
        this.providerProfiles = providerProfiles;
    }

    @Override public Measurement measure(ResultRef profileRef, FrozenModelTarget target,
            List<ModelMessage> messages, List<ModelTool> tools) {
        Objects.requireNonNull(profileRef); Objects.requireNonNull(target);
        var profile = profiles.apply(profileRef).orElseThrow(JTokkitSessionTokenCounter::rejected);
        if (!profile.reference().equals(profileRef) || !profile.target().equals(target) || !TOKENIZER_VERSION.equals(profile.tokenizerVersion())) throw rejected();
        var encodingType = EncodingType.fromName(profile.encoding()).orElseThrow(JTokkitSessionTokenCounter::rejected);
        var providerProfile = providerProfiles == null ? null : Objects.requireNonNull(providerProfiles.apply(target));
        var frozenMessages = List.copyOf(messages);
        SessionTokenCounter.requireMessages(profile, frozenMessages);
        var frozenTools = List.copyOf(tools);
        SessionTokenCounter.requireTools(profile, frozenMessages, frozenTools);
        var counts = new ArrayList<Long>(frozenMessages.size()); long content = 0, roles = 0;
        var imageCounts = new ArrayList<Long>(frozenMessages.size());
        var variantCounts = new ArrayList<Long>(frozenMessages.size());
        var imagePolicy = target.capabilities().imagePolicy();
        // Messages/Responses 原生承载工具图片；只有 Chat Codec 派生额外的 user 桥接消息。
        boolean bridgeToolImages = providerProfile == null || providerProfile.protocol() == ModelProtocol.OPENAI_CHAT_COMPLETIONS;
        int images = 0, bridgeMessages = 0; long imageBytes = 0, bridgeUnits = 0;
        for (var message : frozenMessages) {
            long count = count(encodingType, message.accountingText());
            long imageTokens = 0, variantTokens = 0; boolean hasImages = false;
            for (var part : message.contents()) if (part instanceof ModelContent.Image image) {
                hasImages = true;
                if (!image.media().owner().tenantId().equals(target.binding().tenantId())
                        || message.role() == ModelRole.TOOL && !imagePolicy.toolImages()) throw rejected();
                images = Math.addExact(images, 1); imageBytes = Math.addExact(imageBytes, image.media().byteLength());
                imageTokens = Math.addExact(imageTokens, ModelImageAccounting.tokens(image, imagePolicy));
            }
            if (hasImages && message.role() == ModelRole.TOOL && bridgeToolImages) {
                String bridgeText = ModelImageAccounting.toolImageAnchor(message.toolCallId().orElseThrow()) + message.textContent();
                bridgeMessages = Math.addExact(bridgeMessages, 1);
                bridgeUnits = Math.addExact(bridgeUnits, bridgeText.length());
                variantTokens = Math.addExact(count(encodingType, bridgeText),
                        Math.addExact((long) profile.roleCosts().user(), profile.perMessageFramingTokens()));
            }
            count = Math.addExact(count, Math.addExact(imageTokens, variantTokens));
            imageCounts.add(imageTokens); variantCounts.add(variantTokens);
            counts.add(count); content = Math.addExact(content, count);
            roles = Math.addExact(roles, profile.roleCosts().forRole(message.role()));
        }
        if (images > imagePolicy.maxImages() || images > 0 && imageBytes > imagePolicy.maxRequestBytes()) throw rejected();
        if (Math.addExact(frozenMessages.size(), bridgeMessages) > profile.maxMessages())
            throw new WireCapacityExceeded(WireCapacityExceeded.Resource.MESSAGES);
        long textUnits = frozenMessages.stream().mapToLong(message -> message.accountingText().length()).sum();
        for (var tool : frozenTools) textUnits = Math.addExact(textUnits, tool.accountingText().length());
        if (Math.addExact(textUnits, bridgeUnits) > profile.maxContentCodeUnits())
            throw new WireCapacityExceeded(WireCapacityExceeded.Resource.CONTENT_CODE_UNITS);
        if (providerProfile != null && providerProfile.protocol() == ModelProtocol.ANTHROPIC_MESSAGES) {
            var invocationProfile = new ModelProvider.InvocationProfile(providerProfile.serviceId(),
                    providerProfile.profileVersion(), providerProfile.protocol(), providerProfile.authenticationScheme(),
                    providerProfile.dialectVersion());
            if (!DeepSeekMessagesProjection.supports(providerProfile.providerId(), invocationProfile)) throw rejected();
            var extra = messagesProjection(encodingType, target, frozenMessages, profile);
            if (Math.addExact(extra.codeUnits(), Math.addExact(textUnits, bridgeUnits)) > profile.maxContentCodeUnits())
                throw new WireCapacityExceeded(WireCapacityExceeded.Resource.CONTENT_CODE_UNITS);
            if (Math.addExact(extra.messageCount(), Math.addExact(frozenMessages.size(), bridgeMessages)) > profile.maxMessages())
                throw new WireCapacityExceeded(WireCapacityExceeded.Resource.MESSAGES);
            if (extra.tokens() > 0) {
                int index = 0;
                for (int i = 0; i < frozenMessages.size(); i++)
                    if (frozenMessages.get(i).role() == ModelRole.SYSTEM) index = i;
                counts.set(index, Math.addExact(counts.get(index), extra.tokens()));
                variantCounts.set(index, Math.addExact(variantCounts.get(index), extra.tokens()));
                content = Math.addExact(content, extra.tokens());
            }
        }
        long toolTokens = frozenTools.isEmpty() ? 0
                : countTools(encodingType, frozenTools);
        // 原配置在计量期间不可换版本；变化时不返回失去来源绑定的旧measurement。
        if (!profiles.apply(profileRef).equals(Optional.of(profile))
                || providerProfiles != null && !Objects.equals(providerProfile, providerProfiles.apply(target))) throw rejected();
        return new Measurement(profile, frozenMessages, counts, content, roles,
                Math.multiplyExact((long) frozenMessages.size(), profile.perMessageFramingTokens()),
                frozenTools, toolTokens, imageCounts, variantCounts);
    }

    private record ProjectionCost(long tokens, long codeUnits, int messageCount) { }

    /** 原消息保持计量身份，累积快照超过原 SYSTEM 的成本归入原消息的协议变体。 */
    private ProjectionCost messagesProjection(EncodingType encoding, FrozenModelTarget target,
            List<ModelMessage> messages, Profile profile) {
        var input = messages.stream().map(message -> new ModelProvider.Message(
                ModelProvider.Role.valueOf(message.role().name()), message.contents(),
                message.toolCallId().orElse(null), message.toolCalls().stream().map(call ->
                        new ModelProvider.ToolCall(call.callId(), call.wireName(), call.arguments().canonicalJson())).toList(),
                message.reasoningContent().orElse(null), message.protocolState())).toList();
        var projection = DeepSeekMessagesProjection.project(input, target.capabilities().prefixCapabilities());
        long originalTokens = 0, originalUnits = 0, projectedTokens = 0, projectedUnits = 0;
        int originalSystems = 0, projectedSystems = 0;
        for (var message : messages) if (message.role() == ModelRole.SYSTEM) {
            originalTokens = Math.addExact(originalTokens, count(encoding, message.accountingText()));
            originalUnits = Math.addExact(originalUnits, message.accountingText().length());
            originalSystems++;
        }
        if (projection.leadingSystem() != null) {
            projectedTokens = count(encoding, projection.leadingSystem());
            projectedUnits = projection.leadingSystem().length();
            projectedSystems++;
        }
        for (var message : projection.messages()) if (message.role() == ModelProvider.Role.SYSTEM) {
            projectedTokens = Math.addExact(projectedTokens, count(encoding, message.textContent()));
            projectedUnits = Math.addExact(projectedUnits, message.textContent().length());
            projectedSystems++;
        }
        int additionalMessages = Math.max(0, projectedSystems - originalSystems);
        long additionalFraming = Math.multiplyExact((long) additionalMessages,
                Math.addExact((long) profile.roleCosts().system(), profile.perMessageFramingTokens()));
        return new ProjectionCost(Math.addExact(Math.max(0, projectedTokens - originalTokens), additionalFraming),
                Math.max(0, projectedUnits - originalUnits), additionalMessages);
    }

    /** 顶层工具未嵌入 system 正文，单独编码包含 function 包装的完整可见定义。 */
    private String toolDefinitions(List<ModelTool> tools) {
        synchronized (contentCounts) { toolProjectionsPerformed++; }
        var root = JSON.createObjectNode();
        var definitions = root.putArray("tools");
        try {
            for (var tool : tools) {
                var function = definitions.addObject().put("type", "function").putObject("function");
                function.put("name", tool.wireName()).put("description", tool.description());
                var schema = OpenAiModelProtocol.parse(tool.inputSchema().canonicalJson());
                if (!schema.isObject()) throw rejected();
                function.set("parameters", schema);
                if (tool.strict()) function.put("strict", true);
            }
            return JSON.writeValueAsString(root);
        } catch (java.io.IOException invalid) {
            throw rejected();
        }
    }
    private static IllegalStateException rejected() { return new IllegalStateException("session_tokenizer_profile_rejected"); }
}
