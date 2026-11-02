package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.KnowledgeRequest;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 新执行链唯一实际知识请求，只冻结完整target/request，不保存旧Context或内部journal。 */
public record FrozenKnowledgeRequest(FrozenKnowledgeTarget target, KnowledgeRequest request) {
    public static final String SCHEMA = "skillforge.runtime.execution.frozen-knowledge-request.v2";

    /** 请求只能收窄原冻结来源、版本、算子与时限；身份由Session再与真实Run核对。 */
    public FrozenKnowledgeRequest {
        Objects.requireNonNull(target); Objects.requireNonNull(request);
        var sources = target.sourceVersions().stream().map(KnowledgeSourceVersion::sourceId).collect(Collectors.toUnmodifiableSet());
        if (request.mode() == KnowledgeMode.NONE || !sources.containsAll(request.allowedSourceIds())
                || !target.sourceVersions().containsAll(request.allowedSourceVersions())
                || !target.allowedOperators().containsAll(request.allowedOperators())
                || Duration.ofMillis(request.budget().timeoutMs()).compareTo(target.timeout()) > 0) throw invalid();
    }

    /** 复用唯一显式字段codec；先整体计量再分配规范树和排序副本。 */
    public TypedPayload encode(CodecLimits limits) {
        var budget = new OutcomeEncodingBudget(Objects.requireNonNull(limits));
        budget.object(1, "target", "request");
        KnowledgeRequestCodec.preflightTarget(budget, 2, target);
        KnowledgeRequestCodec.preflightRequest(budget, 2, request);
        return new TypedPayload(SCHEMA, CanonicalJson.encode(CanonicalJson.object(List.of(
                new CanonicalJson.Field("target", KnowledgeRequestCodec.target(target)),
                new CanonicalJson.Field("request", KnowledgeRequestCodec.request(request))))));
    }

    /** 所有Typed字段和集合规范顺序严格恢复，未知字段或另一schema明确拒绝。 */
    public static FrozenKnowledgeRequest decode(TypedPayload payload, CodecLimits limits) {
        if (payload == null || !SCHEMA.equals(payload.schemaId())) throw invalid();
        var value = CanonicalJson.parse(payload.canonicalJson(), limits).requireFields("target", "request");
        var result = new FrozenKnowledgeRequest(KnowledgeRequestCodec.readTarget(value.field("target")), KnowledgeRequestCodec.readRequest(value.field("request")));
        if (!result.encode(limits).equals(payload)) throw invalid();
        return result;
    }

    /** 原结果必须闭合到同一请求、实际来源版本、算子、计划与实际预算。 */
    public void requireResult(EvidenceBundle bundle) { KnowledgeResultConstraints.requireBundle(request, Objects.requireNonNull(bundle)); }

    @Override public String toString() { return "FrozenKnowledgeRequest[REDACTED]"; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_knowledge_request_invalid"); }
}
