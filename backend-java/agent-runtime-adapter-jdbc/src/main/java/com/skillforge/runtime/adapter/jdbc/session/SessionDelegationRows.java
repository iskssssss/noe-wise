package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.spi.SessionDelegations.Entry;
import com.skillforge.runtime.session.spi.SessionDelegations.Wait;

/** 委派 JSONB 快照旁的固定身份、检索和 CAS 列映射。 */
final class SessionDelegationEntryRow extends SessionDelegationParentRow {
    public String delegationId;
    public String operationKey;
    public String childRunId;
    public String childInputId;
    public String previousDelegationId;
    public String state;
    public String cancelKey;
    public long revision;
    public String snapshot;
    public long createdEpochSecond;
    public int createdNano;
    public long updatedEpochSecond;
    public int updatedNano;

    SessionDelegationEntryRow() {}

    static SessionDelegationEntryRow from(Entry entry, String snapshot) {
        var row = new SessionDelegationEntryRow();
        row.setParent(entry.parent());
        row.delegationId = entry.id();
        row.operationKey = entry.operationKey();
        row.childRunId = entry.childRun().runId();
        row.childInputId = entry.childInput().value();
        row.previousDelegationId = entry.previousId().orElse(null);
        row.state = entry.state().name();
        row.cancelKey = entry.cancelKey().orElse(null);
        row.revision = entry.revision();
        row.snapshot = snapshot;
        row.createdEpochSecond = entry.createdAt().getEpochSecond();
        row.createdNano = entry.createdAt().getNano();
        row.updatedEpochSecond = entry.updatedAt().getEpochSecond();
        row.updatedNano = entry.updatedAt().getNano();
        return row;
    }

    Entry restore(JdbcSessionDelegations.Codec codec) {
        Entry entry = codec.decodeEntry(snapshot);
        if (!matches(entry.parent())
                || !delegationId.equals(entry.id())
                || !operationKey.equals(entry.operationKey())
                || !tenantId.equals(entry.childRun().tenantId().value())
                || !childRunId.equals(entry.childRun().runId())
                || !childInputId.equals(entry.childInput().value())
                || !java.util.Objects.equals(previousDelegationId,
                        entry.previousId().orElse(null))
                || !state.equals(entry.state().name())
                || !java.util.Objects.equals(cancelKey,
                        entry.cancelKey().orElse(null))
                || revision != entry.revision()
                || createdEpochSecond != entry.createdAt().getEpochSecond()
                || createdNano != entry.createdAt().getNano()
                || updatedEpochSecond != entry.updatedAt().getEpochSecond()
                || updatedNano != entry.updatedAt().getNano()) {
            throw invalid();
        }
        return entry;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("session_delegation_entry_row_invalid");
    }
}

final class SessionDelegationWaitRow extends SessionDelegationParentRow {
    public String waitId;
    public boolean delivered;
    public long revision;
    public String snapshot;

    SessionDelegationWaitRow() {}

    static SessionDelegationWaitRow from(Wait wait, String snapshot) {
        var row = new SessionDelegationWaitRow();
        row.setParent(wait.parent());
        row.waitId = wait.id();
        row.delivered = wait.delivered();
        row.revision = wait.revision();
        row.snapshot = snapshot;
        return row;
    }

    Wait restore(JdbcSessionDelegations.Codec codec) {
        Wait wait = codec.decodeWait(snapshot);
        if (!matches(wait.parent()) || !waitId.equals(wait.id())
                || delivered != wait.delivered()
                || revision != wait.revision()) {
            throw new IllegalStateException(
                    "session_delegation_wait_row_invalid");
        }
        return wait;
    }


}

class SessionDelegationParentRow {
    public String tenantId;
    public String parentSessionId;
    public String parentTargetKind;
    public String parentTargetId;
    public Long parentEndUserId;
    public String parentRequesterKind,parentRequesterSystemPrincipalId,parentSourceSessionId,parentSourceMessageId,parentOriginRef;
    public Long parentRequesterEndUserId;
    public String parentAgentId;
    public String parentCausationInputId;
    public String parentRunId;
    public String parentTraceId;
    public String parentSpanId;
    public String parentRootInputId;
    public String parentCausedByEventId;
    public String parentCausationIdentityRef;

    SessionDelegationParentRow() {}

    static SessionDelegationParentRow from(RunIdentity parent) {
        var row = new SessionDelegationParentRow();
        row.setParent(parent);
        return row;
    }

    final void setParent(RunIdentity parent) {
        tenantId = parent.runRef().tenantId().value();
        parentSessionId = parent.owner().sessionRef().sessionId();
        parentTargetKind = parent.owner().target().kind().name();
        parentTargetId = parent.owner().target().id();
        parentEndUserId = JdbcSessionLogPort.userId(parent.owner());
        parentRequesterKind=parent.requester() instanceof com.skillforge.runtime.api.RunRequester.EndUser?"END_USER":"SYSTEM";
        parentRequesterEndUserId=parent.requester() instanceof com.skillforge.runtime.api.RunRequester.EndUser user?user.endUserId().value():null;
        parentRequesterSystemPrincipalId=parent.requester() instanceof com.skillforge.runtime.api.RunRequester.System system?system.principal().principalId():null;
        parentSourceSessionId=parent.sourceMessageRef().map(value->value.sessionRef().sessionId()).orElse(null);
        parentSourceMessageId=parent.sourceMessageRef().map(com.skillforge.runtime.api.SourceMessageRef::messageId).orElse(null);
        parentOriginRef=parent.originRef().originId();
        parentAgentId = parent.agentId().value();
        parentCausationInputId = parent.causationInputId().value();
        parentRunId = parent.runRef().runId();
        parentTraceId = parent.trace().traceId();
        parentSpanId = parent.trace().spanId();
        parentRootInputId = parent.trace().rootInputId().value();
        parentCausedByEventId = parent.trace().causedByEventId()
                .map(value -> value.value()).orElse(null);
        parentCausationIdentityRef = parent.causationIdentityRef().value();
    }

    final boolean matches(RunIdentity parent) {
        return tenantId.equals(parent.runRef().tenantId().value())
                && tenantId.equals(parent.owner().sessionRef().tenantId().value())
                && parentSessionId.equals(parent.owner().sessionRef().sessionId())
                && parentTargetKind.equals(parent.owner().target().kind().name())
                && parentTargetId.equals(parent.owner().target().id())
                && java.util.Objects.equals(parentEndUserId,JdbcSessionLogPort.userId(parent.owner()))
                && java.util.Objects.equals(parentRequesterEndUserId,parent.requester() instanceof com.skillforge.runtime.api.RunRequester.EndUser user?user.endUserId().value():null)
                && java.util.Objects.equals(parentRequesterSystemPrincipalId,parent.requester() instanceof com.skillforge.runtime.api.RunRequester.System system?system.principal().principalId():null)
                && parentRequesterKind.equals(parent.requester() instanceof com.skillforge.runtime.api.RunRequester.EndUser?"END_USER":"SYSTEM")
                && java.util.Objects.equals(parentSourceSessionId,parent.sourceMessageRef().map(value->value.sessionRef().sessionId()).orElse(null))
                && java.util.Objects.equals(parentSourceMessageId,parent.sourceMessageRef().map(com.skillforge.runtime.api.SourceMessageRef::messageId).orElse(null))
                && parentOriginRef.equals(parent.originRef().originId())
                && parentAgentId.equals(parent.agentId().value())
                && parentCausationInputId.equals(
                        parent.causationInputId().value())
                && parentRunId.equals(parent.runRef().runId())
                && parentTraceId.equals(parent.trace().traceId())
                && parentSpanId.equals(parent.trace().spanId())
                && parentRootInputId.equals(parent.trace().rootInputId().value())
                && java.util.Objects.equals(parentCausedByEventId,
                        parent.trace().causedByEventId()
                                .map(value -> value.value()).orElse(null))
                && parentCausationIdentityRef.equals(
                        parent.causationIdentityRef().value());
    }
}
