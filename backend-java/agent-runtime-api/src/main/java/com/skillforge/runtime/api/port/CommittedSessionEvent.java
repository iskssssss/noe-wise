package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.time.Instant;
import java.util.Optional;

/** 表示已由 SessionLog 分配正 sequence 的不可变会话事件。 */
public record CommittedSessionEvent(
        SessionOwner owner,
        AgentId agentId,
        InputId inputId,
        Optional<RunRef> runRef,
        Optional<ExecutionRef> executionRef,
        Optional<ActionRef> actionRef,
        Optional<DelegationRef> delegationRef,
        EventId eventId,
        long sessionSequence,
        String eventType,
        String eventVersion,
        TraceEnvelope trace,
        TypedPayload payload,
        Instant occurredAt) {
    /** 复用投影目标校验并拒绝未分配 sequence 或不完整事件。 */
    public CommittedSessionEvent {
        SessionProjectionTarget target = new SessionProjectionTarget(
                owner, agentId, inputId, runRef, executionRef, actionRef, delegationRef);
        owner = target.owner();
        agentId = target.agentId();
        inputId = target.inputId();
        runRef = target.runRef();
        executionRef = target.executionRef();
        actionRef = target.actionRef();
        delegationRef = target.delegationRef();
        eventId = PortContracts.requireNonNull(eventId, "eventId");
        sessionSequence = PortContracts.requirePositive(sessionSequence, "sessionSequence");
        eventType = PortContracts.requireText(eventType, "eventType");
        eventVersion = PortContracts.requireText(eventVersion, "eventVersion");
        trace = PortContracts.requireNonNull(trace, "trace");
        payload = PortContracts.requireNonNull(payload, "payload");
        occurredAt = PortContracts.requireNonNull(occurredAt, "occurredAt");
    }

    /** 直连 Agent 事件的快捷入口；团队 Session 事件必须显式提供实际 Agent。 */
    public CommittedSessionEvent(SessionOwner owner, InputId inputId, Optional<RunRef> runRef,
            Optional<ExecutionRef> executionRef, Optional<ActionRef> actionRef,
            Optional<DelegationRef> delegationRef, EventId eventId, long sessionSequence,
            String eventType, String eventVersion, TraceEnvelope trace, TypedPayload payload,
            Instant occurredAt) {
        this(owner, owner.target().requireAgent(), inputId, runRef, executionRef, actionRef,
                delegationRef, eventId, sessionSequence, eventType, eventVersion, trace, payload,
                occurredAt);
    }
}
