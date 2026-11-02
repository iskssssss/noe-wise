package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.RunRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 跨会话的认知任务；业务执行事实仍由工具或 Workflow 账本拥有。 */
public record ConversationTask(
        String taskId, TenantId tenantId, EndUserId endUserId, AgentId agentId,
        long revision, Status status, String title, String summary,
        TypedPayload state, List<Source> sources, List<Maintenance> maintenance, Instant updatedAt) {
    public static final String STATE_SCHEMA = "skillforge.conversation-task.state.v1";

    public ConversationTask {
        requireText(taskId); Objects.requireNonNull(tenantId); Objects.requireNonNull(endUserId);
        Objects.requireNonNull(agentId); Objects.requireNonNull(status); requireText(title);
        Objects.requireNonNull(summary); Objects.requireNonNull(state); Objects.requireNonNull(updatedAt);
        sources = List.copyOf(sources);
        maintenance = List.copyOf(maintenance);
        var sessions = new java.util.HashSet<com.skillforge.runtime.api.SessionRef>();
        for (var cursor : maintenance) {
            if (!taskId.equals(cursor.taskId()) || !tenantId.equals(cursor.owner().sessionRef().tenantId())
                    || !agentId.equals(cursor.agentId()) || !endUserId.equals(cursor.owner().requireUser())
                    || cursor.expectedRevision() >= revision || !sessions.add(cursor.owner().sessionRef())) {
                throw new IllegalArgumentException("task_maintenance_owner_or_revision_invalid");
            }
        }
        if (revision < 1 || title.length() > 200 || summary.length() > 8000
                || !STATE_SCHEMA.equals(state.schemaId()) || state.canonicalJson().length() > 65536
                || sources.isEmpty() || sources.size() > 256) throw new IllegalArgumentException("task_invalid");
        for (var source : sources) {
            if (!tenantId.equals(source.owner().sessionRef().tenantId())
                    || !endUserId.equals(source.owner().requireUser()) || !agentId.equals(source.agentId()))
                throw new IllegalArgumentException("task_source_owner_mismatch");
        }
    }

    public boolean belongsTo(SessionOwner owner, AgentId actualAgentId) {
        return tenantId.equals(owner.sessionRef().tenantId()) && endUserId.equals(owner.requireUser())
                && agentId.equals(actualAgentId);
    }

    public boolean belongsTo(RunIdentity identity) {
        return belongsTo(identity.owner(), identity.agentId());
    }

    /** 仅供直连 Agent 消费者使用；团队调用必须显式提供实际执行 Agent。 */
    public boolean belongsTo(SessionOwner owner) {
        return belongsTo(owner, owner.target().requireAgent());
    }

    public enum Status { OPEN, WAITING_USER, COMPLETED, CANCELLED }

    public enum MaintenancePhase { ENTRY, EXIT, SUSPEND, RESUME }

    /** 每个来源会话保留已确认游标；与完整任务快照同事务提交，不由模型写入。 */
    public record Maintenance(SessionOwner owner, AgentId agentId, String taskId, RunRef runRef, String batchId,
                              MaintenancePhase phase, long fromExclusiveSequence, long throughSequence,
                              long expectedRevision, List<EventId> sourceEventIds) {
        public Maintenance {
            Objects.requireNonNull(owner); Objects.requireNonNull(agentId);
            requireText(taskId); Objects.requireNonNull(runRef);
            requireText(batchId); Objects.requireNonNull(phase); sourceEventIds = List.copyOf(sourceEventIds);
            if (!owner.sessionRef().tenantId().equals(runRef.tenantId()) || fromExclusiveSequence < 0
                    || throughSequence < fromExclusiveSequence || expectedRevision < 0
                    || new java.util.HashSet<>(sourceEventIds).size() != sourceEventIds.size()) {
                throw new IllegalArgumentException("task_maintenance_invalid");
            }
        }
        public Maintenance(SessionOwner owner, String taskId, RunRef runRef, String batchId,
                MaintenancePhase phase, long fromExclusiveSequence, long throughSequence,
                long expectedRevision, List<EventId> sourceEventIds) {
            this(owner, owner.target().requireAgent(), taskId, runRef, batchId, phase,
                    fromExclusiveSequence, throughSequence, expectedRevision, sourceEventIds);
        }
        @Override public String toString() { return "TaskMaintenance[REDACTED]"; }
    }

    /** 来源保留原会话身份；可访问任务不等于可读取原会话全文。 */
    public record Source(SessionOwner owner, AgentId agentId, EventId eventId) {
        public Source { Objects.requireNonNull(owner); Objects.requireNonNull(agentId); Objects.requireNonNull(eventId); }
        public Source(SessionOwner owner, EventId eventId) {
            this(owner, owner.target().requireAgent(), eventId);
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("task_text_required");
    }
    @Override public String toString() { return "ConversationTask[REDACTED]"; }
}
