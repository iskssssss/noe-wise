package com.skillforge.core.agent.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Temporal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** v2 事实正文实体；固定字段由 record 唯一定义，JSON 仅用于开放 value 及外部载荷边界。 */
public final class MemoryContent {
    private MemoryContent() { }

    /** 显式写入不推断时间：偏好稳定，情节性质未知。 */
    public static Content explicitContent(JsonNode value, String type) {
        if (type == null) throw invalid();
        String kind = switch (type) {
            case "preference" -> "stable";
            case "episode" -> "unknown";
            default -> throw invalid();
        };
        return validate(new Content(value, List.of(), new Temporal(kind, null, null), null));
    }

    /** 候选只贡献正文语义；正式修订目标由宿主添加，不来自模型载荷。 */
    public static Content interpretedContent(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return validate(new Content(candidate.object(), candidate.conditions(), candidate.temporal(), null));
    }

    /** 严格外部解码，不接受重复键、尾随数据、未知字段或标量强转。 */
    public static Content decode(String encoded) {
        budget(encoded);
        return validate(MemoryJsonCodec.decode(encoded, Content.class, "memory_content_invalid"));
    }

    public static Content decode(JsonNode encoded) {
        if (encoded == null) throw invalid();
        return decode(encoded.toString());
    }

    public static Content validate(Content content) {
        if (content == null) throw invalid();
        budget(MemoryJsonCodec.encode(content));
        return content;
    }

    public static String encode(Content content) { return MemoryJsonCodec.encode(validate(content)); }

    /** 无修订提议时省略该字段，其余必需的 null 时间字段仍保留。 */
    public static Content withoutRevisionProposal(Content content) {
        validate(content);
        return new Content(content.value(), content.conditions(), content.temporal(), null);
    }

    public static Content withRevisionProposal(Content content, String factId, long expectedRevision) {
        validate(content);
        return validate(new Content(content.value(), content.conditions(), content.temporal(),
                new RevisionProposal(factId, expectedRevision)));
    }

    /** 条件和时间性质作为实体供治理比较，不重新解释自然语言。 */
    public static Semantics semantics(Content content) {
        validate(content);
        return new Semantics(content.conditions(), content.temporal());
    }

    /** 检索正文保留值与限定条件，排除仅用于审核的修订目标。 */
    public static String contentText(Content content) { return encode(withoutRevisionProposal(content)); }

    public static Optional<RevisionProposal> revisionProposal(Content content) {
        return Optional.ofNullable(validate(content).revisionProposal());
    }

    /** JsonNode 是对外适配入口，业务消费者使用上面的 Content 版本。 */
    public static ObjectNode explicit(JsonNode value, String type) { return MemoryJsonCodec.node(explicitContent(value, type)); }
    public static ObjectNode interpreted(Candidate candidate) { return MemoryJsonCodec.node(interpretedContent(candidate)); }
    public static ObjectNode validate(JsonNode content) { return MemoryJsonCodec.node(decode(content)); }
    public static ObjectNode withRevisionProposal(JsonNode content, String factId, long expectedRevision) {
        return MemoryJsonCodec.node(withRevisionProposal(decode(content), factId, expectedRevision));
    }
    public static Semantics semantics(JsonNode content) { return semantics(decode(content)); }
    public static String contentText(JsonNode content) { return contentText(decode(content)); }
    public static Optional<RevisionProposal> revisionProposal(JsonNode content) { return revisionProposal(decode(content)); }

    /** 固定正文结构；开放 value 双向深拷贝，条件不可变，只有 revisionProposal 可缺省。 */
    public record Content(
            @JsonProperty(required = true) JsonNode value,
            @JsonProperty(required = true) List<String> conditions,
            @JsonProperty(required = true) Temporal temporal,
            @JsonInclude(JsonInclude.Include.NON_NULL) RevisionProposal revisionProposal) {
        public Content {
            value = MemoryExtractionCandidates.value(value);
            conditions = MemoryExtractionCandidates.strings(conditions);
            if (temporal == null) throw invalid();
        }
        @Override public JsonNode value() { return value.deepCopy(); }
    }

    /** 精确事实和原版本由宿主绑定，仍由 Runtime 授权并执行 CAS。 */
    public record RevisionProposal(
            @JsonProperty(required = true) String factId,
            @JsonProperty(required = true) long expectedRevision) {
        public RevisionProposal {
            if (factId == null || factId.isBlank() || expectedRevision < 1) throw invalid();
        }
    }

    /** 审核纯值视图，不携带来源审计或模型调用状态。 */
    public record Semantics(List<String> conditions, Temporal temporal) {
        public Semantics {
            conditions = MemoryExtractionCandidates.strings(conditions);
            if (temporal == null) throw invalid();
        }
    }

    private static void budget(String encoded) {
        if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > MemoryExtractionCandidates.MAX_OBJECT_BYTES)
            throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("memory_content_invalid"); }
}
