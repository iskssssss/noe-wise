package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.RunRequester;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.SourceMessageRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.SessionSubject;
import java.util.Optional;

/** 固定 Run 的归属、因果输入、运行身份、trace 与原调用方身份引用。 */
public record RunIdentity(
        SessionOwner owner,
        AgentId agentId,
        InputId causationInputId,
        RunRef runRef,
        TraceEnvelope trace,
        ResultRef causationIdentityRef,
        RunRequester requester,
        Optional<SourceMessageRef> sourceMessageRef,
        RunOriginRef originRef) {
    /** 拒绝不完整身份与跨租户 Run。 */
    public RunIdentity {
        owner = PortContracts.requireNonNull(owner, "owner");
        agentId = PortContracts.requireNonNull(agentId, "agentId");
        if (owner.target().kind() == SessionTarget.Kind.AGENT
                && !owner.target().id().equals(agentId.value())) {
            throw new IllegalArgumentException("execution agent must match a direct Agent session target");
        }
        causationInputId = PortContracts.requireNonNull(causationInputId, "causationInputId");
        runRef = PortContracts.requireNonNull(runRef, "runRef");
        trace = PortContracts.requireNonNull(trace, "trace");
        causationIdentityRef = PortContracts.requireNonNull(causationIdentityRef, "causationIdentityRef");
        PortContracts.requireSameTenant(owner.sessionRef().tenantId(), runRef.tenantId(), "runRef.tenantId");
        requester = PortContracts.requireNonNull(requester, "requester");
        sourceMessageRef = PortContracts.requireNonNull(sourceMessageRef, "sourceMessageRef");
        originRef = PortContracts.requireNonNull(originRef, "originRef");
        PortContracts.requireSameTenant(owner.sessionRef().tenantId(), originRef.tenantId(), "originRef.tenantId");
        if (sourceMessageRef.isPresent() && (!owner.sessionRef().tenantId().equals(sourceMessageRef.orElseThrow().sessionRef().tenantId())
                || owner.subject() instanceof SessionSubject.User && !owner.sessionRef().equals(sourceMessageRef.orElseThrow().sessionRef())))
            throw new IllegalArgumentException("source message must belong to the original Session");
        if (requester instanceof RunRequester.System system)
            PortContracts.requireSameTenant(owner.sessionRef().tenantId(), system.principal().tenantId(), "requester.tenantId");
        if (owner.subject() instanceof SessionSubject.User user && requester instanceof RunRequester.EndUser endUser
                && !user.endUserId().equals(endUser.endUserId()))
            throw new IllegalArgumentException("personal Run requester must match Session user");
    }

}
