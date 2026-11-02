package com.skillforge.institution.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 正式群消息到不可变 Memory 来源的唯一投影；不把请求操作者写成原作者。 */
@Service
public final class GroupMemorySources {
    public static final String SCHEMA = "skillforge.group.memory-source.v1";
    public static final String EVENT_TYPE = "group.memory.source";
    private final GroupMessageMapper messages;
    private final GroupHistoryMapper history;
    private final SessionLogPort log;
    private final ObjectMapper json;

    public GroupMemorySources(GroupMessageMapper messages, GroupHistoryMapper history,
            SessionLogPort log, ObjectMapper json) {
        this.messages = messages; this.history = history; this.log = log; this.json = json;
    }

    /** 原消息不可改写；事件身份包含来源修订，跨 Agent 复用首次正式投影。 */
    public List<EventId> project(PortCallContext context, String integration, String group, List<String> ids) {
        var sourceRows=messages(context,integration,group,ids);
        var result = new ArrayList<EventId>();
        for (var row : sourceRows) {
            String id=row.sourceMessageId();
            var eventId = eventId(row.sourceMessageId(), row.sourceRevision());
            var prior = log.findByEvent(context.runIdentity().owner(), eventId);
            if (prior.isPresent()) { require(prior.orElseThrow(), integration, group); result.add(eventId); continue; }
            var source = history.source(row.tenantId(), integration, row.groupBindingId(), id);
            if (source == null) throw denied("group_memory_source_missing");
            try {
                String payload = json.writeValueAsString(payload(row));
                var input = new InputId(eventId.value());
                var trace = new TraceEnvelope(eventId.value(), eventId.value(), input, Optional.empty());
                var target = new SessionProjectionTarget(context.runIdentity().owner(), context.runIdentity().agentId(), input,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
                var event = new SessionEventDraft(eventId, EVENT_TYPE, "v1", row.fingerprint(), trace,
                        new TypedPayload(SCHEMA, payload), source.createdAt());
                final SessionLogPort.AppendResult appended;
                try { appended=log.append(new SessionLogPort.AppendSessionEvent(target,event)); }
                catch(RuntimeException unknown) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MEMORY_SOURCE_OUTCOME_UNKNOWN",
                            "正式来源投影回执未知，请保留原消息身份与记忆幂等键查询原结果",false);
                }
                CommittedSessionEvent committed=switch(appended) {
                    case SessionLogPort.AppendResult.Appended value -> value.event();
                    case SessionLogPort.AppendResult.Replayed value -> value.event();
                    case SessionLogPort.AppendResult.Conflict ignored -> throw denied("group_memory_source_projection_conflict");
                };
                if(!committed.owner().equals(context.runIdentity().owner())||!committed.eventId().equals(eventId)
                        ||!committed.payload().equals(event.payload())||!committed.occurredAt().equals(event.occurredAt()))
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MEMORY_SOURCE_OUTCOME_UNKNOWN",
                            "正式来源投影回执不匹配，请保留原消息身份与记忆幂等键查询原结果",false);
                require(committed,integration,group);
                result.add(eventId);
            } catch (JsonProcessingException invalid) { throw new IllegalStateException("group_memory_source_json_invalid", invalid); }
        }
        return List.copyOf(result);
    }

    /** 治理授权前只读核对原消息，拒绝的命令不提前追加来源投影。 */
    public List<GroupMessageMapper.Message> messages(PortCallContext context,String integration,String group,List<String> ids) {
        if(ids==null||ids.isEmpty()||ids.size()>64||new HashSet<>(ids).size()!=ids.size())throw denied("group_memory_source_invalid");
        return ids.stream().map(id->{
            var row=message(context.runIdentity().runRef().tenantId().value(),integration,group,id);
            if(!row.sessionId().equals(context.runIdentity().owner().sessionRef().sessionId()))throw denied("group_memory_source_session_mismatch");
            return row;
        }).toList();
    }

    /** 校验精确已提交来源及当前正式原消息，不信任模型/调用者提供的作者 JSON。 */
    public GroupMessageMapper.Message require(CommittedSessionEvent event, String integration, String group) {
        if (!EVENT_TYPE.equals(event.eventType()) || !"v1".equals(event.eventVersion()) || !SCHEMA.equals(event.payload().schemaId()))
            throw denied("group_memory_source_not_public_message");
        try {
            var value = json.readValue(event.payload().canonicalJson(), Source.class);
            var row = message(event.owner().sessionRef().tenantId().value(), integration, group,
                    value.sourceMessageId());
            if (!payload(row).equals(value)
                    || !row.sessionId().equals(event.owner().sessionRef().sessionId())
                    || !event.eventId().equals(eventId(row.sourceMessageId(), row.sourceRevision())))
                throw denied("group_memory_source_mismatch");
            return row;
        } catch (JsonProcessingException invalid) { throw denied("group_memory_source_invalid"); }
    }

    public List<GroupMessageMapper.Message> read(PortCallContext context, String integration, String group, List<EventId> ids) {
        return ids.stream().map(id -> require(log.findByEvent(context.runIdentity().owner(), id)
                .orElseThrow(() -> denied("group_memory_source_missing")), integration, group)).toList();
    }

    /** 系统提取主体没有集成凭据；从已提交来源取域后仍逐字段核对正式消息和指定群。 */
    public GroupMessageMapper.Message requireForExtraction(CommittedSessionEvent event, String group) {
        if (!EVENT_TYPE.equals(event.eventType()) || !SCHEMA.equals(event.payload().schemaId()))
            throw denied("group_memory_source_not_public_message");
        try {
            Source payload = json.readValue(event.payload().canonicalJson(), Source.class);
            return require(event, payload.integrationId(), group);
        } catch (JsonProcessingException invalid) {
            throw denied("group_memory_source_invalid");
        }
    }

    /** 只读取同一真实 Session 的已提交来源，供确定性治理时态复用。 */
    public CommittedSessionEvent event(PortCallContext context, EventId id) {
        return log.findByEvent(context.runIdentity().owner(),id)
                .orElseThrow(()->denied("group_memory_source_missing"));
    }

    /** 相同正式消息修订在所有服务 Agent 之间复用同一个来源身份。 */
    public static EventId eventId(String sourceMessageId, long revision) {
        return new EventId("group-memory-source:" + sourceMessageId + ":" + revision);
    }

    /** 固定事件协议只在日志边界序列化；不以可变 Map 或 JSON 节点传递业务材料。 */
    public Source payload(GroupMessageMapper.Message row) throws JsonProcessingException {
        return new Source(row.integrationId(), Long.toString(row.groupBindingId()), row.sourceMessageId(),
                row.sourceEventId(), row.sourceRevision(), row.sourceSequence(),
                json.readValue(row.authorJson(), GroupMessageContracts.Author.class),
                json.readValue(row.contentJson(), json.getTypeFactory().constructCollectionType(List.class, ChatContent.class)),
                row.fingerprint());
    }

    /** v1 字段名与原正式消息投影一致；正文和作者仍引用群消息唯一权威。 */
    public record Source(@JsonProperty("integration_id") String integrationId,
            @JsonProperty("group_binding_id") String groupBindingId,
            @JsonProperty("source_message_id") String sourceMessageId,
            @JsonProperty("source_event_id") String sourceEventId,
            @JsonProperty("source_revision") long sourceRevision,
            @JsonProperty("source_sequence") long sourceSequence,
            @JsonProperty("author") GroupMessageContracts.Author author,
            @JsonProperty("content") List<ChatContent> content,
            @JsonProperty("source_fingerprint") String sourceFingerprint) {
        public Source { content = List.copyOf(content); }
    }

    private GroupMessageMapper.Message message(String tenant, String integration, String group, String id) {
        var row = messages.message(Long.parseLong(tenant), integration, Long.parseLong(group), id);
        if (row == null || !"end_user".equals(row.authorKind()) || row.authorEndUserId() == null)
            throw denied("group_memory_source_not_user_message");
        return row;
    }

    private static ApiException denied(String reason) { return new ApiException(HttpStatus.FORBIDDEN, reason); }
}
