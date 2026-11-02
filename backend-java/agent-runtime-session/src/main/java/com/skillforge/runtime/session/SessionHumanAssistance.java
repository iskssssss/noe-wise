package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanAssistance;
import com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanReceipt;
import java.time.Instant;
import java.util.*;

/** 人工协助的唯一会话事实；不创建 Run，也不授予模型新的执行权限。 */
public final class SessionHumanAssistance {
    public static final String EVENT = "human.assistance";
    public static final String SCHEMA = "skillforge.runtime.session.human-assistance.v1";
    private final SessionLogPort log;
    public SessionHumanAssistance(SessionLogPort log) { this.log = Objects.requireNonNull(log); }

    public HumanReceipt receive(SessionOwner owner, AgentId agent, HumanAssistance value, TraceEnvelope trace, Instant at) {
        var payload = new TypedPayload(SCHEMA, RuntimeJson.writeCanonical(Map.of("commandId", value.commandId(),
                "ticketId", value.ticketId(), "operatorId", value.operatorId(), "operatorName", value.operatorName(),
                "authorType", "human_operator", "text", value.text())));
        var id = new EventId("human-assistance:" + SessionEventValues.hash(SCHEMA, RuntimeJson.writeCanonical(List.of(
                owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(), owner.target().kind().name(),
                owner.target().id(), Long.toString(owner.requireUser().value()), value.commandId()))));
        var prior = log.findByEvent(owner, id);
        // 已提交事实的时间与实际主助手身份冻结；重复受理不能因当前主助手切换而重写来源。
        var target = new SessionProjectionTarget(owner, prior.map(CommittedSessionEvent::agentId).orElse(agent),
                new InputId(value.commandId()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var command = new SessionLogPort.AppendSessionEvent(target, new SessionEventDraft(id, EVENT, "1",
                SessionEventValues.hash(SCHEMA, payload.canonicalJson()), prior.map(CommittedSessionEvent::trace).orElse(trace),
                payload, prior.map(CommittedSessionEvent::occurredAt).orElse(at)));
        var event = switch (log.append(command)) {
            case SessionLogPort.AppendResult.Appended v -> v.event();
            case SessionLogPort.AppendResult.Replayed v -> v.event();
            case SessionLogPort.AppendResult.Conflict ignored -> throw new IllegalStateException("human_assistance_command_conflict");
        };
        SessionEventValues.requireAppend(command, event, 0);
        return new HumanReceipt(event.eventId().value(), event.sessionSequence());
    }

    /** 原人工回复已提交时只核不可变原文与完整owner，不生成另一输入。 */
    public Optional<HumanReceipt> receipt(SessionOwner owner,HumanAssistance value) {
        var id=new EventId("human-assistance:"+SessionEventValues.hash(SCHEMA,RuntimeJson.writeCanonical(List.of(
                owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),owner.target().kind().name(),
                owner.target().id(),Long.toString(owner.requireUser().value()),value.commandId()))));
        return log.findByEvent(owner,id).map(event->{
            if(!event.owner().equals(owner)||!content(event).equals(value))throw new IllegalStateException("human_assistance_command_conflict");
            return new HumanReceipt(event.eventId().value(),event.sessionSequence());
        });
    }
    public static boolean supports(CommittedSessionEvent event) { return EVENT.equals(event.eventType()); }
    public static HumanAssistance content(CommittedSessionEvent event) {
        if (!supports(event) || !SCHEMA.equals(event.payload().schemaId()) || !"1".equals(event.eventVersion())
                || event.runRef().isPresent()) throw new IllegalStateException("human_assistance_source_rejected");
        var row = SessionNativeConversation.object(event.payload().canonicalJson());
        if (!row.keySet().equals(Set.of("commandId", "ticketId", "operatorId", "operatorName", "authorType", "text"))
                || !"human_operator".equals(row.get("authorType"))) throw new IllegalStateException("human_assistance_source_rejected");
        var value = new HumanAssistance((String)row.get("commandId"),(String)row.get("ticketId"),
                (String)row.get("operatorId"),(String)row.get("operatorName"),(String)row.get("text"));
        if (!event.inputId().value().equals(value.commandId())) throw new IllegalStateException("human_assistance_source_rejected");
        return value;
    }
}
