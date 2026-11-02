package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import java.util.Optional;

/** 冻结领域事件投影到 SessionLog 所需的完整归属与关联引用。 */
public record SessionProjectionTarget(
        SessionOwner owner,
        AgentId agentId,
        InputId inputId,
        Optional<RunRef> runRef,
        Optional<ExecutionRef> executionRef,
        Optional<ActionRef> actionRef,
        Optional<DelegationRef> delegationRef) {
    /** 防御性规范化可选引用并拒绝跨租户关联。 */
    public SessionProjectionTarget {
        owner = PortContracts.requireNonNull(owner, "owner");
        agentId = PortContracts.requireNonNull(agentId, "agentId");
        inputId = PortContracts.requireNonNull(inputId, "inputId");
        runRef = PortContracts.requireNonNull(runRef, "runRef");
        executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
        actionRef = PortContracts.requireNonNull(actionRef, "actionRef");
        delegationRef = PortContracts.requireNonNull(delegationRef, "delegationRef");
        TenantId ownerTenant = owner.sessionRef().tenantId();
        runRef.ifPresent(ref -> requireTenant(ownerTenant, ref.tenantId(), "runRef.tenantId"));
        executionRef.ifPresent(ref -> requireTenant(ownerTenant, ref.tenantId(), "executionRef.tenantId"));
        actionRef.ifPresent(ref -> requireTenant(ownerTenant, ref.tenantId(), "actionRef.tenantId"));
        delegationRef.ifPresent(ref -> requireTenant(ownerTenant, ref.tenantId(), "delegationRef.tenantId"));
    }

    /** 直连 Agent 投影的快捷入口；团队 Session 投影必须显式提供实际 Agent。 */
    public SessionProjectionTarget(SessionOwner owner, InputId inputId, Optional<RunRef> runRef,
            Optional<ExecutionRef> executionRef, Optional<ActionRef> actionRef,
            Optional<DelegationRef> delegationRef) {
        this(owner, owner.target().requireAgent(), inputId, runRef, executionRef, actionRef,
                delegationRef);
    }

    /** 校验可选引用仍属于投影 Session 的租户。 */
    private static void requireTenant(
            TenantId ownerTenant, TenantId tenantId, String field) {
        PortContracts.requireSameTenant(ownerTenant, tenantId, field);
    }
}
