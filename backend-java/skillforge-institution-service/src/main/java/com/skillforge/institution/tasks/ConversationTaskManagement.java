package com.skillforge.institution.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 机构管理操作共用服务；当前会话只用于确认操作者可管理的用户与助手归属。 */
@Service
public class ConversationTaskManagement {
    private final SessionService sessions;
    private final ConversationTaskPort tasks;
    private final ConversationTaskCoordinator coordinator;
    private final ObjectMapper json;
    private final Clock clock;
    public ConversationTaskManagement(SessionService sessions, ConversationTaskPort tasks,
            ConversationTaskCoordinator coordinator, ObjectMapper json, Clock clock) {
        this.sessions = sessions; this.tasks = tasks; this.coordinator = coordinator; this.json = json; this.clock = clock;
    }
    public SessionOwner owner(String tenant, String sessionId) {
        var session = sessions.require(tenant, sessionId);
        if (session.getAgentId() == null || session.getEndUserId() == null)
            throw new ApiException(HttpStatus.CONFLICT, "仅 Agent 会话支持任务续接");
        return SessionOwner.user(new SessionRef(new TenantId(tenant), sessionId),
                new AgentId(session.getAgentId().toString()), new EndUserId(session.getEndUserId()));
    }
    public List<ConversationTask> list(SessionOwner owner, String query, boolean closed, int offset, int limit) {
        return tasks.search(owner, owner.target().requireAgent(), query, closed, offset, limit);
    }
    public ConversationTask detail(SessionOwner owner, String id) {
        return coordinator.require(owner, owner.target().requireAgent(), id);
    }

    /** 只读取当前操作者在当前会话的原操作回执；未找到不代表操作失败或终止。 */
    public Optional<ConversationTask> receipt(SessionOwner owner, String id, String operationId, String actor) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 200 || actor == null || actor.isBlank())
            throw new IllegalArgumentException("task_management_identity_invalid");
        coordinator.require(owner, owner.target().requireAgent(), id);
        String key = "task-management:" + ConversationTaskCoordinator.hash(actor, operationId);
        return coordinator.replay(owner, owner.target().requireAgent(), key).filter(task -> task.taskId().equals(id));
    }

    public ConversationTask update(SessionOwner owner, String id, long revision, String operation,
                                   String text, String operationId, String actor) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 200 || actor == null || actor.isBlank())
            throw new IllegalArgumentException("task_management_identity_invalid");
        // 先验证归属，再比较原操作载荷；成功请求重放不被当前 revision 拒绝。
        var before = detail(owner, id);
        String key = "task-management:" + ConversationTaskCoordinator.hash(actor, operationId);
        var input = new InputId(key);
        var identity = new RunIdentity(owner,owner.target().requireAgent(), input, new RunRef(owner.sessionRef().tenantId(), key),
                new TraceEnvelope(key, key, input, Optional.empty()), new ResultRef(key),new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()),Optional.empty(),
                new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(),key));
        var source = coordinator.append(identity, key + ":source", "task.user-correction", "skillforge.task-management.v1",
                json.createObjectNode().put("taskId", id).put("operation", operation).put("expectedRevision", revision)
                        .put("text", text == null ? "" : text).put("actor", actor));
        var replayed = coordinator.replay(owner, owner.target().requireAgent(), key);
        if (replayed.isPresent()) return replayed.get();
        if (before.revision() != revision) throw new ApiException(HttpStatus.CONFLICT, "TASK_OPERATION_REJECTED_BEFORE_MUTATION", "任务已更新，请刷新后核对", false);
        if ("correct".equals(operation)) {
            if (text == null || text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("task_correction_invalid");
            return coordinator.correct(identity, key, id, source);
        }
        var status = switch (operation) {
            case "complete" -> ConversationTask.Status.COMPLETED;
            case "cancel" -> ConversationTask.Status.CANCELLED;
            case "reopen" -> ConversationTask.Status.OPEN;
            case "select" -> before.status();
            default -> throw new IllegalArgumentException("task_management_operation_invalid");
        };
        if ("select".equals(operation) && (status == ConversationTask.Status.COMPLETED || status == ConversationTask.Status.CANCELLED))
            throw new ApiException(HttpStatus.CONFLICT, "TASK_OPERATION_REJECTED_BEFORE_MUTATION", "请先重新打开任务", false);
        var refs = new java.util.ArrayList<>(before.sources());
        refs.add(new ConversationTask.Source(owner, source.agentId(), source.eventId()));
        var next = new ConversationTask(before.taskId(), before.tenantId(), before.endUserId(), before.agentId(),
                before.revision() + 1, status, before.title(), before.summary(), before.state(), refs,
                before.maintenance(), clock.instant());
        return coordinator.save(identity, key + ":change", before, next, "select".equals(operation));
    }
}
