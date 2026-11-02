package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.Snapshot;
import java.time.Instant;
import java.util.*;

/** 规划模式的日志投影。沿用 Session owner 和稳定事件身份，不持有执行票据或第二份状态。 */
public final class SessionCollaboration {
    public static final String SELECT = "plan/selection";
    public static final String MODE = "plan/mode";
    public static final String MODE_SCHEMA = "skillforge.runtime.plan-mode.v1";
    private static final Set<String> EVENTS = Set.of(SELECT, MODE, AgentExecutionProgram.ACCEPTED_EVENT,
            AgentExecutionProgram.COMPLETED_EVENT);
    private final SessionLogPort log;

    public SessionCollaboration(SessionLogPort log) { this.log = Objects.requireNonNull(log); }

    public Snapshot read(SessionOwner owner) {
        boolean active = false;
        Selection selected = Selection.inactive();
        long applied = 0;
        Set<RunRef> running = new HashSet<>();
        for (var event : events(owner, EVENTS)) {
            if (SELECT.equals(event.eventType())) {
                selected = selection(event);
                // 空闲选择以同一日志位置生效；不在追加前读取可竞态的 Run 快照，也不在重放时补写 mode。
                // 工具批准携带 RunRef，始终等待下个 Run 接受边界。
                if (event.runRef().isEmpty() && running.isEmpty()) {
                    active = selected.active(); applied = selected.sourceSequence();
                }
            }
            else if (MODE.equals(event.eventType())) {
                var value = mode(event.payload());
                active = value.active(); applied = value.sourceSequence();
            } else if (AgentExecutionProgram.ACCEPTED_EVENT.equals(event.eventType())) {
                running.add(event.runRef().orElseThrow(SessionCollaboration::invalid));
            } else if (AgentExecutionProgram.COMPLETED_EVENT.equals(event.eventType())) {
                running.remove(event.runRef().orElseThrow(SessionCollaboration::invalid));
            }
        }
        return new Snapshot(active, selected.sourceSequence() > applied && active != selected.active(),
                selected.active());
    }

    /** 准入时读取一次选择；恢复只读取原冻结目录，不重新采用最新选择。 */
    public Selection selected(SessionOwner owner) {
        Selection selected = Selection.inactive();
        for (var event : events(owner, Set.of(SELECT))) selected = selection(event);
        return selected;
    }

    /** 权限已由 façade 验证；命令正文与稳定 commandId 一起进入原 SessionLog。 */
    public void select(VerifiedIdentity identity, AgentId agentId, String commandId, boolean active,
                TraceEnvelope trace, Instant at) {
        text(commandId);
        var owner = identity.owner();
        var target = new SessionProjectionTarget(owner, agentId, new InputId(commandId), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        var id = SessionEventValues.hash("plan-command.v1", RuntimeJson.writeCanonical(List.of(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), agentId.value(),
                Long.toString(owner.requireUser().value()), commandId)));
        append(target, new EventId("plan-selection:" + id), SELECT,
                new TypedPayload("skillforge.runtime.plan-selection.v1", RuntimeJson.writeCanonical(Map.of("active", active))), trace, at);
    }

    /** 只有受信任的 Tool continuation 可以调用；批准先产生选择，下个已接受Run才生效。 */
    public CommittedSessionEvent requestPlanExit(RunIdentity run, String invocationId, Instant at) {
        text(invocationId);
        return append(target(run), new EventId("plan-exit:" + invocationId), SELECT,
                new TypedPayload("skillforge.runtime.plan-selection.v1", RuntimeJson.writeCanonical(Map.of("active", false))), run.trace(), at);
    }

    static Map<String, Object> value(Selection selection) {
        return Map.of("active", selection.active(), "sourceEventId", selection.sourceEventId(),
                "sourceSequence", Long.toString(selection.sourceSequence()));
    }

    /** 与冻结目录一同进入Run准入事务；重放只读取原选择，绝不采用后来的会话开关。 */
    public static com.skillforge.runtime.session.spi.ExecutionStore.Event admissionMode(RunIdentity run, Selection selection) {
        var payload = new TypedPayload(MODE_SCHEMA, RuntimeJson.writeCanonical(value(selection)));
        return new com.skillforge.runtime.session.spi.ExecutionStore.Event(
                new EventId("plan-mode:" + run.runRef().runId()), MODE, payload);
    }

    private static Selection mode(TypedPayload payload) {
        if (!payload.schemaId().equals(MODE_SCHEMA)) throw invalid();
        return parseSelection(SessionNativeConversation.object(payload.canonicalJson()));
    }

    private static Selection parseSelection(Map<?, ?> value) {
        if (!value.keySet().equals(Set.of("active", "sourceEventId", "sourceSequence"))
                || !(value.get("active") instanceof Boolean active)
                || !(value.get("sourceEventId") instanceof String id)
                || !(value.get("sourceSequence") instanceof String sequence)) throw invalid();
        try { return new Selection(active, id, Long.parseLong(sequence)); }
        catch (RuntimeException rejected) { throw invalid(); }
    }

    private static Selection selection(CommittedSessionEvent event) {
        if (!SELECT.equals(event.eventType()) || !event.payload().schemaId().equals("skillforge.runtime.plan-selection.v1")) throw invalid();
        var value = SessionNativeConversation.object(event.payload().canonicalJson());
        if (!value.keySet().equals(Set.of("active")) || !(value.get("active") instanceof Boolean active)) throw invalid();
        return new Selection(active, event.eventId().value(), event.sessionSequence());
    }

    private List<CommittedSessionEvent> events(SessionOwner owner, Set<String> types) {
        var events = new ArrayList<CommittedSessionEvent>();
        long cursor = 0;
        while (true) {
            var page = log.readAfter(new SessionLogPort.ReadSessionLog(owner, cursor, 100), types);
            if (page.size() > 100) throw invalid();
            for (var event : page) {
                if (!event.owner().equals(owner) || event.sessionSequence() <= cursor) throw invalid();
                cursor = event.sessionSequence(); events.add(event);
            }
            if (page.size() < 100) return List.copyOf(events);
        }
    }

    private CommittedSessionEvent append(SessionProjectionTarget target, EventId id, String type,
            TypedPayload payload, TraceEnvelope trace, Instant at) {
        var prior = log.findByEvent(target.owner(), id);
        return append(command(target, id, type, payload, trace, prior.map(CommittedSessionEvent::occurredAt).orElse(at)));
    }

    private CommittedSessionEvent append(SessionLogPort.AppendSessionEvent command) {
        var result = log.append(command);
        var event = switch (result) {
            case SessionLogPort.AppendResult.Appended value -> value.event();
            case SessionLogPort.AppendResult.Replayed value -> value.event();
            case SessionLogPort.AppendResult.Conflict ignored -> throw invalid();
        };
        SessionEventValues.requireAppend(command, event, 0);
        return event;
    }

    private static SessionLogPort.AppendSessionEvent command(SessionProjectionTarget target, EventId id, String type,
            TypedPayload payload, TraceEnvelope trace, Instant at) {
        return new SessionLogPort.AppendSessionEvent(target, new SessionEventDraft(id, type, "1",
                SessionEventValues.hash(payload.schemaId(), payload.canonicalJson()), trace, payload, at));
    }

    static SessionProjectionTarget target(RunIdentity run) {
        return new SessionProjectionTarget(run.owner(), run.agentId(), run.causationInputId(), Optional.of(run.runRef()),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public record Selection(boolean active, String sourceEventId, long sourceSequence) {
        public Selection {
            Objects.requireNonNull(sourceEventId);
            if (sourceSequence < 0 || (sourceSequence == 0) != sourceEventId.isEmpty() || sourceSequence == 0 && active) throw invalid();
        }
        public static Selection inactive() { return new Selection(false, "", 0); }
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static IllegalStateException invalid() { return new IllegalStateException("session_collaboration_source_rejected"); }
}
