package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgeRequest;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.ResultRef;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/** 隔离 Core 与 Knowledge provider，并只执行已冻结来源清单内的检索计划。 */
public interface KnowledgePortal {

    /** 执行一个已通过 Core 决策且收窄到冻结来源的知识请求。 */
    KnowledgeOutcome execute(KnowledgeCall call);

    /** 表示 `knowledge:<mountId>` 格式的知识挂载句柄。 */
    record KnowledgeHandle(String value) {

        /** 拒绝非 Knowledge 前缀或缺失 mountId 的句柄。 */
        public KnowledgeHandle {
            value = requireHandle(value);
        }
    }

    /** 固定知识挂载、descriptor 版本、来源版本清单、算子与超时。 */
    record FrozenKnowledgeTarget(
            KnowledgeHandle handle,
            CapabilityRef capability,
            String descriptorId,
            long descriptorRevision,
            Set<KnowledgeSourceVersion> sourceVersions,
            Set<KnowledgeOperatorType> allowedOperators,
            Duration timeout) {

        /** 拒绝错类型能力、缺失清单、不完整 descriptor 或非正超时。 */
        public FrozenKnowledgeTarget {
            handle = PortContracts.requireNonNull(handle, "handle");
            capability = requireCapability(capability);
            descriptorId = PortContracts.requireText(descriptorId, "descriptorId");
            descriptorRevision = PortContracts.requirePositive(descriptorRevision, "descriptorRevision");
            sourceVersions = PortContracts.immutableSet(sourceVersions, "sourceVersions");
            allowedOperators = PortContracts.immutableSet(allowedOperators, "allowedOperators");
            timeout = PortContracts.requirePositive(timeout, "timeout");
        }
    }

    /** 描述一次绑定当前快照、身份、因果事件与稳定幂等键的知识调用。 */
    record KnowledgeCall(
            ProviderCallContext context,
            CapabilitySnapshotRef snapshot,
            FrozenKnowledgeTarget target,
            KnowledgeRequest request,
            EventId sourceEventId,
            IdempotencyKey idempotencyKey) {

        /** 拒绝身份/trace/快照漂移、NONE 模式和扩大冻结目标的请求。 */
        public KnowledgeCall {
            context = PortContracts.requireNonNull(context, "context");
            snapshot = PortContracts.requireNonNull(snapshot, "snapshot");
            target = PortContracts.requireNonNull(target, "target");
            request = PortContracts.requireNonNull(request, "request");
            sourceEventId = PortContracts.requireNonNull(sourceEventId, "sourceEventId");
            idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
            requireCurrentSnapshot(context, snapshot);
            requireCurrentIdentity(context, request);
            requireNarrowedTarget(target, request);
            if (request.mode() == KnowledgeMode.NONE) {
                throw new IllegalArgumentException("Knowledge NONE must not call the portal");
            }
        }
    }

    /** 表达已检索、确定失败或结果未知三种知识调用结果。 */
    sealed interface KnowledgeOutcome
            permits KnowledgeOutcome.Retrieved, KnowledgeOutcome.Failed,
                    KnowledgeOutcome.OutcomeUnknown {

        /** 返回完整 EvidenceBundle 与已提交结果引用。 */
        record Retrieved(EvidenceBundle bundle, ResultRef resultRef) implements KnowledgeOutcome {

            /** 拒绝缺失证据包或结果引用。 */
            public Retrieved {
                bundle = PortContracts.requireNonNull(bundle, "bundle");
                resultRef = PortContracts.requireNonNull(resultRef, "resultRef");
            }
        }

        /** 表示知识调用已确定失败。 */
        record Failed(PortFailure failure) implements KnowledgeOutcome {

            /** 拒绝缺失的稳定失败事实。 */
            public Failed {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 表示结果未知；Knowledge 没有查询/协调入口，因此只能停止自动重试。 */
        record OutcomeUnknown(PortFailure failure) implements KnowledgeOutcome {

            /** 拒绝 Knowledge 无法承接的结果查询或协调模式。 */
            public OutcomeUnknown {
                failure = requireRetryMode(failure);
            }
        }
    }

    /** 校验 Knowledge 挂载只引用 KNOWLEDGE_SOURCE 类型能力。 */
    private static CapabilityRef requireCapability(CapabilityRef capability) {
        capability = PortContracts.requireNonNull(capability, "capability");
        if (capability.type() != CapabilityType.KNOWLEDGE_SOURCE) {
            throw new IllegalArgumentException("capability type must be KNOWLEDGE_SOURCE");
        }
        return capability;
    }

    /** 校验挂载句柄有精确前缀和非空白 mountId。 */
    private static String requireHandle(String value) {
        value = PortContracts.requireText(value, "value");
        String prefix = "knowledge:";
        if (!value.startsWith(prefix) || value.length() == prefix.length()
                || value.substring(prefix.length()).isBlank()) {
            throw new IllegalArgumentException("handle must use knowledge:<mountId>");
        }
        return value;
    }

    /** 校验能力快照属于当前 Session 与当前因果输入。 */
    private static void requireCurrentSnapshot(
            ProviderCallContext context,
            CapabilitySnapshotRef snapshot) {
        PortCallContext callContext = context.callContext();
        if (!snapshot.sessionRef().equals(callContext.runIdentity().owner().sessionRef())
                || !snapshot.inputId().equals(callContext.runIdentity().causationInputId())) {
            throw new IllegalArgumentException("snapshot must match current session and causation input");
        }
    }

    /** 校验 KnowledgeRequest 的 owner 与 trace 完全来自调用上下文。 */
    private static void requireCurrentIdentity(
            ProviderCallContext context,
            KnowledgeRequest request) {
        PortCallContext callContext = context.callContext();
        SessionOwner owner = callContext.runIdentity().owner();
        if (!request.tenantId().equals(owner.sessionRef().tenantId())
                || !request.requester().equals(callContext.runIdentity().requester())
                || !request.agentId().equals(callContext.runIdentity().agentId())
                || !request.sessionRef().equals(owner.sessionRef())
                || !request.trace().equals(callContext.runIdentity().trace())) {
            throw new IllegalArgumentException("knowledge request identity and trace must match call context");
        }
    }

    /** 校验请求来源、版本、算子和毫秒超时未扩大冻结目标。 */
    private static void requireNarrowedTarget(FrozenKnowledgeTarget target, KnowledgeRequest request) {
        Set<String> frozenSourceIds = target.sourceVersions().stream()
                .map(KnowledgeSourceVersion::sourceId)
                .collect(Collectors.toUnmodifiableSet());
        if (!frozenSourceIds.containsAll(request.allowedSourceIds())
                || !target.sourceVersions().containsAll(request.allowedSourceVersions())
                || !target.allowedOperators().containsAll(request.allowedOperators())
                || Duration.ofMillis(request.budget().timeoutMs()).compareTo(target.timeout()) > 0) {
            throw new IllegalArgumentException("knowledge request must narrow the frozen target");
        }
    }

    /** 校验知识未知结果只声明 Portal 能安全承接的重试方式。 */
    private static PortFailure requireRetryMode(PortFailure failure) {
        failure = PortContracts.requireNonNull(failure, "failure");
        if (failure.retryMode() != RetryMode.NONE) {
            throw new IllegalArgumentException("Knowledge OutcomeUnknown retry mode is not supported");
        }
        return failure;
    }
}
