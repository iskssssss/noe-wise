package com.skillforge.runtime.adapter.jdbc.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 墓碑核全Append命令；fixture不连接数据库，不替代事务/锁/FK验证。 */
class SessionHistoryTombstoneTest {
    @Test void v2FullEnvelopeHasAnIndependentUtf8LengthPrefixedGolden() {
        // 独立.NET UTF8+网络序长度前缀计算：25字段共298字节；相同固定向量也在SQL请求验收。
        assertEquals("974ab814c641ec95106623832b9bc514e7c290a41475937e131c8e540250f4d1",
                SessionHistoryDigest.digest(command(),17));
        assertThrows(IllegalArgumentException.class,()->SessionHistoryDigest.digest(command(),0));
    }
    @Test void unchangedOriginalRequestReplaysExactSequenceButEveryChangedEnvelopeFieldConflicts() {
        var original = command();
        var tombstone = new SessionEventTombstoneRow();
        tombstone.eventId = original.event().eventId().value(); tombstone.sessionSequence = 17;
        tombstone.eventDigest = SessionEventTombstoneRow.digest(original,17);
        assertTrue(tombstone.matches(original));
        assertEquals(SessionEventRow.from(original,17).toCommittedEvent(),SessionEventRow.from(original,tombstone.sessionSequence).toCommittedEvent());
        var event = original.event();
        var changedEvents = List.of(
                new SessionEventDraft(new EventId("other"),event.eventType(),event.eventVersion(),event.eventFingerprint(),event.trace(),event.payload(),event.occurredAt()),
                new SessionEventDraft(event.eventId(),"other.type",event.eventVersion(),event.eventFingerprint(),event.trace(),event.payload(),event.occurredAt()),
                new SessionEventDraft(event.eventId(),event.eventType(),"3",event.eventFingerprint(),event.trace(),event.payload(),event.occurredAt()),
                new SessionEventDraft(event.eventId(),event.eventType(),event.eventVersion(),"changed",event.trace(),event.payload(),event.occurredAt()),
                new SessionEventDraft(event.eventId(),event.eventType(),event.eventVersion(),event.eventFingerprint(),new TraceEnvelope("other","span",new InputId("root"),Optional.of(new EventId("cause"))),event.payload(),event.occurredAt()),
                new SessionEventDraft(event.eventId(),event.eventType(),event.eventVersion(),event.eventFingerprint(),event.trace(),new TypedPayload("other.v1",event.payload().canonicalJson()),event.occurredAt()),
                new SessionEventDraft(event.eventId(),event.eventType(),event.eventVersion(),event.eventFingerprint(),event.trace(),new TypedPayload(event.payload().schemaId(),"{\"text\":\"different\"}"),event.occurredAt()),
                new SessionEventDraft(event.eventId(),event.eventType(),event.eventVersion(),event.eventFingerprint(),event.trace(),event.payload(),event.occurredAt().plusNanos(1)));
        for (var changed : changedEvents) assertFalse(tombstone.matches(new AppendSessionEvent(original.target(),changed)));
        var target = original.target(); var tenant = target.owner().sessionRef().tenantId();
        var changedTargets = List.of(
                new SessionProjectionTarget(SessionOwner.user(new SessionRef(tenant,"other-session"),new AgentId("a"),new EndUserId(1)),target.agentId(),target.inputId(),target.runRef(),target.executionRef(),target.actionRef(),target.delegationRef()),
                new SessionProjectionTarget(target.owner(),new AgentId("other-agent"),target.inputId(),target.runRef(),target.executionRef(),target.actionRef(),target.delegationRef()),
                new SessionProjectionTarget(target.owner(),target.agentId(),new InputId("other-input"),target.runRef(),target.executionRef(),target.actionRef(),target.delegationRef()),
                new SessionProjectionTarget(target.owner(),target.agentId(),target.inputId(),Optional.of(new RunRef(tenant,"other-run")),target.executionRef(),target.actionRef(),target.delegationRef()),
                new SessionProjectionTarget(target.owner(),target.agentId(),target.inputId(),target.runRef(),Optional.empty(),target.actionRef(),target.delegationRef()),
                new SessionProjectionTarget(target.owner(),target.agentId(),target.inputId(),target.runRef(),target.executionRef(),Optional.empty(),target.delegationRef()),
                new SessionProjectionTarget(target.owner(),target.agentId(),target.inputId(),target.runRef(),target.executionRef(),target.actionRef(),Optional.empty()));
        for (var changed : changedTargets) assertFalse(tombstone.matches(new AppendSessionEvent(changed,event)));
        var otherOwner = SessionOwner.user(target.owner().sessionRef(),target.owner().target(),new EndUserId(2));
        assertFalse(tombstone.matches(new AppendSessionEvent(new SessionProjectionTarget(otherOwner,target.agentId(),target.inputId(),
                target.runRef(),target.executionRef(),target.actionRef(),target.delegationRef()),event)));
        assertFalse(tombstone.matches(new AppendSessionEvent(new SessionProjectionTarget(SessionOwner.user(target.owner().sessionRef(),
                new SessionTarget(SessionTarget.Kind.TEAM,"team"),target.owner().requireUser()),target.agentId(),target.inputId(),
                target.runRef(),target.executionRef(),target.actionRef(),target.delegationRef()),event)));
        tombstone.sessionSequence++;
        assertFalse(tombstone.matches(original));
    }
    private static AppendSessionEvent command() {
        var tenant = new TenantId("t");
        var owner = SessionOwner.user(new SessionRef(tenant,"s"),new AgentId("a"),new EndUserId(1));
        var target = new SessionProjectionTarget(owner,new InputId("input"),Optional.of(new RunRef(tenant,"run")),
                Optional.of(new ExecutionRef(tenant,"execution")),Optional.of(new ActionRef(tenant,"action")),Optional.of(new DelegationRef(tenant,"delegation")));
        return new AppendSessionEvent(target,new SessionEventDraft(new EventId("event"),"agent.model-reasoning","1","fingerprint",
                new TraceEnvelope("trace","span",new InputId("root"),Optional.of(new EventId("cause"))),
                new TypedPayload("reasoning.v1","{\"text\":\"原文😀\"}"),Instant.ofEpochSecond(1780000000,123456789)));
    }
}
