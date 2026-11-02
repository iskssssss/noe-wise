package com.skillforge.institution.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.SessionPreparations;
import com.skillforge.runtime.session.spi.SessionTaskContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** 显式任务选择与管理写入边界；普通对话只冻结入口指定的任务，不触发模型维护。 */
@Service
public final class ConversationTaskCoordinator implements SessionTaskContext {
    static final String SNAPSHOT_SCHEMA = "skillforge.conversation-task.snapshot.v2";
    private static final int MAX_SYSTEM_AGENT_INPUT_CHARACTERS = 128_000;
    private static final int MAX_EVIDENCE_EVENTS = 256;

    private final ConversationTaskPort tasks;
    private final SessionLogPort log;
    private final SystemAgentExecution execution;
    private final ObjectMapper json;
    private final Clock clock;

    public ConversationTaskCoordinator(ConversationTaskPort tasks, SessionLogPort log,
            SystemAgentExecution execution, ObjectMapper json, Clock clock) {
        this.tasks = tasks;
        this.log = log;
        this.execution = execution;
        this.json = ConversationTaskConfiguration.taskJson(json);
        this.clock = clock;
    }

    /**
     * 仅从本 Run 的原始消息读取显式 task_id。同一 Run 首次选择提交后始终返回该冻结快照，
     * 不读取 tasks.current，也不因任务随后变化而隐式切换。
     */
    @Override
    public Optional<CommittedSessionEvent> prepare(SessionPreparations.Key key, VerifiedIdentity actor,
                                                    List<CommittedSessionEvent> sources) {
        authorize(key.identity(), actor);
        var identity = key.identity();
        String operation = "task-selection:" + hash(identity.runRef().runId(), identity.causationInputId().value());
        EventId contextId = new EventId(operation + ":context");
        var frozen = log.findByEvent(identity.owner(), contextId);
        if (frozen.isPresent()) {
            validateFrozen(identity, contextId, frozen.orElseThrow());
            return frozen;
        }

        String requested = explicitTask(identity, sources);
        if (requested == null) return Optional.empty();
        ConversationTask selected = tasks.find(identity.owner(), identity.agentId(), requested)
                .orElseThrow(SessionTaskContext.SelectionRejected::new);
        ObjectNode body = json.createObjectNode().put("selectionId", operation)
                .put("loopId", key.loopId()).put("active", true);
        body.set("task", safeTask(selected, true));
        return Optional.of(append(identity, contextId.value(), EVENT_TYPE, SCHEMA, body));
    }

    private String explicitTask(RunIdentity identity, List<CommittedSessionEvent> sources) {
        String requested = null;
        for (var source : sources) {
            if (!"inbound.message".equals(source.eventType())
                    || !source.inputId().equals(identity.causationInputId())
                    || !source.runRef().equals(Optional.of(identity.runRef()))) continue;
            requireCommitted(identity, source, "task_source_not_committed");
            JsonNode value = object(source.payload().canonicalJson()).path("extraParameters").get("task_id");
            if (value == null) continue;
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 200)
                throw new SessionTaskContext.SelectionRejected();
            if (requested != null && !requested.equals(value.asText()))
                throw new SessionTaskContext.SelectionRejected();
            requested = value.asText();
        }
        return requested;
    }

    private void validateFrozen(RunIdentity identity, EventId expectedId, CommittedSessionEvent event) {
        if (!event.owner().equals(identity.owner()) || !event.agentId().equals(identity.agentId())
                || !event.inputId().equals(identity.causationInputId())
                || !event.runRef().equals(Optional.of(identity.runRef())) || !event.eventId().equals(expectedId)
                || !EVENT_TYPE.equals(event.eventType()) || !SCHEMA.equals(event.payload().schemaId()))
            throw new IllegalArgumentException("task_context_invalid");
        var body = object(event.payload().canonicalJson());
        if (!expectedId.value().equals(body.path("selectionId").asText() + ":context")
                || !body.path("active").asBoolean(false) || !body.path("task").isObject()
                || body.path("task").path("taskId").asText("").isBlank())
            throw new IllegalArgumentException("task_context_invalid");
    }

    /** 显式管理纠正可调用模型解释自由文本，但不参与普通聊天准备、回复或动作路径。 */
    ConversationTask correct(RunIdentity identity, String operation, String taskId,
                             CommittedSessionEvent correction) {
        requireCommitted(identity, correction, "task_correction_source_invalid");
        if (!"task.user-correction".equals(correction.eventType()))
            throw new IllegalArgumentException("task_correction_source_invalid");
        var replayed = replay(identity.owner(), identity.agentId(), operation);
        if (replayed.isPresent()) return replayed.orElseThrow();
        var before = require(identity.owner(), identity.agentId(), taskId);
        if (before.status() == ConversationTask.Status.COMPLETED
                || before.status() == ConversationTask.Status.CANCELLED)
            throw new IllegalStateException("task_reopen_required");

        ObjectNode request = json.createObjectNode().put("managementAction", "correct")
                .put("currentTaskId", taskId);
        request.putArray("sourceEventIds").add(correction.eventId().value());
        request.putArray("events").addObject().put("eventId", correction.eventId().value())
                .put("eventType", correction.eventType()).put("content", correction.payload().canonicalJson());
        request.set("selectedTask", safeTask(before, true));
        request.set("candidates", json.createArrayNode().add(safeTask(before, false)));
        append(identity, operation + ":request", "agent.task-request", "skillforge.task-request.v2", request);

        var run = execution.executePurpose("task-context", identity.owner().sessionRef().tenantId().value(),
                operation + ":model:0", checkedModelInput(request));
        if (!"COMPLETED".equals(run.status())) throw new IllegalStateException("task_correction_failed");
        ObjectNode patch = object(run.output());
        String action = ConversationTaskState.required(patch, "action", 24);
        if (!Set.of("select", "update").contains(action)
                || patch.has("taskId") && !taskId.equals(patch.path("taskId").asText()))
            throw new IllegalArgumentException("task_correction_patch_invalid");

        var evidence = evidence(before, correction, patch);
        ObjectNode state;
        try {
            state = ConversationTaskState.merge(json, object(before.state().canonicalJson()), patch, evidence);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("task_correction_failed", invalid);
        }
        String title = patch.has("title") ? ConversationTaskState.required(patch, "title", 200) : before.title();
        String summary = patch.has("summary") ? ConversationTaskState.required(patch, "summary", 8000)
                : before.summary();
        var status = state.path("pending").isArray() && !state.path("pending").isEmpty()
                ? ConversationTask.Status.WAITING_USER : ConversationTask.Status.OPEN;
        var next = new ConversationTask(before.taskId(), before.tenantId(), before.endUserId(), before.agentId(),
                before.revision() + 1, status, title, summary, ConversationTaskState.payload(state),
                sourceReferences(before, state, evidence), before.maintenance(), clock.instant());
        return save(identity, operation + ":change", before, next, true);
    }

    private List<CommittedSessionEvent> evidence(ConversationTask before, CommittedSessionEvent correction,
                                                  JsonNode patch) {
        var result = new LinkedHashMap<String, CommittedSessionEvent>();
        result.put(correction.eventId().value(), correction);
        var requested = new LinkedHashSet<String>();
        patch.path("facts").forEach(fact -> requested.add(fact.path("sourceEventId").asText("")));
        for (var source : before.sources()) {
            if (!requested.contains(source.eventId().value())) continue;
            var event = log.findByEvent(source.owner(), source.eventId())
                    .orElseThrow(() -> new IllegalStateException("task_fact_source_missing"));
            if (!event.owner().equals(source.owner()) || !event.agentId().equals(source.agentId())
                    || !sameSubject(before, event.owner(), event.agentId()))
                throw new IllegalStateException("task_fact_source_invalid");
            var prior = result.putIfAbsent(source.eventId().value(), event);
            if (prior != null && !prior.owner().equals(event.owner()))
                throw new IllegalStateException("task_fact_source_ambiguous");
        }
        if (result.size() > MAX_EVIDENCE_EVENTS) throw new IllegalStateException("task_evidence_limit");
        return List.copyOf(result.values());
    }

    private List<ConversationTask.Source> sourceReferences(ConversationTask before, JsonNode state,
                                                            List<CommittedSessionEvent> evidence) {
        var referenced = new HashSet<String>();
        state.path("facts").elements().forEachRemaining(value ->
                referenced.add(value.path("sourceEventId").asText("")));
        var result = new LinkedHashSet<ConversationTask.Source>();
        before.sources().stream().filter(source -> referenced.contains(source.eventId().value()))
                .forEach(result::add);
        evidence.forEach(event -> result.add(new ConversationTask.Source(
                event.owner(), event.agentId(), event.eventId())));
        if (result.isEmpty() || result.size() > MAX_EVIDENCE_EVENTS)
            throw new IllegalStateException("task_evidence_limit");
        return List.copyOf(result);
    }

    static boolean sameSubject(ConversationTask task, SessionOwner owner, AgentId agentId) {
        return task.tenantId().equals(owner.sessionRef().tenantId())
                && task.endUserId().equals(owner.requireUser()) && task.agentId().equals(agentId);
    }

    /** 工具层已经完成结构校验；此处冻结参数、回读来源并执行唯一的任务 CAS 写入。 */
    public ConversationTask update(RunIdentity identity, String invocationId, ToolUpdate update) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(update);
        if (invocationId == null || invocationId.isBlank())
            throw new IllegalArgumentException("update_task_arguments_rejected");
        String subject = identity.owner().sessionRef().tenantId().value() + ":"
                + identity.owner().target().kind() + ":" + identity.owner().target().id() + ":"
                + identity.agentId().value() + ":" + identity.owner().requireUser().value() + ":"
                + identity.runRef().runId();
        String digest = hash(subject, invocationId);
        String operation = "task-tool:" + digest;
        String taskId = update.taskId() == null ? "task-" + digest.substring(0, 32) : update.taskId();

        ObjectNode request = json.createObjectNode().put("taskId", taskId).put("create", update.taskId() == null);
        if (update.expectedRevision() != null) request.put("expectedRevision", update.expectedRevision());
        if (update.title() != null) request.put("title", update.title());
        if (update.summary() != null) request.put("summary", update.summary());
        if (update.status() != null) request.put("status", update.status().name());
        var facts = request.putArray("facts");
        update.facts().forEach(fact -> facts.addObject().put("key", fact.key()).put("value", fact.value())
                .put("sourceEventId", fact.sourceEventId()).put("confirmed", fact.confirmed()));
        if (update.pending() != null) request.set("pending", json.valueToTree(update.pending()));
        var audit = append(identity, operation + ":request", "agent.task-tool-request",
                "skillforge.task-tool-request.v1", request);
        var replayed = replay(identity.owner(), identity.agentId(), operation);
        if (replayed.isPresent()) return replayed.orElseThrow();

        ConversationTask before = update.taskId() == null ? null : tasks.find(identity.owner(), identity.agentId(), taskId)
                .orElseThrow(() -> new IllegalArgumentException("task_not_found"));
        if (before == null && tasks.find(identity.owner(), identity.agentId(), taskId).isPresent())
            throw new IllegalStateException("task_id_conflict");
        if (before == null && update.expectedRevision() != null
                || before != null && (update.expectedRevision() == null
                || before.revision() != update.expectedRevision()))
            throw new IllegalStateException("task_revision_conflict");

        ObjectNode patch = json.createObjectNode();
        var factPatch = patch.putArray("facts");
        update.facts().forEach(fact -> factPatch.addObject().put("key", fact.key()).put("value", fact.value())
                .put("sourceEventId", fact.sourceEventId()).put("confirmed", fact.confirmed()));
        if (update.pending() != null) patch.set("pending", json.valueToTree(update.pending()));
        var evidence = toolEvidence(identity, before, audit, update.facts());
        ObjectNode state = ConversationTaskState.merge(json,
                before == null ? null : object(before.state().canonicalJson()), patch, evidence);
        String title = update.title() != null ? update.title() : before == null ? null : before.title();
        String summary = update.summary() != null ? update.summary() : before == null ? "" : before.summary();
        var status = update.status();
        if (status == null && before != null && Set.of(ConversationTask.Status.COMPLETED,
                ConversationTask.Status.CANCELLED).contains(before.status())) status = before.status();
        if (status == null) status = state.path("pending").isArray() && !state.path("pending").isEmpty()
                ? ConversationTask.Status.WAITING_USER : ConversationTask.Status.OPEN;
        long revision = before == null ? 1 : before.revision() + 1;
        var next = new ConversationTask(taskId, identity.owner().sessionRef().tenantId(), identity.owner().requireUser(),
                identity.agentId(), revision, status, title, summary, ConversationTaskState.payload(state),
                toolSourceReferences(before, state, evidence), before == null ? List.of() : before.maintenance(),
                clock.instant());
        return save(identity, operation + ":change", before, next, false);
    }

    private List<CommittedSessionEvent> toolEvidence(RunIdentity identity, ConversationTask before,
                                                      CommittedSessionEvent audit, List<ToolFact> facts) {
        var result = new LinkedHashMap<String, CommittedSessionEvent>();
        result.put(audit.eventId().value(), audit);
        for (var fact : facts) {
            CommittedSessionEvent event = log.findByEvent(identity.owner(), new EventId(fact.sourceEventId())).orElse(null);
            if (event == null && before != null) {
                var candidates = before.sources().stream()
                        .filter(source -> source.eventId().value().equals(fact.sourceEventId())).toList();
                if (candidates.size() > 1 && candidates.stream().map(ConversationTask.Source::owner).distinct().count() > 1)
                    throw new IllegalArgumentException("task_fact_source_ambiguous");
                if (!candidates.isEmpty()) {
                    var source = candidates.getFirst();
                    event = log.findByEvent(source.owner(), source.eventId()).orElse(null);
                }
            }
            if (event == null || before == null && (!event.owner().equals(identity.owner())
                    || !event.agentId().equals(identity.agentId()))
                    || before != null && !sameSubject(before, event.owner(), event.agentId()))
                throw new IllegalArgumentException("task_fact_source_missing");
            var prior = result.putIfAbsent(event.eventId().value(), event);
            if (prior != null && !prior.owner().equals(event.owner()))
                throw new IllegalArgumentException("task_fact_source_ambiguous");
        }
        if (result.size() > MAX_EVIDENCE_EVENTS) throw new IllegalStateException("task_evidence_limit");
        return List.copyOf(result.values());
    }

    private List<ConversationTask.Source> toolSourceReferences(ConversationTask before, JsonNode state,
                                                                List<CommittedSessionEvent> evidence) {
        var referenced = new HashSet<String>();
        state.path("facts").elements().forEachRemaining(value ->
                referenced.add(value.path("sourceEventId").asText("")));
        var result = new LinkedHashSet<ConversationTask.Source>();
        if (before != null) before.sources().stream()
                .filter(source -> referenced.contains(source.eventId().value())).forEach(result::add);
        evidence.forEach(event -> result.add(new ConversationTask.Source(
                event.owner(), event.agentId(), event.eventId())));
        if (result.isEmpty() || result.size() > MAX_EVIDENCE_EVENTS)
            throw new IllegalStateException("task_evidence_limit");
        return List.copyOf(result);
    }

    public record ToolFact(String key, String value, String sourceEventId, boolean confirmed) {
        public ToolFact {
            if (key == null || key.isBlank() || key.length() > 160 || value == null || value.length() > 4_000
                    || sourceEventId == null || sourceEventId.isBlank() || sourceEventId.length() > 1_024)
                throw new IllegalArgumentException("update_task_arguments_rejected");
        }
    }

    public record ToolUpdate(String taskId, Long expectedRevision, String title, String summary,
                             List<ToolFact> facts, List<String> pending, ConversationTask.Status status) {
        public ToolUpdate {
            facts = List.copyOf(facts);
            pending = pending == null ? null : List.copyOf(pending);
        }
    }

    static ObjectNode safeTask(ObjectMapper json, ConversationTask task, boolean includeState) {
        var value = json.createObjectNode().put("taskId", task.taskId()).put("revision", task.revision())
                .put("status", task.status().name()).put("title", task.title()).put("summary", task.summary())
                .put("updatedAt", task.updatedAt().toString());
        if (includeState) value.set("state", safeState(json, task, false));
        return value;
    }

    /** 任务视图保留可复用的事实来源 ID，但不公开来源 Session、完整事件或维护游标。 */
    public static ObjectNode safeState(ObjectMapper json, ConversationTask task, boolean snakeCase) {
        try {
            JsonNode stored = json.readTree(task.state().canonicalJson());
            if (!stored.isObject() || stored.has("facts") && !stored.path("facts").isObject()
                    || stored.has("pending") && !stored.path("pending").isArray())
                throw new IllegalArgumentException("task_state_invalid");
            ObjectNode state = json.createObjectNode();
            ObjectNode facts = state.putObject("facts");
            stored.path("facts").fields().forEachRemaining(entry -> {
                JsonNode fact = entry.getValue();
                if (!fact.path("value").isTextual() || !fact.path("sourceEventId").isTextual())
                    throw new IllegalArgumentException("task_state_invalid");
                facts.putObject(entry.getKey()).put("value", fact.path("value").textValue())
                        .put(snakeCase ? "source_event_id" : "sourceEventId",
                                fact.path("sourceEventId").textValue())
                        .put("confirmed", fact.path("confirmed").asBoolean(false));
            });
            state.set("pending", stored.has("pending") ? stored.path("pending").deepCopy() : json.createArrayNode());
            return state;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("task_state_invalid", failure);
        }
    }

    private ObjectNode safeTask(ConversationTask task, boolean includeState) {
        return safeTask(json, task, includeState);
    }

    TypedPayload snapshot(ConversationTask task) {
        try { return new TypedPayload(SNAPSHOT_SCHEMA, json.writeValueAsString(task)); }
        catch (Exception failure) { throw new IllegalArgumentException("task_encode_failed", failure); }
    }

    ConversationTask decode(TypedPayload payload) {
        if (!SNAPSHOT_SCHEMA.equals(payload.schemaId())) throw new IllegalArgumentException("task_snapshot_schema_invalid");
        try { return json.readValue(payload.canonicalJson(), ConversationTask.class); }
        catch (Exception failure) { throw new IllegalArgumentException("task_decode_failed", failure); }
    }

    Optional<ConversationTask> replay(SessionOwner owner, AgentId agentId, String operation) {
        return log.findByEvent(owner, new EventId(operation + ":change"))
                .filter(event -> event.agentId().equals(agentId)).map(event -> decode(event.payload()));
    }

    ConversationTask save(RunIdentity identity, String eventId, ConversationTask before,
                          ConversationTask next, boolean select) {
        var original = log.findByEvent(identity.owner(), new EventId(eventId));
        if (original.isPresent()) return decode(original.orElseThrow().payload());
        Optional<ConversationTaskPort.Lease> lease = before == null ? Optional.empty() : Optional.of(tasks.acquire(
                identity.owner(), identity.agentId(), next.taskId(), identity.runRef().runId(), clock.instant(),
                clock.instant().plus(Duration.ofMinutes(5))));
        try {
            var result = tasks.commit(new ConversationTaskPort.Change(identity.owner(), next,
                    before == null ? 0 : before.revision(), command(identity, eventId, "agent.task-updated", snapshot(next)),
                    select, lease));
            if (result instanceof ConversationTaskPort.Result.Committed committed) return committed.task();
            if (result instanceof ConversationTaskPort.Result.Conflict conflict)
                throw new IllegalStateException(conflict.reason());
            throw new IllegalStateException("task_commit_result_invalid");
        } finally {
            lease.ifPresent(value -> tasks.release(identity.owner(), identity.agentId(), next.taskId(),
                    value.runId(), value.fence()));
        }
    }

    ConversationTask require(SessionOwner owner, AgentId agentId, String taskId) {
        return tasks.find(owner, agentId, taskId).orElseThrow(() -> new IllegalArgumentException("task_not_found"));
    }

    CommittedSessionEvent append(RunIdentity identity, String id, String type, String schema, JsonNode body) {
        var previous = log.findByEvent(identity.owner(), new EventId(id));
        if (previous.isPresent()) {
            var event = previous.orElseThrow();
            if (!event.owner().equals(identity.owner()) || !event.inputId().equals(identity.causationInputId())
                    || !event.runRef().equals(Optional.of(identity.runRef())) || !event.eventType().equals(type)
                    || !event.payload().schemaId().equals(schema)
                    || !object(event.payload().canonicalJson()).equals(body))
                throw new IllegalArgumentException("task_operation_conflict");
            return event;
        }
        var result = log.append(command(identity, id, type, new TypedPayload(schema, body.toString())));
        if (result instanceof SessionLogPort.AppendResult.Appended value) return value.event();
        if (result instanceof SessionLogPort.AppendResult.Replayed value) return value.event();
        throw new IllegalStateException("task_event_conflict");
    }

    private SessionLogPort.AppendSessionEvent command(RunIdentity identity, String id, String type,
                                                       TypedPayload payload) {
        return new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(identity.owner(),
                identity.agentId(), identity.causationInputId(), Optional.of(identity.runRef()), Optional.empty(), Optional.empty(),
                Optional.empty()), new SessionEventDraft(new EventId(id), type, "1",
                hash(payload.schemaId(), payload.canonicalJson()), identity.trace(), payload, clock.instant()));
    }

    private void requireCommitted(RunIdentity identity, CommittedSessionEvent source, String error) {
        if (!source.owner().equals(identity.owner()) || !source.agentId().equals(identity.agentId())
                || !log.findByEvent(identity.owner(), source.eventId()).equals(Optional.of(source)))
            throw new IllegalArgumentException(error);
    }

    private ObjectNode object(String value) {
        try {
            var node = json.readTree(value);
            if (node instanceof ObjectNode object) return object;
        } catch (Exception failure) {
            throw new IllegalArgumentException("task_json_invalid", failure);
        }
        throw new IllegalArgumentException("task_json_invalid");
    }

    private static String checkedModelInput(ObjectNode request) {
        String value = request.toString();
        if (value.length() > MAX_SYSTEM_AGENT_INPUT_CHARACTERS)
            throw new IllegalStateException("task_correction_input_too_large");
        return value;
    }

    private static void authorize(RunIdentity identity, VerifiedIdentity actor) {
        if (!identity.owner().equals(actor.owner())) throw new IllegalArgumentException("task_actor_mismatch");
    }

    static String hash(String first, String second) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((first.length() + ":" + first + second).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
