package com.skillforge.core.agent.systemagent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.agent.memory.MemoryJsonCodec;
import com.skillforge.core.agent.memory.MemoryContent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 提取生产与调试共用类型化候选契约；JSON 仅在模型协议与开放结构值边界存在。 */
public final class MemoryExtractionCandidates {
    /** 与冻结输入及模型输出一起升级，不接受旧版宽松格式。 */
    public static final int SCHEMA_VERSION = 2;
    /** 群输入明确携带作者与可描述对象，不改变个人 v2 协议。 */
    public static final int GROUP_SCHEMA_VERSION = 3;
    /** 单次输出的原始候选上限，重复候选也占解析预算。 */
    public static final int MAX_CANDIDATES = 32;
    /** 单条结构值及正式正文的 UTF-8 大小上限。 */
    public static final int MAX_OBJECT_BYTES = 16_384;
    /** 规范化去重后候选的累计写入预算。 */
    public static final int MAX_WRITE_BYTES = 131_072;
    /** 解析前的输入和输出总 UTF-8 预算。 */
    public static final int MAX_OUTPUT_BYTES = 262_144;
    /** 结构值递归上限，不解释 JSON 键的自然语言含义。 */
    public static final int MAX_JSON_DEPTH = 16;
    private static final int MAX_SOURCES = 32;
    private static final int MAX_EXISTING_FACTS = 64;
    private static final int MAX_EVIDENCE_ITEMS = 32;
    private static final int MAX_LIST_ITEMS = 32;
    private static final Set<String> TYPES = Set.of("episode", "preference");
    private static final Set<String> SCOPES = Set.of("user", "user_agent", "group", "group_member");
    private static final Set<String> SIGNALS = Set.of("observed", "explicit_save", "repeated_confirmed");
    private static final Set<String> TEMPORAL_KINDS = Set.of("stable", "task", "interval", "unknown");
    private static final Set<String> RELATION_KINDS = Set.of("new", "similar", "possible_conflict", "correction");

    public MemoryExtractionCandidates() { }

    /** 保留宿主注入入口；协议编解码始终使用独立固定 codec，不继承应用的命名或宽松配置。 */
    public MemoryExtractionCandidates(ObjectMapper json) { Objects.requireNonNull(json, "json"); }

    /** 模型输出立即转换为 record，再以可信来源和别名校验，非法候选拒绝整份输出。 */
    public List<Candidate> parse(String encoded, ExtractionInput input) {
        validateInput(input);
        budget(encoded, MAX_OUTPUT_BYTES, "memory_extraction_output_budget_exceeded");
        ExtractionOutput output = MemoryJsonCodec.decodeModelOutput(encoded);
        if (output.schemaVersion() != input.schemaVersion()) throw invalid("memory_extraction_schema_invalid");
        var sources = new LinkedHashMap<String, String>();
        input.sources().forEach(source -> sources.put(source.id(), source.text()));
        var targets = new HashSet<String>();
        input.existingFacts().forEach(fact -> targets.add(fact.id()));
        var parsed = new LinkedHashMap<String, Candidate>();
        int bytes = 0;
        for (Candidate candidate : output.candidates()) {
            requireSubject(input.userId(), input.group(), candidate.scope(), candidate.subject());
            for (Evidence evidence : candidate.evidence()) {
                if (!sources.containsKey(evidence.sourceId()) || !sources.get(evidence.sourceId()).contains(evidence.quote()))
                    throw invalid("memory_extraction_evidence_invalid");
            }
            Relation relation = candidate.relation();
            if (relation.target() != null && !targets.contains(relation.target())) throw invalid("memory_extraction_relation_invalid");
            String identity = encodeCandidate(candidate);
            if (parsed.putIfAbsent(identity, candidate) == null) {
                bytes += identity.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_WRITE_BYTES) throw invalid("memory_extraction_write_budget_exceeded");
            }
        }
        return List.copyOf(parsed.values());
    }

    /** 外部树边界委托同一 record 契约，不在业务中读取固定 JSON 字段。 */
    public List<Candidate> parse(String encoded, JsonNode input) { return parse(encoded, decodeInput(input)); }

    public ExtractionInput typedGroupInput(long authorId, GroupContext group, List<Source> sources, List<ExistingFact> existingFacts) {
        return validateInput(new ExtractionInput(GROUP_SCHEMA_VERSION, authorId, sources, existingFacts, Objects.requireNonNull(group)));
    }

    public ExtractionInput typedInput(long userId, List<Source> sources, List<ExistingFact> existingFacts) {
        return validateInput(new ExtractionInput(SCHEMA_VERSION, userId, sources, existingFacts));
    }

    /** 从正式 wire 恢复类型化输入；不读取 latest 覆盖已冻结来源。 */
    public ExtractionInput decodeInput(String encoded) {
        budget(encoded, MAX_OUTPUT_BYTES, "memory_extraction_input_budget_exceeded");
        return validateInput(MemoryJsonCodec.decode(encoded, ExtractionInput.class, "memory_extraction_input_invalid"));
    }

    public ExtractionInput decodeInput(JsonNode node) {
        return validateInput(MemoryJsonCodec.decode(node, ExtractionInput.class, "memory_extraction_input_invalid"));
    }

    public String encodeInput(ExtractionInput input) { return MemoryJsonCodec.encode(validateInput(input)); }

    /** 已持久单条候选恢复时仍核对本次冻结来源，不把候选 JSON 当成自证来源。 */
    public Candidate decodeCandidate(String encoded, ExtractionInput input) {
        budget(encoded, MAX_OUTPUT_BYTES, "memory_extraction_output_budget_exceeded");
        Candidate candidate = MemoryJsonCodec.decode(encoded, Candidate.class, "memory_extraction_candidate_invalid");
        String output = MemoryJsonCodec.encode(new ExtractionOutput(input.schemaVersion(), List.of(candidate)));
        return parse(output, input).getFirst();
    }

    public String encodeCandidate(Candidate candidate) { return MemoryJsonCodec.encode(candidate); }

    /** record 构造拥有完整不变量；总预算在输入边界统一校验。 */
    public ExtractionInput validateInput(ExtractionInput input) {
        if (input == null) throw invalid("memory_extraction_input_invalid");
        budget(MemoryJsonCodec.encode(input), MAX_OUTPUT_BYTES, "memory_extraction_input_budget_exceeded");
        return input;
    }

    /** 仅供 JSON 适配边界输出；正式消费者使用 typedInput/encodeInput。 */
    public JsonNode input(long userId, List<Source> sources, List<ExistingFact> existingFacts) {
        return MemoryJsonCodec.node(typedInput(userId, sources, existingFacts));
    }

    public JsonNode validateInput(JsonNode input) { return MemoryJsonCodec.node(decodeInput(input)); }

    public ObjectNode toNode(Candidate candidate) { return MemoryJsonCodec.node(candidate); }

    /** 字段名即 camelCase 协议定义；集合不可变，别名只在本次输入中有意义。 */
    public record ExtractionInput(
            @JsonProperty(required = true) int schemaVersion,
            @JsonProperty(required = true) long userId,
            @JsonProperty(required = true) List<Source> sources,
            @JsonProperty(required = true) List<ExistingFact> existingFacts,
            @JsonInclude(JsonInclude.Include.NON_NULL) GroupContext group) {
        public ExtractionInput(int schemaVersion, long userId, List<Source> sources, List<ExistingFact> existingFacts) {
            this(schemaVersion, userId, sources, existingFacts, null);
        }
        public ExtractionInput {
            if (group == null ? schemaVersion != SCHEMA_VERSION : schemaVersion != GROUP_SCHEMA_VERSION)
                throw invalid("memory_extraction_schema_invalid");
            if (userId < 1) throw invalid("memory_extraction_subject_invalid");
            sources = immutable(sources, "memory_extraction_input_invalid");
            existingFacts = immutable(existingFacts, "memory_extraction_input_invalid");
            if (sources.isEmpty() || sources.size() > MAX_SOURCES || existingFacts.size() > MAX_EXISTING_FACTS)
                throw invalid("memory_extraction_input_invalid");
            var sourceIds = new HashSet<String>();
            for (Source source : sources) if (!sourceIds.add(source.id())) throw invalid("memory_extraction_source_invalid");
            var factIds = new HashSet<String>();
            for (ExistingFact fact : existingFacts) {
                if (!factIds.add(fact.id())) throw invalid("memory_extraction_fact_invalid");
                requireSubject(userId, group, fact.scope(), fact.subject());
            }
        }
    }

    /** 正式群来源冻结的描述对象白名单；成员身份由宿主解析，模型不得生成新身份。 */
    public record GroupContext(@JsonProperty(required = true) String groupBindingId,
            @JsonProperty(required = true) List<Long> subjectEndUserIds) {
        public GroupContext {
            text(groupBindingId, "memory_extraction_group_invalid");
            subjectEndUserIds = immutable(subjectEndUserIds, "memory_extraction_subject_invalid");
            if (subjectEndUserIds.stream().anyMatch(id -> id < 1)
                    || new HashSet<>(subjectEndUserIds).size() != subjectEndUserIds.size())
                throw invalid("memory_extraction_subject_invalid");
        }
    }

    private static void requireSubject(long authorId, GroupContext group, String scope, String subject) {
        boolean valid = group == null
                ? Set.of("user", "user_agent").contains(scope) && ("user:" + authorId).equals(subject)
                : "group".equals(scope) ? ("group:" + group.groupBindingId()).equals(subject)
                : "group_member".equals(scope) && group.subjectEndUserIds().stream().anyMatch(id -> ("user:" + id).equals(subject));
        if (!valid) throw invalid("memory_extraction_subject_invalid");
    }

    /** 仅为模型结果协议，不带落库或正式修订权限。 */
    public record ExtractionOutput(
            @JsonProperty(required = true) int schemaVersion,
            @JsonProperty(value = MemoryJsonCodec.ModelFields.CANDIDATES, required = true) List<Candidate> candidates) {
        public ExtractionOutput {
            version(schemaVersion);
            candidates = immutable(candidates, "memory_extraction_candidates_invalid");
            if (candidates.size() > MAX_CANDIDATES) throw invalid("memory_extraction_candidate_budget_exceeded");
        }
    }

    /** 仅表达提议；开放 object 保留 JSON，其他固定结构均为实体，集合与 JSON 双向隔离。 */
    public record Candidate(
            @JsonProperty(required = true) String type,
            @JsonProperty(required = true) String subject,
            @JsonProperty(required = true) String predicate,
            @JsonProperty(required = true) JsonNode object,
            @JsonProperty(required = true) String signal,
            @JsonProperty(required = true) boolean sensitive,
            @JsonProperty(required = true) String scope,
            @JsonProperty(required = true) Temporal temporal,
            @JsonProperty(required = true) List<String> conditions,
            @JsonProperty(required = true) List<Evidence> evidence,
            @JsonProperty(value = MemoryJsonCodec.ModelFields.RELATION, required = true) Relation relation,
            @JsonProperty(required = true) List<String> uncertainties) {
        public Candidate {
            member(type, TYPES, "memory_extraction_type_invalid");
            member(scope, SCOPES, "memory_extraction_scope_invalid");
            member(signal, SIGNALS, "memory_extraction_signal_invalid");
            text(subject, "memory_extraction_subject_invalid");
            text(predicate, "memory_predicate_missing");
            if ("user".equals(scope) && !"preference".equals(type)) throw invalid("memory_extraction_scope_invalid");
            object = value(object);
            require(temporal, "memory_extraction_temporal_invalid");
            conditions = strings(conditions);
            evidence = immutable(evidence, "memory_extraction_evidence_invalid");
            if (evidence.isEmpty() || evidence.size() > MAX_EVIDENCE_ITEMS) throw invalid("memory_extraction_evidence_invalid");
            require(relation, "memory_extraction_relation_invalid");
            uncertainties = strings(uncertainties);
        }
        @Override public JsonNode object() { return object.deepCopy(); }
    }

    /** interval 有明确开始且可开放结束；其他时间性质必须显式携带 null 起止字段。 */
    public record Temporal(
            @JsonProperty(required = true) String kind,
            @JsonProperty(required = true) Instant from,
            @JsonProperty(required = true) Instant to) {
        public Temporal {
            member(kind, TEMPORAL_KINDS, "memory_extraction_temporal_invalid");
            if ("interval".equals(kind) ? from == null || to != null && !to.isAfter(from) : from != null || to != null)
                throw invalid("memory_extraction_temporal_invalid");
        }
    }

    /** quote 必须为本次 sourceId 对应来源的连续原文，精确归属由 parse 校验。 */
    public record Evidence(
            @JsonProperty(required = true) String sourceId,
            @JsonProperty(required = true) String quote) {
        public Evidence {
            text(sourceId, "memory_extraction_evidence_invalid");
            text(quote, "memory_extraction_evidence_invalid");
        }
    }

    /** target 为冻结别名，new 必须显式 null；别名不提供正式替换权。 */
    public record Relation(
            @JsonProperty(value = MemoryJsonCodec.ModelFields.KIND, required = true) String kind,
            @JsonProperty(value = MemoryJsonCodec.ModelFields.TARGET, required = true) String target) {
        public Relation {
            member(kind, RELATION_KINDS, "memory_extraction_relation_invalid");
            if ("new".equals(kind) ? target != null : target == null || target.isBlank())
                throw invalid("memory_extraction_relation_invalid");
        }
    }

    /** 来源时间显式冻结，调试也不以当前执行时间代替消息时间。 */
    public record Source(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) String text,
            @JsonProperty(required = true) Instant occurredAt) {
        public Source {
            MemoryExtractionCandidates.text(id, "memory_extraction_source_invalid");
            MemoryExtractionCandidates.text(text, "memory_extraction_source_invalid");
            require(occurredAt, "memory_extraction_source_invalid");
        }
    }

    /** 关联快照不是新证据；object 为正式正文实体，开放 JSON 仅存在于其 value 内。 */
    public record ExistingFact(
            @JsonProperty(required = true) String id,
            @JsonProperty(required = true) String type,
            @JsonProperty(required = true) String scope,
            @JsonProperty(required = true) String subject,
            @JsonProperty(required = true) String predicate,
            @JsonProperty(required = true) MemoryContent.Content object,
            @JsonProperty(required = true) long revision,
            @JsonProperty(required = true) Instant validFrom,
            @JsonProperty(required = true) Instant validTo) {
        public ExistingFact {
            text(id, "memory_extraction_fact_invalid");
            member(type, TYPES, "memory_extraction_type_invalid");
            member(scope, SCOPES, "memory_extraction_scope_invalid");
            text(subject, "memory_extraction_subject_invalid");
            text(predicate, "memory_predicate_missing");
            object = MemoryContent.validate(object);
            if (revision < 1) throw invalid("memory_extraction_fact_invalid");
            if (validFrom == null || validTo != null && !validTo.isAfter(validFrom)) throw invalid("memory_extraction_temporal_invalid");
        }
    }

    /** 正文和提取协议都复用同一 Temporal 实体，不在业务中手工读取字段名。 */
    public static Temporal temporal(JsonNode encoded) {
        return MemoryJsonCodec.decode(encoded, Temporal.class, "memory_extraction_temporal_invalid");
    }

    public static ObjectNode temporalNode(Temporal temporal) { return MemoryJsonCodec.node(temporal); }

    /** 开放 JSON 值仅按结构规范化；保留大小写、字符串与数组顺序，不作自然语言判断。 */
    public static JsonNode value(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid("memory_object_invalid");
        budget(value.toString(), MAX_OBJECT_BYTES, "memory_extraction_object_budget_exceeded");
        return canonical(value, 0);
    }

    private static JsonNode canonical(JsonNode value, int depth) {
        if (depth > MAX_JSON_DEPTH || value.isPojo() || value.isBinary() || value.isMissingNode()
                || value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) throw invalid("memory_object_invalid");
        if (value.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var keys = new ArrayList<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) result.set(key, canonical(value.get(key), depth + 1));
            return result;
        }
        if (value.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            value.forEach(child -> result.add(canonical(child, depth + 1)));
            return result;
        }
        return value.deepCopy();
    }

    /** 条件与不确定性列表不可变；不折叠大小写、内容或原顺序。 */
    public static List<String> strings(List<String> values) {
        List<String> result = immutable(values, "memory_extraction_list_invalid");
        if (result.size() > MAX_LIST_ITEMS) throw invalid("memory_extraction_list_invalid");
        for (String value : result) {
            text(value, "memory_extraction_list_invalid");
            budget(value, MAX_OBJECT_BYTES, "memory_extraction_object_budget_exceeded");
        }
        return result;
    }

    private static <T> List<T> immutable(List<T> values, String code) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) throw invalid(code);
        return List.copyOf(values);
    }
    private static void version(int schemaVersion) {
        if (schemaVersion != SCHEMA_VERSION && schemaVersion != GROUP_SCHEMA_VERSION) throw invalid("memory_extraction_schema_invalid");
    }
    private static void member(String value, Set<String> values, String code) {
        if (value == null || !values.contains(value)) throw invalid(code);
    }
    private static void text(String value, String code) {
        if (value == null || value.isBlank()) throw invalid(code);
    }
    private static void require(Object value, String code) {
        if (value == null) throw invalid(code);
    }
    private static void budget(String value, int limit, String code) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > limit) throw invalid(code);
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
