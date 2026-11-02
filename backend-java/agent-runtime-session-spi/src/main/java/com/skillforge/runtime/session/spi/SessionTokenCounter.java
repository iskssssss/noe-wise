package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 准备阶段的指定tokenizer计量；内容编码与配置开销分离，不代表Provider实际usage。 */
public interface SessionTokenCounter {
    /** 派生线上消息的资源容量压力，允许准备层卸载历史图片后重新严格计量。 */
    final class WireCapacityExceeded extends IllegalStateException {
        public enum Resource { MESSAGES, CONTENT_CODE_UNITS }
        private final Resource resource;
        public WireCapacityExceeded(Resource resource) {
            super("session_tokenizer_wire_capacity_exceeded");
            this.resource = Objects.requireNonNull(resource);
        }
        public Resource resource() { return resource; }
    }
    Measurement measure(ResultRef profileRef, FrozenModelTarget target, List<ModelMessage> messages, List<ModelTool> tools);

    default Measurement measure(ResultRef profileRef, FrozenModelTarget target, List<ModelMessage> messages) {
        return measure(profileRef, target, messages, List.of());
    }

    /** 各角色的显式开销；相同消息 framing 的文本协议可复用用户侧开销。 */
    record RoleCosts(int system, int user, int assistant, int tool) {
        public RoleCosts(int system, int user, int assistant) { this(system, user, assistant, user); }
        public RoleCosts { if (system < 0 || user < 0 || assistant < 0 || tool < 0) throw invalid(); }
        public int forRole(ModelRole role) {
            return switch (Objects.requireNonNull(role)) { case SYSTEM -> system; case USER -> user; case ASSISTANT -> assistant; case TOOL -> tool; };
        }
    }

    /** 完整原目标和版本化配置必须独立登记；maxContentCodeUnits约束所有消息内容的总UTF-16长度。 */
    record Profile(ResultRef reference, String version, String tokenizerVersion, String encoding,
                   FrozenModelTarget target, RoleCosts roleCosts, int perMessageFramingTokens,
                   int replyPrimingTokens, int maxMessages, int maxContentCodeUnits) {
        public Profile {
            Objects.requireNonNull(reference); text(version); text(tokenizerVersion); text(encoding);
            Objects.requireNonNull(target); Objects.requireNonNull(roleCosts);
            if (perMessageFramingTokens < 0 || replyPrimingTokens < 0 || maxMessages <= 0 || maxContentCodeUnits <= 0) throw invalid();
        }
        @Override public String toString() { return "SessionTokenizerProfile[REDACTED]"; }
    }

    /** 每条普通文本token与原消息顺序绑定；角色、framing和reply priming均独立计量。 */
    record Measurement(Profile profile, List<ModelMessage> messages, List<Long> contentTokenCounts,
                       long contentTokens, long roleTokens, long framingTokens, List<ModelTool> tools, long toolTokens,
                       List<Long> imageTokenCounts, List<Long> variantTokenCounts) {
        public Measurement(Profile profile, List<ModelMessage> messages, List<Long> contentTokenCounts,
                long contentTokens, long roleTokens, long framingTokens, List<ModelTool> tools, long toolTokens) {
            this(profile, messages, contentTokenCounts, contentTokens, roleTokens, framingTokens, tools, toolTokens,
                    java.util.Collections.nCopies(messages.size(), 0L), java.util.Collections.nCopies(messages.size(), 0L));
        }
        public Measurement(Profile profile, List<ModelMessage> messages, List<Long> contentTokenCounts,
                           long contentTokens, long roleTokens, long framingTokens) {
            this(profile, messages, contentTokenCounts, contentTokens, roleTokens, framingTokens, List.of(), 0);
        }
        public Measurement {
            Objects.requireNonNull(profile); messages = List.copyOf(messages); contentTokenCounts = List.copyOf(contentTokenCounts);
            tools = List.copyOf(tools);
            imageTokenCounts = List.copyOf(imageTokenCounts); variantTokenCounts = List.copyOf(variantTokenCounts);
            requireMessages(profile, messages);
            requireTools(profile, messages, tools);
            if (toolTokens < 0 || tools.isEmpty() && toolTokens != 0 || !tools.isEmpty() && toolTokens == 0) throw invalid();
            if (contentTokenCounts.size() != messages.size() || imageTokenCounts.size() != messages.size()
                    || variantTokenCounts.size() != messages.size()) throw invalid();
            long content = 0, roles = 0;
            for (int i = 0; i < messages.size(); i++) {
                long tokens = contentTokenCounts.get(i);
                if (tokens < 0) throw invalid();
                long images = imageTokenCounts.get(i), variant = variantTokenCounts.get(i);
                if (images < 0 || variant < 0 || Math.addExact(images, variant) > tokens) throw invalid();
                content = Math.addExact(content, tokens);
                roles = Math.addExact(roles, profile.roleCosts().forRole(messages.get(i).role()));
            }
            long framing = Math.multiplyExact((long) messages.size(), profile.perMessageFramingTokens());
            if (content != contentTokens || roles != roleTokens || framing != framingTokens) throw invalid();
            Math.addExact(toolTokens, Math.addExact(Math.addExact(content, roles), Math.addExact(framing, profile.replyPrimingTokens())));
        }
        /** 首条消息承担顶层工具定义成本；各条均含role/framing，不含reply priming。 */
        public long tokensForMessage(int index) {
            return Math.addExact(index == 0 ? toolTokens : 0, Math.addExact(contentTokenCounts.get(index), Math.addExact((long) profile.roleCosts().forRole(messages.get(index).role()), profile.perMessageFramingTokens())));
        }
        public long replyPrimingTokens() { return profile.replyPrimingTokens(); }
        public long totalTokens() { return Math.addExact(toolTokens, Math.addExact(Math.addExact(contentTokens, roleTokens), Math.addExact(framingTokens, replyPrimingTokens()))); }
        @Override public String toString() { return "SessionTokenMeasurement[REDACTED]"; }
    }
    /** 工具定义与消息共用总输入字符限额，目录不能通过移出消息正文绕开预算。 */
    static void requireTools(Profile profile, List<ModelMessage> messages, List<ModelTool> tools) {
        long units = messages.stream().mapToLong(message -> message.accountingText().length()).sum();
        var names = new HashSet<String>();
        for (var tool : tools) {
            if (!names.add(tool.wireName())) throw invalid();
            String value = tool.accountingText();
            units = Math.addExact(units, value.length());
            if (units > profile.maxContentCodeUnits()) throw invalid();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
                } else if (Character.isLowSurrogate(c)) throw invalid();
            }
        }
    }

    /** tokenizer运行前即可核验资源与Unicode，拒绝替换非法代理项后计数。 */
    static void requireMessages(Profile profile, List<ModelMessage> messages) {
        Objects.requireNonNull(profile); Objects.requireNonNull(messages);
        if (messages.size() > profile.maxMessages()) throw invalid();
        long units = 0; var ids = new HashSet<String>();
        for (var message : messages) {
            Objects.requireNonNull(message);
            if (!ids.add(message.messageId())) throw invalid();
            units = Math.addExact(units, message.accountingText().length());
            if (units > profile.maxContentCodeUnits()) throw invalid();
            var content = message.accountingText();
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == content.length() || !Character.isLowSurrogate(content.charAt(i))) throw invalid();
                } else if (Character.isLowSurrogate(c)) throw invalid();
            }
        }
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("session_token_measurement_invalid"); }
}
