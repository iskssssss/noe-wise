package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelPort.AssistantContent;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.core.CanonicalJson.Field;
import com.skillforge.runtime.core.CanonicalJson.Value;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** 无损保存已按模型策略收窄的终态；只验证注册结构，不把业务结果引用当成账本事实。 */
final class ModelOutcomeCodec {
    static final String SCHEMA = "skillforge.runtime.portal.model-outcome.v5";
    private final CodecLimits limits;
    private final PayloadValidator validator;

    /** 输出 registry 必须显式注入，首次结果和恢复使用相同的资源与 schema 边界。 */
    ModelOutcomeCodec(CodecLimits limits, PayloadValidator validator) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /** 编码也通过有界解析和业务 registry，不能以新生成的 payload 为由跳过输入限制。 */
    PortalOutcomeSnapshot encode(PortalCallKind kind, ModelResult result) {
        preflight(result);
        if (result instanceof ModelResult.Completed completed) requireNativeOutput(completed);
        var snapshot = encodeStructure(kind, result);
        decode(snapshot, kind);
        return snapshot;
    }

    /** 与私有 body 精确同形的计量先于 JSON Value/转义副本分配。 */
    private void preflight(ModelResult result) {
        var budget = new OutcomeEncodingBudget(limits);
        switch (result) {
            case ModelResult.Completed value -> {
                budget.object(1, "outcomeType", "output", "effectiveModelId", "usage", "finishReasonCode", "resultRef", "reasoningContent", "assistantTurn", "protocolState");
                NativeProtocolStateCodec.preflight(budget, value.protocolState(), 2);
                budget.string(2, "Completed");
                budget.payload(2, value.output());
                budget.string(2, value.effectiveModelId());
                budget.object(2, "inputTokens", "outputTokens", "cachedInputTokens", "cacheWriteInputTokens", "reasoningOutputTokens");
                preflightTokens(budget, value.usage().inputTokens());
                preflightTokens(budget, value.usage().outputTokens());
                preflightTokens(budget, value.usage().cachedInputTokens());
                preflightTokens(budget, value.usage().cacheWriteInputTokens());
                preflightTokens(budget, value.usage().reasoningOutputTokens());
                budget.string(2, value.finishReasonCode());
                budget.string(2, value.resultRef().value());
                if (value.reasoningContent().isPresent()) {
                    budget.string(2, value.reasoningContent().orElseThrow());
                } else {
                    budget.nil(2);
                }
                if (value.assistantTurn().isPresent()) {
                    var turn = value.assistantTurn().orElseThrow();
                    budget.object(2, "contents");
                    budget.array(3, turn.contents().size());
                    for (var block : turn.contents()) {
                        switch (block) {
                            case AssistantContent.Text text -> {
                                budget.object(4, "type", "text");
                                budget.string(5, "text"); budget.string(5, text.text());
                            }
                            case AssistantContent.ToolCall call -> {
                                budget.object(4, "type", "callId", "wireName", "argumentsJson");
                                budget.string(5, "tool_call"); budget.string(5, call.callId());
                                budget.string(5, call.wireName()); budget.string(5, call.argumentsJson());
                            }
                        }
                    }
                } else {
                    budget.nil(2);
                }
            }
            case ModelResult.Failed value -> {
                budget.object(1, "outcomeType", "failure", "retryAt");
                budget.string(2, "Failed"); budget.failure(2, value.failure());
                if (value.retryAt().isPresent()) budget.string(2, value.retryAt().orElseThrow().toString()); else budget.nil(2);
            }
            case ModelResult.OutcomeUnknown value -> preflightFailure(budget, "OutcomeUnknown", value.failure());
        }
    }

    /** 固定长度十进制转换只分配至多 20 字符，不复制不受限的外部正文。 */
    private static void preflightTokens(OutcomeEncodingBudget budget, OptionalLong tokens) {
        if (tokens.isPresent()) { budget.string(3, Long.toString(tokens.getAsLong())); } else { budget.nil(3); }
    }

    /** 两种失败的字段数量相同，但 discriminator 长度必须分别计量。 */
    private static void preflightFailure(OutcomeEncodingBudget budget, String type, PortFailure failure) {
        budget.object(1, "outcomeType", "failure");
        budget.string(2, type);
        budget.failure(2, failure);
    }

    /** 外部 call kind 是调用方提供的预期，generate 和 stream 的共用 body 不能掩盖 kind 漂移。 */
    ModelResult decode(PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        ModelResult result;
        try {
            requireKind(expectedKind);
            if (snapshot.callKind() != expectedKind || !SCHEMA.equals(snapshot.outcome().schemaId())) {
                throw new IllegalArgumentException("model outcome kind mismatch");
            }
            var root = CanonicalJson.parse(snapshot.outcome().canonicalJson(), limits);
            String type = root.field("outcomeType").stringValue();
            result = switch (type) {
                case "Completed" -> {
                    root.requireFields("outcomeType", "output", "effectiveModelId", "usage", "finishReasonCode", "resultRef", "reasoningContent", "assistantTurn", "protocolState");
                    var payload = root.field("output").requireFields("schemaId", "canonicalJson");
                    var usage = root.field("usage");
                    yield new ModelResult.Completed(new TypedPayload(payload.field("schemaId").stringValue(), payload.field("canonicalJson").stringValue()),
                            root.field("effectiveModelId").stringValue(), ModelUsageCodec.decode(usage),
                            root.field("finishReasonCode").stringValue(), new ResultRef(root.field("resultRef").stringValue()),
                            root.field("reasoningContent").isNull() ? java.util.Optional.empty()
                                    : java.util.Optional.of(root.field("reasoningContent").stringValue()),
                            AssistantTurnCodec.decode(root.field("assistantTurn")), NativeProtocolStateCodec.decode(root.field("protocolState")));
                }
                case "Failed", "OutcomeUnknown" -> {
                    if (type.equals("Failed")) root.requireFields("outcomeType", "failure", "retryAt");
                    else root.requireFields("outcomeType", "failure");
                    var failure = root.field("failure").requireFields("reasonCode", "retryMode", "resultRef");
                    var decoded = new PortFailure(failure.field("reasonCode").stringValue(), RetryMode.valueOf(failure.field("retryMode").stringValue()),
                            new ResultRef(failure.field("resultRef").stringValue()));
                    requireNoRetry(decoded);
                    yield type.equals("Failed") ? new ModelResult.Failed(decoded, root.field("retryAt").isNull() ? java.util.Optional.empty()
                            : java.util.Optional.of(java.time.Instant.parse(root.field("retryAt").stringValue()))) : new ModelResult.OutcomeUnknown(decoded);
                }
                default -> throw new IllegalArgumentException("unknown model outcome type");
            };
            if (!snapshot.equals(encodeStructure(expectedKind, result))) {
                throw new IllegalArgumentException("model outcome is not canonical");
            }
            if (result instanceof ModelResult.Completed completed) requireNativeOutput(completed);
        } catch (IllegalArgumentException exception) {
            // 不把枚举解析等异常中的原输出沿 cause 链带出 codec。
            throw new IllegalArgumentException("invalid model outcome");
        }
        // 外部 registry 的技术异常保留原有原因；本地解析 catch 不得把服务不可用伪装成 schema 拒绝。
        if (result instanceof ModelResult.Completed completed) {
            validator.validate(completed.output(), limits);
        }
        return result;
    }

    /** 原生消息是唯一事实来源，正文投影不能与原始内容产生两套含义。 */
    private static void requireNativeOutput(ModelResult.Completed result) {
        if (result.assistantTurn().isEmpty()) return;
        if (!ModelPort.TEXT_OUTPUT_SCHEMA.equals(result.output().schemaId())
                || !CanonicalJson.encode(CanonicalJson.string(result.assistantTurn().orElseThrow().content()))
                        .equals(result.output().canonicalJson())) {
            throw new IllegalArgumentException("native assistant output mismatch");
        }
    }

    /** 精确结构构造器不再次调用 registry，避免 roundtrip 验证递归及重复业务查询。 */
    private static PortalOutcomeSnapshot encodeStructure(PortalCallKind kind, ModelResult result) {
        requireKind(kind);
        return switch (result) {
            case ModelResult.Completed value -> snapshot(kind, PortalResolution.KNOWN_RESULT, "Completed", CanonicalJson.object(List.of(
                    new Field("outcomeType", CanonicalJson.string("Completed")),
                    new Field("output", CanonicalJson.object(List.of(new Field("schemaId", CanonicalJson.string(value.output().schemaId())),
                            new Field("canonicalJson", CanonicalJson.string(value.output().canonicalJson()))))),
                    new Field("effectiveModelId", CanonicalJson.string(value.effectiveModelId())),
                    new Field("usage", ModelUsageCodec.encode(value.usage())),
                    new Field("finishReasonCode", CanonicalJson.string(value.finishReasonCode())),
                    new Field("resultRef", CanonicalJson.string(value.resultRef().value())),
                    new Field("reasoningContent", value.reasoningContent().map(CanonicalJson::string).orElseGet(CanonicalJson::nil)),
                    new Field("assistantTurn", AssistantTurnCodec.encode(value.assistantTurn())),
                    new Field("protocolState", NativeProtocolStateCodec.encode(value.protocolState())))));
            case ModelResult.Failed value -> {
                requireNoRetry(value.failure());
                yield snapshot(kind, PortalResolution.KNOWN_FAILURE, "Failed", CanonicalJson.object(List.of(
                        new Field("outcomeType", CanonicalJson.string("Failed")), new Field("failure", failureValue(value.failure())),
                        new Field("retryAt", value.retryAt().map(at -> CanonicalJson.string(at.toString())).orElseGet(CanonicalJson::nil)))));
            }
            case ModelResult.OutcomeUnknown value -> failureSnapshot(kind, PortalResolution.OUTCOME_UNKNOWN, "OutcomeUnknown", value.failure());
        };
    }

    /** Failed 和 unknown 均保存完整失败事实，存储态固定不自动重试模型副作用。 */
    private static PortalOutcomeSnapshot failureSnapshot(PortalCallKind kind, PortalResolution resolution, String type, PortFailure failure) {
        requireNoRetry(failure);
        return snapshot(kind, resolution, type, CanonicalJson.object(List.of(new Field("outcomeType", CanonicalJson.string(type)),
                new Field("failure", CanonicalJson.object(List.of(new Field("reasonCode", CanonicalJson.string(failure.reasonCode())),
                        new Field("retryMode", CanonicalJson.string(failure.retryMode().name())),
                        new Field("resultRef", CanonicalJson.string(failure.resultRef().value()))))))));
    }

    private static Value failureValue(PortFailure failure) {
        return CanonicalJson.object(List.of(new Field("reasonCode", CanonicalJson.string(failure.reasonCode())),
                new Field("retryMode", CanonicalJson.string(failure.retryMode().name())),
                new Field("resultRef", CanonicalJson.string(failure.resultRef().value()))));
    }

    /** API 封套额外验证 schema/discriminator 闭包，不另起 adapter 别名。 */
    private static PortalOutcomeSnapshot snapshot(PortalCallKind kind, PortalResolution resolution, String type, Value body) {
        return new PortalOutcomeSnapshot(kind, resolution, type, new TypedPayload(SCHEMA, CanonicalJson.encode(body)));
    }

    /** 未提供用量与零不同；存在值由 ModelUsage 保证严格为正。 */
    private static OptionalLong tokens(Value value) { return value.isNull() ? OptionalLong.empty() : OptionalLong.of(value.longValue()); }

    /** 避免超过 2^53 的有效 token 计数经浮点 JSON 丢失精度。 */
    private static Value tokens(OptionalLong value) { return value.isPresent() ? CanonicalJson.integer(value.getAsLong()) : CanonicalJson.nil(); }

    /** 模型只有两种调用种类，其余 Portal 不能借用共用 schema。 */
    private static void requireKind(PortalCallKind kind) {
        if (kind != PortalCallKind.MODEL_GENERATE && kind != PortalCallKind.MODEL_STREAM) { throw new IllegalArgumentException("not a model call kind"); }
    }

    /** guard 先完成策略交集；codec 不静默改写待持久化结果。 */
    private static void requireNoRetry(PortFailure failure) {
        if (failure.retryMode() != RetryMode.NONE) { throw new IllegalArgumentException("stored model failure must not retry"); }
    }
}
