package com.skillforge.runtime.api;

import java.util.Optional;

/**
 * 携带只读 façade 调用所需的完整租户、主体、请求和 W3C trace 身份。
 */
public record AccessEnvelope(
        AuthenticationRef authenticationRef,
        TenantId tenantId,
        SessionTarget sessionTarget,
        Optional<AgentId> agentId,
        SessionSubject subject,
        RequestId requestId,
        String channel,
        String source,
        String traceId,
        String spanId) {

    /** 在执行授权前拒绝任何缺失身份或不完整 trace。 */
    public AccessEnvelope {
        authenticationRef = ApiContracts.requireNonNull(authenticationRef, "authenticationRef");
        tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
        sessionTarget = ApiContracts.requireNonNull(sessionTarget, "sessionTarget");
        agentId = ApiContracts.requireNonNull(agentId, "agentId");
        if (sessionTarget.kind() == SessionTarget.Kind.AGENT
                && (agentId.isEmpty() || !sessionTarget.id().equals(agentId.orElseThrow().value()))) {
            throw new IllegalArgumentException("execution agent must match a direct Agent session target");
        }
        subject = ApiContracts.requireNonNull(subject, "subject");
        if (subject instanceof SessionSubject.User && (sessionTarget.kind() == SessionTarget.Kind.GROUP || agentId.isEmpty()))
            throw new IllegalArgumentException("personal access requires an actual Agent and AGENT or TEAM target");
        if (subject instanceof SessionSubject.Group group && (sessionTarget.kind() != SessionTarget.Kind.GROUP
                || !group.groupBindingRef().tenantId().equals(tenantId)
                || !group.groupBindingRef().groupBindingId().equals(sessionTarget.id())))
            throw new IllegalArgumentException("group access must reference the same tenant and GROUP target");
        requestId = ApiContracts.requireNonNull(requestId, "requestId");
        channel = ApiContracts.requireText(channel, "channel");
        source = ApiContracts.requireText(source, "source");
        traceId = ApiContracts.requireText(traceId, "traceId");
        spanId = ApiContracts.requireText(spanId, "spanId");
    }

}
