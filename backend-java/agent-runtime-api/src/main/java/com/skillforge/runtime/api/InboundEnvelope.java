package com.skillforge.runtime.api;

import java.time.Instant;
import java.util.Optional;

/**
 * 统一承载所有状态变更输入，并固定其身份、幂等指纹、目标运行与 trace。
 */
public record InboundEnvelope(
        AuthenticationRef authenticationRef,
        Optional<ResultRef> causationIdentityRef,
        SessionRef sessionRef,
        SessionTarget sessionTarget,
        AgentId agentId,
        SessionSubject subject,
        RunRequester requester,
        InputId inputId,
        EventId eventId,
        InboundKind kind,
        RunTarget runTarget,
        String channel,
        String source,
        TraceEnvelope trace,
        TypedPayload payload,
        Instant receivedAt,
        Optional<SourceMessageRef> sourceMessageRef,
        RunOriginRef originRef) {

    /** 在进入 Inbox 和幂等账本前拒绝缺失身份、指纹字段或跨租户既有运行。 */
    public InboundEnvelope {
        authenticationRef = ApiContracts.requireNonNull(authenticationRef, "authenticationRef");
        causationIdentityRef = ApiContracts.normalize(causationIdentityRef);
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        sessionTarget = ApiContracts.requireNonNull(sessionTarget, "sessionTarget");
        agentId = ApiContracts.requireNonNull(agentId, "agentId");
        subject = ApiContracts.requireNonNull(subject, "subject");
        requester = ApiContracts.requireNonNull(requester, "requester");
        if (subject instanceof SessionSubject.User && sessionTarget.kind() == SessionTarget.Kind.GROUP)
            throw new IllegalArgumentException("personal subject requires an AGENT or TEAM target");
        if (subject instanceof SessionSubject.Group group && (sessionTarget.kind() != SessionTarget.Kind.GROUP
                || !group.groupBindingRef().tenantId().equals(sessionRef.tenantId())
                || !group.groupBindingRef().groupBindingId().equals(sessionTarget.id())))
            throw new IllegalArgumentException("group subject and target must reference the exact Session tenant and binding");
        sourceMessageRef = ApiContracts.requireNonNull(sourceMessageRef, "sourceMessageRef");
        originRef = ApiContracts.requireNonNull(originRef, "originRef");
        if (!originRef.tenantId().equals(sessionRef.tenantId()))
            throw new IllegalArgumentException("origin must belong to the Session tenant");
        if (sourceMessageRef.isPresent() && (!sourceMessageRef.orElseThrow().sessionRef().tenantId().equals(sessionRef.tenantId())
                || subject instanceof SessionSubject.User && !sourceMessageRef.orElseThrow().sessionRef().equals(sessionRef)))
            throw new IllegalArgumentException("source message must belong to the Session");
        if (requester instanceof RunRequester.System system && !system.principal().tenantId().equals(sessionRef.tenantId()))
            throw new IllegalArgumentException("system requester must belong to the Session tenant");
        if (subject instanceof SessionSubject.User user && requester instanceof RunRequester.EndUser endUser
                && !user.endUserId().equals(endUser.endUserId()))
            throw new IllegalArgumentException("personal requester must match Session user");
        if (sessionTarget.kind() == SessionTarget.Kind.AGENT && !sessionTarget.id().equals(agentId.value()))
            throw new IllegalArgumentException("execution agent must match direct Agent target");
        inputId = ApiContracts.requireNonNull(inputId, "inputId");
        eventId = ApiContracts.requireNonNull(eventId, "eventId");
        kind = ApiContracts.requireNonNull(kind, "kind");
        runTarget = ApiContracts.requireNonNull(runTarget, "runTarget");
        channel = ApiContracts.requireText(channel, "channel");
        source = ApiContracts.requireText(source, "source");
        trace = ApiContracts.requireNonNull(trace, "trace");
        payload = ApiContracts.requireNonNull(payload, "payload");
        receivedAt = ApiContracts.requireNonNull(receivedAt, "receivedAt");

        // 既有运行输入必须在 envelope 构造时封闭跨租户目标。
        if (runTarget instanceof RunTarget.ExistingRun existingRun) {
            ApiContracts.requireSameTenant(sessionRef, existingRun.runRef());
        }
    }

}
