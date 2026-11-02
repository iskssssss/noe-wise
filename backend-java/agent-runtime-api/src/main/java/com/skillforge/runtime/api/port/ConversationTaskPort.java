package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.port.ConversationTask;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 任务投影与 SessionLog 原子提交端口；宿主仍须校验当前身份和业务写权。 */
public interface ConversationTaskPort {
    Optional<ConversationTask> find(SessionOwner owner, AgentId agentId, String taskId);
    List<ConversationTask> search(SessionOwner owner, AgentId agentId, String query,
                                  boolean includeClosed, int offset, int limit);
    Optional<String> current(SessionOwner owner, AgentId agentId);
    Result commit(Change change);

    /** 同一任务只能被一个 Run 推进；租约过期后 fencing token 必须递增。 */
    Lease acquire(SessionOwner owner, AgentId agentId, String taskId, String runId, Instant now, Instant until);
    void release(SessionOwner owner, AgentId agentId, String taskId, String runId, long fence);

    record Lease(String taskId, String runId, long fence, Instant until) { }

    /** event.payload 必须是完整任务快照，投影可由事件恢复；相同事件重试先返回原结果。 */
    record Change(SessionOwner owner, ConversationTask task, long expectedRevision,
                  SessionLogPort.AppendSessionEvent event, boolean select,
                  Optional<Lease> lease) {
        public Change {
            Objects.requireNonNull(owner); Objects.requireNonNull(task); Objects.requireNonNull(event);
            Objects.requireNonNull(lease);
            if (!task.belongsTo(owner, event.target().agentId()) || !owner.equals(event.target().owner())
                    || expectedRevision < 0 || task.revision() != expectedRevision + 1)
                throw new IllegalArgumentException("task_change_invalid");
        }
    }

    sealed interface Result {
        record Committed(ConversationTask task, CommittedSessionEvent event, boolean replayed) implements Result { }
        record Conflict(String reason) implements Result { }
    }
}
