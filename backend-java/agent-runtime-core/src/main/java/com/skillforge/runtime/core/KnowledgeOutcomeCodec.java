package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.EvidenceSufficiency;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgePlan;
import com.skillforge.runtime.api.KnowledgeStep;
import com.skillforge.runtime.api.KnowledgeTraceStep;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 完整知识证据的私有结构 codec；不执行检索，也不证明来源授权、提交或公开投影。 */
final class KnowledgeOutcomeCodec {
    static final String SCHEMA = "skillforge.runtime.portal.knowledge-outcome.v2";
    private final CodecLimits limits;
    private final PayloadValidator evidenceContents;

    /** 证据正文 registry 独立于 Tool/Workflow 输出，不能省略或使用默认放行。 */
    KnowledgeOutcomeCodec(CodecLimits limits, PayloadValidator evidenceContents) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.evidenceContents = Objects.requireNonNull(evidenceContents, "evidenceContents");
    }

    /** 整体资源预检在任何 JSON 树、转义副本和排序字节的分配之前完成。 */
    PortalOutcomeSnapshot encode(PortalCallKind kind, KnowledgeOutcome result) {
        PortalOutcomeSnapshot snapshot;
        try {
            requireKind(kind);
            if (result == null) { throw invalid(); }
            preflight(result);
            snapshot = structure(result);
        } catch (IllegalArgumentException exception) { throw invalid(); }
        decode(snapshot, kind);
        return snapshot;
    }

    /** 封套、精确字段、API 引用闭包和规范字节全部检查后才验证每项 opaque content。 */
    KnowledgeOutcome decode(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        KnowledgeOutcome result = structureDecode(snapshot, expectedKind);
        // 外部异常不进入本地解析 catch；部分校验失败也不返回可被误用的部分 bundle。
        if (result instanceof KnowledgeOutcome.Retrieved retrieved) {
            for (EvidenceItem item : retrieved.bundle().items()) { evidenceContents.validate(item.content(), limits); }
        }
        return result;
    }

    /** 终态命令先校验封闭 wire 结构；业务 content registry 留到本地 tuple 完整闭合之后。 */
    KnowledgeOutcome structureDecode(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        KnowledgeOutcome result;
        try {
            requireKind(expectedKind);
            if (snapshot == null || snapshot.callKind() != expectedKind || !SCHEMA.equals(snapshot.outcome().schemaId())) { throw invalid(); }
            Value root = CanonicalJson.parse(snapshot.outcome().canonicalJson(), limits);
            String type = root.field("outcomeType").stringValue();
            result = switch (type) {
                case "Retrieved" -> {
                    root.requireFields("outcomeType", "bundle", "resultRef");
                    yield new KnowledgeOutcome.Retrieved(readBundle(root.field("bundle")), new ResultRef(root.field("resultRef").stringValue()));
                }
                case "Failed", "OutcomeUnknown" -> {
                    root.requireFields("outcomeType", "failure");
                    PortFailure failure = readFailure(root.field("failure"));
                    yield type.equals("Failed") ? new KnowledgeOutcome.Failed(failure) : new KnowledgeOutcome.OutcomeUnknown(failure);
                }
                default -> throw invalid();
            };
            if (!snapshot.equals(structure(result))) { throw invalid(); }
        } catch (IllegalArgumentException | DateTimeException exception) { throw invalid(); }
        return result;
    }

    /** Observation 恢复复用同一 evidence content registry，不借私有 outcome snapshot 充当公开事件。 */
    void validateBundle(EvidenceBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        for (EvidenceItem item : bundle.items()) { evidenceContents.validate(item.content(), limits); }
    }

    /** 根据封闭联合计量 root；保留 API Failed 的模式，不替 guard 签发重试权限。 */
    private void preflight(KnowledgeOutcome result) {
        var budget = new OutcomeEncodingBudget(limits);
        switch (result) {
            case KnowledgeOutcome.Retrieved value -> {
                budget.object(1, "outcomeType", "bundle", "resultRef");
                budget.string(2, "Retrieved");
                preflightBundle(budget, value.bundle(), 2);
                budget.string(2, value.resultRef().value());
            }
            case KnowledgeOutcome.Failed value -> preflightFailure(budget, "Failed", value.failure());
            case KnowledgeOutcome.OutcomeUnknown value -> preflightFailure(budget, "OutcomeUnknown", value.failure());
        }
    }

    /** 分组、证据与轨迹各层集合先检查最小节点数，不能仅限制每个 payload 而遗漏聚合量。 */
    static void preflightBundle(OutcomeEncodingBudget budget, EvidenceBundle value, int depth) {
        budget.object(depth, "bundleId", "knowledgeRequestId", "plan", "items", "conflictItemIds", "sufficiency", "trace", "version", "createdAt", "documentResult");
        budget.string(depth + 1, value.bundleId());
        budget.string(depth + 1, value.knowledgeRequestId());
        preflightPlan(budget, value.plan(), depth + 1);
        budget.array(depth + 1, value.items().size());
        for (var item : value.items()) { preflightItem(budget, item, depth + 2); }
        budget.array(depth + 1, value.conflictItemIds().size());
        for (var group : value.conflictItemIds()) { preflightStrings(budget, group, depth + 2); }
        budget.string(depth + 1, value.sufficiency().name());
        budget.array(depth + 1, value.trace().size());
        for (var trace : value.trace()) { preflightTrace(budget, trace, depth + 2); }
        budget.string(depth + 1, Long.toString(value.version()));
        budget.string(depth + 1, value.createdAt().toString());
        if (value.documentResult().isPresent()) KnowledgeDocumentCodec.preflight(budget, value.documentResult().orElseThrow(), depth + 1);
        else budget.nil(depth + 1);
    }

    /** steps 是扁平列表，依赖路径由 API 迭代验证，不增加虚构的 JSON 深度。 */
    private static void preflightPlan(OutcomeEncodingBudget budget, KnowledgePlan value, int depth) {
        budget.object(depth, "planId", "knowledgeRequestId", "steps", "stopCondition", "plannerVersion");
        budget.string(depth + 1, value.planId());
        budget.string(depth + 1, value.knowledgeRequestId());
        budget.array(depth + 1, value.steps().size());
        for (var step : value.steps()) { preflightStep(budget, step, depth + 2); }
        budget.string(depth + 1, value.stopCondition());
        budget.string(depth + 1, value.plannerVersion());
    }

    /** Set 只在预检后排序；此处保留同样的元素成本而不分配任何排序 key。 */
    private static void preflightStep(OutcomeEncodingBudget budget, KnowledgeStep value, int depth) {
        budget.object(depth, "stepId", "query", "sourceIds", "operator", "dependsOn", "budget");
        budget.string(depth + 1, value.stepId());
        budget.string(depth + 1, value.query());
        budget.array(depth + 1, value.sourceIds().size());
        for (String sourceId : value.sourceIds()) { budget.string(depth + 2, sourceId); }
        budget.string(depth + 1, value.operator().name());
        preflightStrings(budget, value.dependsOn(), depth + 1);
        var local = value.budget();
        budget.object(depth + 1, "maxQueries", "maxItems", "maxTokens", "maxCharacters", "timeoutMs");
        budget.string(depth + 2, Integer.toString(local.maxQueries()));
        budget.string(depth + 2, Integer.toString(local.maxItems()));
        budget.string(depth + 2, Integer.toString(local.maxTokens()));
        budget.string(depth + 2, Integer.toString(local.maxCharacters()));
        budget.string(depth + 2, Long.toString(local.timeoutMs()));
    }

    /** relevance 的规范十进制字符串有固定 double 上界，外部正文则逐字符检查。 */
    private static void preflightItem(OutcomeEncodingBudget budget, EvidenceItem value, int depth) {
        budget.object(depth, "itemId", "sourceId", "sourceVersion", "operator", "content", "provenanceRefs", "graphPath", "relevance", "trustLevel");
        budget.string(depth + 1, value.itemId());
        budget.string(depth + 1, value.sourceId());
        budget.string(depth + 1, value.sourceVersion());
        budget.string(depth + 1, value.operator().name());
        budget.payload(depth + 1, value.content());
        preflightStrings(budget, value.provenanceRefs(), depth + 1);
        preflightStrings(budget, value.graphPath(), depth + 1);
        budget.string(depth + 1, relevance(value.relevance()));
        budget.string(depth + 1, value.trustLevel());
    }

    /** 轨迹的候选和选择顺序均属于原事实，不排序或从结果重新推算用量。 */
    private static void preflightTrace(OutcomeEncodingBudget budget, KnowledgeTraceStep value, int depth) {
        budget.object(depth, "stepId", "phase", "candidateItemIds", "selectedItemIds", "tokenCost", "elapsedMs");
        budget.string(depth + 1, value.stepId());
        budget.string(depth + 1, value.phase());
        preflightStrings(budget, value.candidateItemIds(), depth + 1);
        preflightStrings(budget, value.selectedItemIds(), depth + 1);
        budget.string(depth + 1, Integer.toString(value.tokenCost()));
        budget.string(depth + 1, Long.toString(value.elapsedMs()));
    }

    /** 列表不在预检中复制；先用 size 拒绝明显超额再按原元素计量。 */
    private static void preflightStrings(OutcomeEncodingBudget budget, List<String> values, int depth) {
        budget.array(depth, values.size());
        for (String value : values) { budget.string(depth + 1, value); }
    }

    /** 失败正文无证据项，仍完整保留 reason、mode 与业务审计引用。 */
    private static void preflightFailure(OutcomeEncodingBudget budget, String type, PortFailure failure) {
        budget.object(1, "outcomeType", "failure");
        budget.string(2, type);
        budget.failure(2, failure);
    }

    /** 结构构造只由预检后的 encode 或有界解析后的 decode 调用，不重复业务 registry。 */
    private static PortalOutcomeSnapshot structure(KnowledgeOutcome result) {
        return switch (result) {
            case KnowledgeOutcome.Retrieved value -> snapshot(PortalResolution.KNOWN_RESULT, "Retrieved",
                    CanonicalJson.object(List.of(new Field("outcomeType", CanonicalJson.string("Retrieved")),
                            new Field("bundle", bundleValue(value.bundle())), new Field("resultRef", CanonicalJson.string(value.resultRef().value())))));
            case KnowledgeOutcome.Failed value -> failureSnapshot(PortalResolution.KNOWN_FAILURE, "Failed", value.failure());
            case KnowledgeOutcome.OutcomeUnknown value -> failureSnapshot(PortalResolution.OUTCOME_UNKNOWN, "OutcomeUnknown", value.failure());
        };
    }

    /** 不把 bundleId/resultRef 当成完整查询事实的替代品。 */
    static Value bundleValue(EvidenceBundle value) {
        return CanonicalJson.object(List.of(
                new Field("bundleId", CanonicalJson.string(value.bundleId())),
                new Field("knowledgeRequestId", CanonicalJson.string(value.knowledgeRequestId())),
                new Field("plan", planValue(value.plan())),
                new Field("items", CanonicalJson.array(value.items().stream().map(KnowledgeOutcomeCodec::itemValue).toList())),
                new Field("conflictItemIds", CanonicalJson.array(value.conflictItemIds().stream().map(KnowledgeOutcomeCodec::strings).toList())),
                new Field("sufficiency", CanonicalJson.string(value.sufficiency().name())),
                new Field("trace", CanonicalJson.array(value.trace().stream().map(KnowledgeOutcomeCodec::traceValue).toList())),
                new Field("version", CanonicalJson.integer(value.version())),
                new Field("createdAt", CanonicalJson.string(value.createdAt().toString())),
                new Field("documentResult", value.documentResult().map(KnowledgeDocumentCodec::value).orElseGet(CanonicalJson::nil))));
    }

    /** 计划步骤保持冻结顺序，拓扑校验不能修改这个事实。 */
    private static Value planValue(KnowledgePlan value) {
        return CanonicalJson.object(List.of(new Field("planId", CanonicalJson.string(value.planId())),
                new Field("knowledgeRequestId", CanonicalJson.string(value.knowledgeRequestId())),
                new Field("steps", CanonicalJson.array(value.steps().stream().map(KnowledgeOutcomeCodec::stepValue).toList())),
                new Field("stopCondition", CanonicalJson.string(value.stopCondition())),
                new Field("plannerVersion", CanonicalJson.string(value.plannerVersion()))));
    }

    /** 来源集合与依赖列表的排序语义不同，不能共用排序入口。 */
    private static Value stepValue(KnowledgeStep value) {
        return CanonicalJson.object(List.of(new Field("stepId", CanonicalJson.string(value.stepId())),
                new Field("query", CanonicalJson.string(value.query())), new Field("sourceIds", sortedSources(value)),
                new Field("operator", CanonicalJson.string(value.operator().name())), new Field("dependsOn", strings(value.dependsOn())),
                new Field("budget", budgetValue(value.budget()))));
    }

    /** 全部预算精确到公开整数范围，不经 JSON number 或浮点中转。 */
    private static Value budgetValue(KnowledgeBudget value) {
        return CanonicalJson.object(List.of(new Field("maxQueries", CanonicalJson.integer(value.maxQueries())),
                new Field("maxItems", CanonicalJson.integer(value.maxItems())), new Field("maxTokens", CanonicalJson.integer(value.maxTokens())),
                new Field("maxCharacters", CanonicalJson.integer(value.maxCharacters())), new Field("timeoutMs", CanonicalJson.integer(value.timeoutMs()))));
    }

    /** EvidenceItem 的内容保持 TypedPayload 字符串封套，不把任意 JSON 拼进外围结构。 */
    private static Value itemValue(EvidenceItem value) {
        return CanonicalJson.object(List.of(new Field("itemId", CanonicalJson.string(value.itemId())),
                new Field("sourceId", CanonicalJson.string(value.sourceId())), new Field("sourceVersion", CanonicalJson.string(value.sourceVersion())),
                new Field("operator", CanonicalJson.string(value.operator().name())), new Field("content", payloadValue(value.content())),
                new Field("provenanceRefs", strings(value.provenanceRefs())), new Field("graphPath", strings(value.graphPath())),
                new Field("relevance", CanonicalJson.string(relevance(value.relevance()))), new Field("trustLevel", CanonicalJson.string(value.trustLevel()))));
    }

    /** 已消耗用量与选择事实只保存，不在 codec 中猜测或纠偏。 */
    private static Value traceValue(KnowledgeTraceStep value) {
        return CanonicalJson.object(List.of(new Field("stepId", CanonicalJson.string(value.stepId())), new Field("phase", CanonicalJson.string(value.phase())),
                new Field("candidateItemIds", strings(value.candidateItemIds())), new Field("selectedItemIds", strings(value.selectedItemIds())),
                new Field("tokenCost", CanonicalJson.integer(value.tokenCost())), new Field("elapsedMs", CanonicalJson.integer(value.elapsedMs()))));
    }

    /** 列表编码保序，所有成本已在结构生成前计量。 */
    private static Value strings(List<String> values) { return CanonicalJson.array(values.stream().map(CanonicalJson::string).toList()); }

    /** 仅为排序缓存规范元素及其字节，不构成另一套业务 DTO。 */
    private record EncodedString(Value value, byte[] bytes) { }

    /** 比较规范 JSON 字符串元素的无符号 UTF-8 字节，避免 BMP/补充字符和转义顺序错误。 */
    private static Value sortedSources(KnowledgeStep step) {
        List<EncodedString> encoded = step.sourceIds().stream().map(value -> {
            Value canonical = CanonicalJson.string(value);
            return new EncodedString(canonical, CanonicalJson.encode(canonical).getBytes(StandardCharsets.UTF_8));
        }).sorted((left, right) -> Arrays.compareUnsigned(left.bytes(), right.bytes())).toList();
        return CanonicalJson.array(encoded.stream().map(EncodedString::value).toList());
    }

    /** decode 重建整个 API 闭包，避免错 request/冲突项/轨迹来源变成可见证据。 */
    static EvidenceBundle readBundle(Value value) {
        value.requireFields("bundleId", "knowledgeRequestId", "plan", "items", "conflictItemIds", "sufficiency", "trace", "version", "createdAt", "documentResult");
        return new EvidenceBundle(value.field("bundleId").stringValue(), value.field("knowledgeRequestId").stringValue(), readPlan(value.field("plan")),
                value.field("items").arrayValue().stream().map(KnowledgeOutcomeCodec::readItem).toList(),
                value.field("conflictItemIds").arrayValue().stream().map(KnowledgeOutcomeCodec::readStrings).toList(),
                EvidenceSufficiency.valueOf(value.field("sufficiency").stringValue()),
                value.field("trace").arrayValue().stream().map(KnowledgeOutcomeCodec::readTrace).toList(),
                value.field("version").longValue(), Instant.parse(value.field("createdAt").stringValue()),
                value.field("documentResult").isNull() ? java.util.Optional.empty() : java.util.Optional.of(KnowledgeDocumentCodec.read(value.field("documentResult"))));
    }

    /** KnowledgePlan 构造期使用已修复的迭代 DAG 检查，不捕获 StackOverflowError 伪装协议失败。 */
    private static KnowledgePlan readPlan(Value value) {
        value.requireFields("planId", "knowledgeRequestId", "steps", "stopCondition", "plannerVersion");
        return new KnowledgePlan(value.field("planId").stringValue(), value.field("knowledgeRequestId").stringValue(),
                value.field("steps").arrayValue().stream().map(KnowledgeOutcomeCodec::readStep).toList(),
                value.field("stopCondition").stringValue(), value.field("plannerVersion").stringValue());
    }

    /** Set 读取先拒绝重复，再由完整重编码检查唯一排序；依赖列表保留原顺序。 */
    private static KnowledgeStep readStep(Value value) {
        value.requireFields("stepId", "query", "sourceIds", "operator", "dependsOn", "budget");
        // Set.copyOf 会静默去重，必须先拒绝重复；领域对象直接持有集合，不扩展通用集合签名边界。
        var unique = new HashSet<String>();
        for (Value element : value.field("sourceIds").arrayValue()) { if (!unique.add(element.stringValue())) { throw invalid(); } }
        return new KnowledgeStep(value.field("stepId").stringValue(), value.field("query").stringValue(), Set.copyOf(unique),
                KnowledgeOperatorType.valueOf(value.field("operator").stringValue()), readStrings(value.field("dependsOn")), readBudget(value.field("budget")));
    }

    /** 窄化到 int 之前验证精确范围；timeoutMs 保持 long。 */
    private static KnowledgeBudget readBudget(Value value) {
        value.requireFields("maxQueries", "maxItems", "maxTokens", "maxCharacters", "timeoutMs");
        return new KnowledgeBudget(value.field("maxQueries").intValue(), value.field("maxItems").intValue(), value.field("maxTokens").intValue(),
                value.field("maxCharacters").intValue(), value.field("timeoutMs").longValue());
    }

    /** relevance 先经过有限区间约束，再经整个 body 的重编码拒绝数值别名。 */
    private static EvidenceItem readItem(Value value) {
        value.requireFields("itemId", "sourceId", "sourceVersion", "operator", "content", "provenanceRefs", "graphPath", "relevance", "trustLevel");
        return new EvidenceItem(value.field("itemId").stringValue(), value.field("sourceId").stringValue(), value.field("sourceVersion").stringValue(),
                KnowledgeOperatorType.valueOf(value.field("operator").stringValue()), readPayload(value.field("content")),
                readStrings(value.field("provenanceRefs")), readStrings(value.field("graphPath")), Double.parseDouble(value.field("relevance").stringValue()),
                value.field("trustLevel").stringValue());
    }

    /** trace 的选择是候选子集，API 构造器及 bundle 共同检查引用闭包。 */
    private static KnowledgeTraceStep readTrace(Value value) {
        value.requireFields("stepId", "phase", "candidateItemIds", "selectedItemIds", "tokenCost", "elapsedMs");
        return new KnowledgeTraceStep(value.field("stepId").stringValue(), value.field("phase").stringValue(), readStrings(value.field("candidateItemIds")),
                readStrings(value.field("selectedItemIds")), value.field("tokenCost").intValue(), value.field("elapsedMs").longValue());
    }

    /** 字符串数组不做 trim、去重或排序，以便后续严格拒绝错值。 */
    private static List<String> readStrings(Value value) { return value.arrayValue().stream().map(Value::stringValue).toList(); }

    /** 私有失败事实完整保存，不把业务 resultRef 当作账本 attempt factRef。 */
    private static PortalOutcomeSnapshot failureSnapshot(PortalResolution resolution, String type, PortFailure failure) {
        return snapshot(resolution, type, CanonicalJson.object(List.of(new Field("outcomeType", CanonicalJson.string(type)),
                new Field("failure", CanonicalJson.object(List.of(new Field("reasonCode", CanonicalJson.string(failure.reasonCode())),
                        new Field("retryMode", CanonicalJson.string(failure.retryMode().name())), new Field("resultRef", CanonicalJson.string(failure.resultRef().value()))))))));
    }

    /** 业务 payload 的内部 canonicalJson 字符串由独立 registry 验证，不在此解析业务 schema。 */
    private static Value payloadValue(TypedPayload value) { return CanonicalJson.object(List.of(new Field("schemaId", CanonicalJson.string(value.schemaId())), new Field("canonicalJson", CanonicalJson.string(value.canonicalJson())))); }

    /** TypedPayload 精确两字段，无版本别名或 fallback。 */
    private static TypedPayload readPayload(Value value) {
        value.requireFields("schemaId", "canonicalJson");
        return new TypedPayload(value.field("schemaId").stringValue(), value.field("canonicalJson").stringValue());
    }

    /** RetryMode.valueOf 的原输入错误仅在本地 catch 内发生。 */
    private static PortFailure readFailure(Value value) {
        value.requireFields("reasonCode", "retryMode", "resultRef");
        return new PortFailure(value.field("reasonCode").stringValue(), RetryMode.valueOf(value.field("retryMode").stringValue()), new ResultRef(value.field("resultRef").stringValue()));
    }

    /** 与 temperature 一致，负零规范化为零；不暴露 floating-point JSON number。 */
    private static String relevance(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    /** 外层注册表是第二层闭包检查，独立于 body 的字段和 discriminator 校验。 */
    private static PortalOutcomeSnapshot snapshot(PortalResolution resolution, String type, Value value) {
        return new PortalOutcomeSnapshot(PortalCallKind.KNOWLEDGE_EXECUTE, resolution, type, new TypedPayload(SCHEMA, CanonicalJson.encode(value)));
    }

    /** Knowledge 结果不能被其他 Portal 的消费路径读取。 */
    private static void requireKind(PortalCallKind kind) { if (kind != PortalCallKind.KNOWLEDGE_EXECUTE) { throw invalid(); } }

    /** 本地解析错误固定文案且不保留可能泄露证据正文的 cause。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid knowledge outcome"); }
}
