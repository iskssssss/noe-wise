package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import java.util.Optional;

/** 固定后台工作或调度的归属、可选 Run、因果输入、trace 与原身份。 */
public record RuntimeWorkIdentity(
        SessionOwner owner,
        AgentId agentId,
        Optional<RunRef> owningRunRef,
        InputId causationInputId,
        TraceEnvelope trace,
        ResultRef causationIdentityRef) {
    /** 规范化可选 Run 并拒绝不完整或跨租户工作身份。 */
    public RuntimeWorkIdentity {
        owner = PortContracts.requireNonNull(owner, "owner");
        owner.requireUser();
        agentId = PortContracts.requireNonNull(agentId, "agentId");
        owningRunRef = PortContracts.requireNonNull(owningRunRef, "owningRunRef");
        causationInputId = PortContracts.requireNonNull(causationInputId, "causationInputId");
        trace = PortContracts.requireNonNull(trace, "trace");
        causationIdentityRef = PortContracts.requireNonNull(causationIdentityRef, "causationIdentityRef");
        TenantId ownerTenant = owner.sessionRef().tenantId();
        owningRunRef.ifPresent(ref -> PortContracts.requireSameTenant(
                ownerTenant, ref.tenantId(), "owningRunRef.tenantId"));
    }

    /** 直连 Agent 工作的快捷入口；团队工作必须显式提供实际执行 Agent。 */
    public RuntimeWorkIdentity(SessionOwner owner, Optional<RunRef> owningRunRef,
            InputId causationInputId, TraceEnvelope trace, ResultRef causationIdentityRef) {
        this(owner, owner.target().requireAgent(), owningRunRef, causationInputId, trace,
                causationIdentityRef);
    }
}
