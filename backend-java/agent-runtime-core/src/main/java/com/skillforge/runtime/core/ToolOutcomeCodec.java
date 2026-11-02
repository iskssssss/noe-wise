package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.codec.spi.ToolOutcomeCapacityExceeded;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Tool 私有结果的唯一结构编码，不产生公开 observation、提交证明或重试权限。 */
final class ToolOutcomeCodec {
    static final String SCHEMA = "skillforge.runtime.portal.tool-outcome.v2";
    private final CodecLimits limits;
    private final PayloadValidator observations;

    /** 业务 observation registry 必须由宿主显式提供。 */
    ToolOutcomeCodec(CodecLimits limits, PayloadValidator observations) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    /** 仅供组合协议在任何 registry 前比对完整事实；不授予持久来源或安全投影证明。 */
    PortalOutcomeSnapshot structureSnapshot(ToolOutcome result) {
        preflight(result);
        return structure(result);
    }

    /** 先预检成本再构造字符串；严格回读与业务校验只执行一次。 */
    PortalOutcomeSnapshot encode(PortalCallKind kind, ToolOutcome result) {
        PortalOutcomeSnapshot snapshot;
        try {
            requireKind(kind);
            if (result instanceof ToolOutcome.Failed failed && failed.comparisonFact().isPresent())
                validateComparisonFact(failed.comparisonFact().orElseThrow());
            preflight(result);
            snapshot = structure(result);
        } catch (OutcomeEncodingBudget.CapacityExceeded | ToolOutcomeCapacityExceeded capacity) {
            // 降级只能针对容量：先完整核验事实与失败字段，不能把畸形输入当成空间不足。
            if (result instanceof ToolOutcome.Failed failed && failed.comparisonFact().isPresent()) {
                try {
                    CanonicalJson.string(failed.failure().reasonCode());
                    CanonicalJson.string(failed.failure().resultRef().value());
                    var fact = failed.comparisonFact().orElseThrow();
                    CanonicalJson.string(fact.schemaId());
                    int length = fact.canonicalJson().length();
                    CanonicalJson.parse(fact.canonicalJson(), new CodecLimits(length, length, 128));
                } catch (IllegalArgumentException invalid) { throw invalid(); }
            }
            throw new ToolOutcomeCapacityExceeded();
        } catch (IllegalArgumentException exception) { throw invalid(); }
        decode(snapshot, kind);
        return snapshot;
    }

    /** 外层 tuple、封闭字段与 canonical bytes 全部匹配后才进入业务 registry。 */
    ToolOutcome decode(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        var result = structureDecode(snapshot, expectedKind);
        // 外部异常保留原实例和原因，不能把 registry 不可用伪装成本地 wire 拒绝。
        if (result instanceof ToolOutcome.Succeeded success) { observations.validate(success.observation(), limits); }
        return result;
    }

    /** 组合命令先闭合全部私有结构再运行 registry；该局部结果不证明来源、安全输出或已登记。 */
    ToolOutcome structureDecode(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        ToolOutcome result;
        try {
            requireKind(expectedKind);
            if (snapshot.callKind() != expectedKind || !SCHEMA.equals(snapshot.outcome().schemaId())) { throw invalid(); }
            var root = CanonicalJson.parse(snapshot.outcome().canonicalJson(), limits);
            String type = root.field("outcomeType").stringValue();
            result = switch (type) {
                case "Succeeded" -> {
                    root.requireFields("outcomeType", "observation", "resultRef", "usageRefs");
                    var payload = root.field("observation").requireFields("schemaId", "canonicalJson");
                    yield new ToolOutcome.Succeeded(new TypedPayload(payload.field("schemaId").stringValue(), payload.field("canonicalJson").stringValue()),
                            new ResultRef(root.field("resultRef").stringValue()),
                            root.field("usageRefs").arrayValue().stream().map(value -> new ResultRef(value.stringValue())).toList());
                }
                case "InputRequired" -> {
                    root.requireMembers(List.of("outcomeType"),
                            List.of("inputRequests", "requestState"));
                    Map<String, ToolInputRequest> requests = new LinkedHashMap<>();
                    var requestField = root.optionalField("inputRequests");
                    if (requestField.isPresent()) {
                        for (var field : requestField.orElseThrow().objectFields()) {
                            var request = field.value().requireFields("method", "params");
                            requests.put(field.name(), new ToolInputRequest(
                                    ToolInputMethod.fromWireName(
                                            request.field("method").stringValue()),
                                    readPayload(request.field("params"))));
                        }
                    }
                    Optional<String> requestState = root.optionalField("requestState")
                            .map(Value::stringValue);
                    yield new ToolOutcome.InputRequired(requests, requestState);
                }
                case "Failed", "OutcomeUnknown" -> {
                    if (type.equals("Failed")) root.requireFields("outcomeType", "failure", "comparisonFact");
                    else root.requireFields("outcomeType", "failure");
                    var value = root.field("failure").requireFields("reasonCode", "retryMode", "resultRef");
                    var failure = new PortFailure(value.field("reasonCode").stringValue(), RetryMode.valueOf(value.field("retryMode").stringValue()),
                            new ResultRef(value.field("resultRef").stringValue()));
                    if (type.equals("OutcomeUnknown")) yield new ToolOutcome.OutcomeUnknown(failure);
                    var fact = root.field("comparisonFact");
                    Optional<TypedPayload> detail = fact.isNull() ? Optional.empty() : Optional.of(readPayload(fact));
                    if (detail.isPresent()) validateComparisonFact(detail.orElseThrow());
                    yield new ToolOutcome.Failed(failure, detail);
                }
                default -> throw invalid();
            };
            if (!snapshot.equals(structure(result))) { throw invalid(); }
        } catch (IllegalArgumentException exception) { throw invalid(); }
        return result;
    }

    /** 事实自身也受解析预算约束；仅在完整规范事实超预算时报告容量。 */
    private void validateComparisonFact(TypedPayload fact) {
        CanonicalJson.string(fact.schemaId());
        try { CanonicalJson.parse(fact.canonicalJson(), limits); }
        catch (IllegalArgumentException rejected) {
            int length = fact.canonicalJson().length();
            CanonicalJson.parse(fact.canonicalJson(), new CodecLimits(length, length, 128));
            throw new ToolOutcomeCapacityExceeded();
        }
    }

    /** 预检与规范结构一一对应，usageRefs 先判集合额度再读取元素。 */
    private void preflight(ToolOutcome result) {
        var budget = new OutcomeEncodingBudget(limits);
        switch (result) {
            case ToolOutcome.Succeeded value -> {
                budget.object(1, "outcomeType", "observation", "resultRef", "usageRefs");
                budget.array(2, value.usageRefs().size());
                budget.string(2, "Succeeded");
                budget.payload(2, value.observation());
                budget.string(2, value.resultRef().value());
                for (var ref : value.usageRefs()) { budget.string(3, ref.value()); }
            }
            case ToolOutcome.InputRequired value -> {
                var names = new java.util.ArrayList<String>();
                names.add("outcomeType");
                if (!value.inputRequests().isEmpty()) names.add("inputRequests");
                if (value.requestState().isPresent()) names.add("requestState");
                budget.object(1, names.toArray(String[]::new));
                budget.string(2, "InputRequired");
                if (!value.inputRequests().isEmpty()) {
                    budget.object(2, value.inputRequests().keySet().toArray(String[]::new));
                    value.inputRequests().forEach((requestId, request) -> {
                        budget.object(3, "method", "params");
                        budget.string(4, request.method().wireName());
                        budget.payload(4, request.params());
                    });
                }
                value.requestState().ifPresent(state -> budget.string(2, state));
            }
            case ToolOutcome.Failed value -> {
                budget.object(1, "outcomeType", "failure", "comparisonFact");
                budget.string(2, "Failed");
                budget.failure(2, value.failure());
                if (value.comparisonFact().isPresent()) budget.payload(2, value.comparisonFact().orElseThrow());
                else budget.nil(2);
            }
            case ToolOutcome.OutcomeUnknown value -> preflightFailure(budget, "OutcomeUnknown", value.failure());
        }
    }

    /** 不强加模型的 NONE 策略，允许模式由 Tool API 构造器校验。 */
    private static void preflightFailure(OutcomeEncodingBudget budget, String type, PortFailure failure) {
        budget.object(1, "outcomeType", "failure");
        budget.string(2, type);
        budget.failure(2, failure);
    }

    /** 只组装结构；调用方已完成资源预检或有界解析，不重复业务 registry。 */
    private static PortalOutcomeSnapshot structure(ToolOutcome result) {
        return switch (result) {
            case ToolOutcome.Succeeded value -> snapshot(PortalResolution.KNOWN_RESULT, "Succeeded", List.of(
                    new Field("observation", CanonicalJson.object(List.of(new Field("schemaId", CanonicalJson.string(value.observation().schemaId())),
                            new Field("canonicalJson", CanonicalJson.string(value.observation().canonicalJson()))))),
                    new Field("resultRef", CanonicalJson.string(value.resultRef().value())),
                    new Field("usageRefs", CanonicalJson.array(value.usageRefs().stream().map(ref -> CanonicalJson.string(ref.value())).toList()))));
            case ToolOutcome.InputRequired value -> inputRequiredSnapshot(value);
            case ToolOutcome.Failed value -> snapshot(PortalResolution.KNOWN_FAILURE, "Failed", List.of(
                    new Field("failure", failureValue(value.failure())),
                    new Field("comparisonFact", value.comparisonFact().map(ToolOutcomeCodec::payload).orElseGet(CanonicalJson::nil))));
            case ToolOutcome.OutcomeUnknown value -> failureSnapshot(PortalResolution.OUTCOME_UNKNOWN, "OutcomeUnknown", value.failure());
        };
    }

    /** inputRequests 保持动态请求 identity，requestState 只作为 opaque 字符串原样持久。 */
    private static PortalOutcomeSnapshot inputRequiredSnapshot(ToolOutcome.InputRequired value) {
        var fields = new java.util.ArrayList<Field>();
        if (!value.inputRequests().isEmpty()) {
            var requests = new java.util.ArrayList<Field>();
            for (String requestId : value.inputRequests().keySet()) {
                var request = value.inputRequests().get(requestId);
                requests.add(new Field(requestId, CanonicalJson.object(List.of(
                        new Field("method", CanonicalJson.string(request.method().wireName())),
                        new Field("params", payload(request.params()))))));
            }
            fields.add(new Field("inputRequests", CanonicalJson.object(requests)));
        }
        if (value.requestState().isPresent()) {
            fields.add(new Field("requestState",
                    CanonicalJson.string(value.requestState().orElseThrow())));
        }
        return snapshot(PortalResolution.INPUT_REQUIRED, "InputRequired", fields);
    }

    /** TypedPayload 私有封套不把业务 JSON 拼接进协议结构。 */
    private static Value payload(TypedPayload value) {
        return CanonicalJson.object(List.of(
                new Field("schemaId", CanonicalJson.string(value.schemaId())),
                new Field("canonicalJson", CanonicalJson.string(value.canonicalJson()))));
    }

    /** 只接受精确的 TypedPayload 两字段结构。 */
    private static TypedPayload readPayload(Value value) {
        value.requireFields("schemaId", "canonicalJson");
        return new TypedPayload(value.field("schemaId").stringValue(),
                value.field("canonicalJson").stringValue());
    }

    /** 三个失败字段均为私有回放事实，不向公开事件投影。 */
    private static PortalOutcomeSnapshot failureSnapshot(PortalResolution resolution, String type, PortFailure failure) {
        return snapshot(resolution, type, List.of(new Field("failure", failureValue(failure))));
    }

    private static Value failureValue(PortFailure failure) {
        return CanonicalJson.object(List.of(new Field("reasonCode", CanonicalJson.string(failure.reasonCode())),
                new Field("retryMode", CanonicalJson.string(failure.retryMode().name())), new Field("resultRef", CanonicalJson.string(failure.resultRef().value()))));
    }

    /** body discriminator 与公开 API 注册封套双重绑定。 */
    private static PortalOutcomeSnapshot snapshot(PortalResolution resolution, String type, List<Field> fields) {
        var all = new java.util.ArrayList<>(fields);
        all.add(new Field("outcomeType", CanonicalJson.string(type)));
        return new PortalOutcomeSnapshot(PortalCallKind.TOOL_INVOKE, resolution, type,
                new TypedPayload(SCHEMA, CanonicalJson.encode(CanonicalJson.object(all))));
    }

    /** 不接受其他 Portal 借用 Tool 结构。 */
    private static void requireKind(PortalCallKind kind) { if (kind != PortalCallKind.TOOL_INVOKE) { throw invalid(); } }

    /** 不把解析失败的私有载荷经 cause 链泄露。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid tool outcome"); }
}
