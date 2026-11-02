package com.skillforge.runtime.memory;

import static com.skillforge.runtime.memory.MemoryJson.array;
import static com.skillforge.runtime.memory.MemoryJson.bool;
import static com.skillforge.runtime.memory.MemoryJson.decimal;
import static com.skillforge.runtime.memory.MemoryJson.encode;
import static com.skillforge.runtime.memory.MemoryJson.field;
import static com.skillforge.runtime.memory.MemoryJson.integer;
import static com.skillforge.runtime.memory.MemoryJson.nil;
import static com.skillforge.runtime.memory.MemoryJson.object;
import static com.skillforge.runtime.memory.MemoryJson.string;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryTaskConstraint;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryRetrievalDiagnostics;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryRetrievalTrace;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RetentionClassRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryResult;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** SDK-2D Session 事件的规范 schema 与确定性身份。 */
final class MemoryEventCodec {
    static final String RETRIEVAL_EVENT = "retrieval.performed";
    static final String RETRIEVAL_SCHEMA = "skillforge.runtime.memory.retrieval-performed.v3";
    static final String WRITTEN_EVENT = "memory.written";
    static final String WRITTEN_SCHEMA = "skillforge.runtime.memory.written.v2";
    static final String EVENT_VERSION = "2";

    private static final String RETRIEVAL_IDENTITY = "skillforge.runtime.memory-retrieval-identity.v1";
    private static final String WRITE_IDENTITY = "skillforge.runtime.memory-write-identity.v1";
    private static final String EVENT_FINGERPRINT = "skillforge.runtime.memory-session-event-fingerprint.v1";
    static final MemoryJson.Limits LIMITS = new MemoryJson.Limits(2_000_000, 250_000, 64);

    private MemoryEventCodec() { }

    /** 安全诊断消费与生产者共用字段定义；只抽取归并身份、操作和阶段，不返私有内容。 */
    static MemoryObservation.Metadata observation(String eventType, TypedPayload payload) {
        if (RETRIEVAL_EVENT.equals(eventType) && RETRIEVAL_SCHEMA.equals(payload.schemaId())) {
            var snapshot = decodeRetrieval(payload);
            return new MemoryObservation.Metadata(snapshot.query().queryId(), MemoryObservation.Kind.RETRIEVAL,
                    "RETRIEVE", snapshot.phase().name(), "RECORDED");
        }
        if (WRITTEN_EVENT.equals(eventType) && WRITTEN_SCHEMA.equals(payload.schemaId())) {
            var root = MemoryJson.parse(payload.canonicalJson(), LIMITS).requireFields(
                    "operation", "idempotencyKey", "before", "command", "fact", "accessAuditRef", "sourceAuditRef");
            String operation = root.field("operation").stringValue();
            if (!Set.of("CREATE", "SUPERSEDE", "TRANSITION", "ACCEPT_REVISION").contains(operation))
                throw invalid("memory_observation_operation_invalid");
            if (root.field("idempotencyKey").stringValue().isBlank()) throw invalid("memory_observation_identity_invalid");
            // 命令和实际输出继续按所属严格类型读取，正文仅在本方法栈内短暂存在。
            fact(root.field("fact"));
            var command = root.field("command");
            switch (operation) {
                case "CREATE" -> command.requireFields("draft");
                case "SUPERSEDE" -> command.requireFields("current", "expectedRevision", "replacement");
                case "TRANSITION" -> command.requireFields("fact", "expectedRevision", "target", "reasonCode");
                case "ACCEPT_REVISION" -> command.requireFields("current", "expectedRevision", "proposal", "expectedProposalRevision");
                default -> throw invalid("memory_observation_operation_invalid");
            }
            String identity = "memory-write:" + MemoryJson.hash("skillforge.runtime.memory.observation-identity.v1", root.field("idempotencyKey"));
            return new MemoryObservation.Metadata(identity, MemoryObservation.Kind.MEMORY, operation, null, "RECORDED");
        }
        throw invalid("memory_observation_schema_mismatch");
    }

    record RetrievalSnapshot(
            MemoryQuery query,
            List<MemoryFact> facts,
            ResultRef resultRef,
            Instant evaluatedAt,
            MemoryRuntime.Phase phase,
            MemoryRetrievalTrace trace,
            ResultRef accessAuditRef,
            MemoryRetrievalDiagnostics diagnostics) {

        RetrievalSnapshot {
            Objects.requireNonNull(query, "query");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            Objects.requireNonNull(resultRef, "resultRef");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(trace, "trace");
            Objects.requireNonNull(accessAuditRef, "accessAuditRef");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    static TypedPayload retrievalPayload(RetrievalSnapshot snapshot) {
        preflightRetrievalPayload(snapshot);
        return new TypedPayload(RETRIEVAL_SCHEMA, encode(retrievalValue(snapshot)));
    }

    static RetrievalSnapshot decodeRetrieval(TypedPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (!RETRIEVAL_SCHEMA.equals(payload.schemaId())) {
            throw invalid("memory_retrieval_schema_mismatch");
        }
        try {
            MemoryJson.Value root = MemoryJson.parse(payload.canonicalJson(), LIMITS).requireFields(
                    "query", "resultRef", "evaluatedAt", "phase", "trace", "facts", "accessAuditRef", "diagnostics");
            return new RetrievalSnapshot(
                    query(root.field("query")),
                    root.field("facts").arrayValue().stream().map(MemoryEventCodec::fact).toList(),
                    new ResultRef(root.field("resultRef").stringValue()),
                    instant(root.field("evaluatedAt")),
                    enumeration(MemoryRuntime.Phase.class, root.field("phase")),
                    retrievalTrace(root.field("trace")),
                    new ResultRef(root.field("accessAuditRef").stringValue()),
                    diagnostics(root.field("diagnostics")));
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException state
                    && state.getMessage() != null
                    && state.getMessage().startsWith("memory_")) {
                throw state;
            }
            throw invalid("memory_retrieval_payload_invalid");
        }
    }

    static TypedPayload writtenPayload(
            MemoryRuntime.Mutation mutation,
            Optional<MemoryFact> beforeFact,
            MemoryFact fact,
            ResultRef accessAuditRef,
            Optional<ResultRef> sourceAuditRef) {
        Objects.requireNonNull(mutation, "mutation");
        beforeFact = Objects.requireNonNull(beforeFact, "beforeFact");
        Objects.requireNonNull(fact, "fact");
        Objects.requireNonNull(accessAuditRef, "accessAuditRef");
        sourceAuditRef = Objects.requireNonNull(sourceAuditRef, "sourceAuditRef");
        preflightWrittenPayload(mutation, beforeFact, fact, accessAuditRef, sourceAuditRef);
        MemoryJson.Value before = beforeFact.<MemoryJson.Value>map(MemoryEventCodec::fact)
                .orElseGet(MemoryJson::nil);
        MemoryJson.Value root = writtenValue(
                mutation, before, command(mutation), fact(fact), accessAuditRef, sourceAuditRef);
        return new TypedPayload(WRITTEN_SCHEMA, encode(root));
    }

    /** 重放只复用同命令、原 before 与原输出的已提交事件，不改写历史授权审计。 */
    static boolean matchesWritten(TypedPayload payload, MemoryRuntime.Mutation mutation,
            Optional<MemoryFact> before, MemoryFact fact) {
        if (!WRITTEN_SCHEMA.equals(payload.schemaId())) return false;
        MemoryJson.Value root = MemoryJson.parse(payload.canonicalJson(), LIMITS);
        ResultRef audit = new ResultRef(root.field("accessAuditRef").stringValue());
        Optional<ResultRef> sourceAudit = optional(root.field("sourceAuditRef"), item -> new ResultRef(item.stringValue()));
        return writtenPayload(mutation, before, fact, audit, sourceAudit).equals(payload);
    }

    static EventId retrievalEventId(RunIdentity identity, RetrievalSnapshot snapshot) {
        preflightRetrievalEventId(identity, snapshot);
        MemoryJson.Value value = object(
                field("runIdentity", runIdentity(identity)),
                field("queryId", string(snapshot.query().queryId())),
                field("resultRef", string(snapshot.resultRef().value())),
                field("phase", string(snapshot.phase().name())));
        return new EventId("memory-retrieval:" + MemoryJson.hash(RETRIEVAL_IDENTITY, value));
    }

    static EventId writtenEventId(SessionOwner owner, IdempotencyKey key) {
        preflightWrittenEventId(owner, key);
        MemoryJson.Value value = object(
                field("owner", owner(owner)),
                field("idempotencyKey", string(key.value())));
        return new EventId("memory-write:" + MemoryJson.hash(WRITE_IDENTITY, value));
    }

    static String eventFingerprint(
            SessionProjectionTarget target,
            EventId eventId,
            String eventType,
            TypedPayload payload,
            TraceEnvelope trace,
            Instant occurredAt) {
        preflightEventFingerprint(target, eventId, eventType, payload, trace, occurredAt);
        MemoryJson.Value value = object(
                field("target", target(target)),
                field("eventId", string(eventId.value())),
                field("eventType", string(eventType)),
                field("eventVersion", string(EVENT_VERSION)),
                field("trace", trace(trace)),
                field("payload", payload(payload)),
                field("occurredAt", string(occurredAt.toString())));
        return MemoryJson.hash(EVENT_FINGERPRINT, value);
    }

    /** 在 MemoryPort 查询前计量调用方请求及最小检索事件 envelope。 */
    static void preflightRetrieval(
            RunIdentity identity,
            MemoryQuery query,
            ResultRef accessAuditRef,
            SessionProjectionTarget target) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(accessAuditRef, "accessAuditRef");
        Objects.requireNonNull(target, "target");
        RetrievalSnapshot projected = new RetrievalSnapshot(
                query,
                List.of(),
                new ResultRef("memory-preflight"),
                query.asOf(),
                MemoryRuntime.Phase.CANDIDATE,
                new MemoryRetrievalTrace(query.queryId(), List.of(), List.of(), List.of(), List.of()),
                accessAuditRef, MemoryRetrievalDiagnostics.empty());
        TypedPayload payload = retrievalPayload(projected);
        EventId eventId = retrievalEventId(identity, projected);
        eventFingerprint(target, eventId, RETRIEVAL_EVENT, payload, identity.trace(), query.asOf());
    }

    /** 在复制候选 identity 或建立校验集合前，计量外部查询结果的完整 CANDIDATE 事件。 */
    static void preflightRetrieved(
            MemoryQuery query,
            MemoryQueryResult result,
            ResultRef accessAuditRef) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(accessAuditRef, "accessAuditRef");
        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        budget.object(1, "query", "resultRef", "evaluatedAt", "phase", "trace", "facts", "accessAuditRef", "diagnostics");
        preflightQuery(budget, 2, query);
        budget.string(2, result.resultRef().value());
        budget.string(2, result.evaluatedAt().toString());
        budget.string(2, MemoryRuntime.Phase.CANDIDATE.name());
        budget.object(2, "queryId", "candidateIds", "selectedIds", "injectedIds", "referencedIds");
        budget.string(3, query.queryId());
        budget.array(3, result.candidates().size());
        for (MemoryFact fact : result.candidates()) {
            budget.string(4, fact.ref().factId());
        }
        budget.array(3, 0);
        budget.array(3, 0);
        budget.array(3, 0);
        budget.array(2, result.candidates().size());
        for (MemoryFact fact : result.candidates()) {
            preflightFact(budget, 3, fact);
        }
        budget.string(2, accessAuditRef.value());
        preflightDiagnostics(budget, 2, result.diagnostics());
    }

    /** 在来源事件读取前先约束 caller 提供的命令树。 */
    static void preflightMutation(MemoryRuntime.Mutation mutation) {
        Objects.requireNonNull(mutation, "mutation");
        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        preflightCommand(budget, 1, mutation);
    }

    /** 在读取 Applied 事实的来源闭包前先约束 adapter 返回的完整事实。 */
    static void preflightAppliedFact(MemoryFact fact) {
        Objects.requireNonNull(fact, "fact");
        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        preflightFact(budget, 1, fact);
    }

    /** 在 MemoryPort 写入前计量完整调用方命令及可预测的写入事件形状。 */
    static void preflightSubmission(
            MemoryRuntime.Mutation mutation,
            Optional<MemoryFact> beforeFact,
            Optional<MemoryFact> proposalFact,
            SessionOwner owner,
            ResultRef accessAuditRef,
            Optional<ResultRef> sourceAuditRef,
            SessionProjectionTarget target,
            TraceEnvelope trace,
            Instant occurredAt) {
        Objects.requireNonNull(mutation, "mutation");
        Optional<MemoryFact> frozenBefore = Objects.requireNonNull(beforeFact, "beforeFact");
        Optional<MemoryFact> frozenProposal = Objects.requireNonNull(proposalFact, "proposalFact");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(accessAuditRef, "accessAuditRef");
        Optional<ResultRef> frozenSourceAudit = Objects.requireNonNull(sourceAuditRef, "sourceAuditRef");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(occurredAt, "occurredAt");

        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        preflightWrittenRoot(
                budget,
                mutation,
                frozenBefore,
                accessAuditRef,
                frozenSourceAudit,
                (value, depth) -> preflightProjectedFact(
                        value, depth, mutation, frozenBefore, frozenProposal, owner, occurredAt));

        EventId eventId = writtenEventId(owner, mutation.idempotencyKey());
        MemoryJson.Value before = frozenBefore.<MemoryJson.Value>map(MemoryEventCodec::fact)
                .orElseGet(MemoryJson::nil);
        TypedPayload projectedPayload = new TypedPayload(
                WRITTEN_SCHEMA,
                encode(writtenValue(
                        mutation,
                        before,
                        command(mutation),
                        projectedFact(mutation, frozenBefore, frozenProposal, owner, occurredAt),
                        accessAuditRef,
                        frozenSourceAudit)));
        // payload 的无分配预算已通过；再按其真实规范字节约束 fingerprint 的二次转义。
        preflightEventFingerprint(
                target,
                eventId,
                WRITTEN_EVENT,
                projectedPayload,
                trace,
                occurredAt);
    }

    private static void preflightRetrievalPayload(RetrievalSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        budget.object(1, "query", "resultRef", "evaluatedAt", "phase", "trace", "facts", "accessAuditRef", "diagnostics");
        preflightQuery(budget, 2, snapshot.query());
        budget.string(2, snapshot.resultRef().value());
        budget.string(2, snapshot.evaluatedAt().toString());
        budget.string(2, snapshot.phase().name());
        preflightRetrievalTrace(budget, 2, snapshot.trace());
        budget.array(2, snapshot.facts().size());
        for (MemoryFact fact : snapshot.facts()) {
            preflightFact(budget, 3, fact);
        }
        budget.string(2, snapshot.accessAuditRef().value());
        preflightDiagnostics(budget, 2, snapshot.diagnostics());
    }

    private static void preflightWrittenPayload(
            MemoryRuntime.Mutation mutation,
            Optional<MemoryFact> beforeFact,
            MemoryFact fact,
            ResultRef accessAuditRef,
            Optional<ResultRef> sourceAuditRef) {
        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        preflightWrittenRoot(
                budget,
                mutation,
                beforeFact,
                accessAuditRef,
                sourceAuditRef,
                (value, depth) -> preflightFact(value, depth, fact));
    }

    private static void preflightWrittenRoot(
            MemoryEncodingBudget budget,
            MemoryRuntime.Mutation mutation,
            Optional<MemoryFact> beforeFact,
            ResultRef accessAuditRef,
            Optional<ResultRef> sourceAuditRef,
            FactPreflight writtenFact) {
        budget.object(1, "operation", "idempotencyKey", "before", "command", "fact",
                "accessAuditRef", "sourceAuditRef");
        budget.string(2, writtenOperation(mutation));
        budget.string(2, mutation.idempotencyKey().value());
        if (beforeFact.isPresent()) {
            preflightFact(budget, 2, beforeFact.orElseThrow());
        } else {
            budget.nil(2);
        }
        preflightCommand(budget, 2, mutation);
        writtenFact.accept(budget, 2);
        budget.string(2, accessAuditRef.value());
        if (sourceAuditRef.isPresent()) {
            budget.string(2, sourceAuditRef.orElseThrow().value());
        } else {
            budget.nil(2);
        }
    }

    private static void preflightCommand(
            MemoryEncodingBudget budget,
            int depth,
            MemoryRuntime.Mutation mutation) {
        switch (mutation) {
            case MemoryRuntime.Mutation.Create create -> {
                budget.object(depth, "draft");
                preflightDraft(budget, depth + 1, create.draft());
            }
            case MemoryRuntime.Mutation.Supersede supersede -> {
                budget.object(depth, "current", "expectedRevision", "replacement");
                preflightFactRef(budget, depth + 1, supersede.current());
                budget.integer(depth + 1, supersede.expectedRevision());
                preflightDraft(budget, depth + 1, supersede.replacement());
            }
            case MemoryRuntime.Mutation.AcceptRevision accept -> {
                budget.object(depth, "current", "expectedRevision", "proposal", "expectedProposalRevision");
                preflightFactRef(budget, depth + 1, accept.current());
                budget.integer(depth + 1, accept.expectedRevision());
                preflightFactRef(budget, depth + 1, accept.proposal());
                budget.integer(depth + 1, accept.expectedProposalRevision());
            }
            case MemoryRuntime.Mutation.Transition transition -> {
                budget.object(depth, "fact", "expectedRevision", "target", "reasonCode");
                preflightFactRef(budget, depth + 1, transition.fact());
                budget.integer(depth + 1, transition.expectedRevision());
                budget.string(depth + 1, transition.target().name());
                budget.string(depth + 1, transition.reasonCode());
            }
        }
    }

    private static void preflightRetrievalEventId(RunIdentity identity, RetrievalSnapshot snapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(snapshot, "snapshot");
        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        budget.object(1, "runIdentity", "queryId", "resultRef", "phase");
        preflightRunIdentity(budget, 2, identity);
        budget.string(2, snapshot.query().queryId());
        budget.string(2, snapshot.resultRef().value());
        budget.string(2, snapshot.phase().name());
    }

    private static void preflightWrittenEventId(SessionOwner owner, IdempotencyKey key) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(key, "key");
        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        budget.object(1, "owner", "idempotencyKey");
        preflightOwner(budget, 2, owner);
        budget.string(2, key.value());
    }

    private static void preflightEventFingerprint(
            SessionProjectionTarget target,
            EventId eventId,
            String eventType,
            TypedPayload payload,
            TraceEnvelope trace,
            Instant occurredAt) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(occurredAt, "occurredAt");
        MemoryEncodingBudget budget = new MemoryEncodingBudget(LIMITS);
        budget.object(1, "target", "eventId", "eventType", "eventVersion", "trace", "payload", "occurredAt");
        preflightTarget(budget, 2, target);
        budget.string(2, eventId.value());
        budget.string(2, eventType);
        budget.string(2, EVENT_VERSION);
        preflightTrace(budget, 2, trace);
        budget.payload(2, payload);
        budget.string(2, occurredAt.toString());
    }

    private static void preflightQuery(MemoryEncodingBudget budget, int depth, MemoryQuery value) {
        budget.object(depth, "queryId", "owner", "subjectEndUserId", "intent", "taskConstraint",
                "scopes", "types", "profile", "asOf", "retrievalPolicy", "maxItems",
                "tokenBudget", "characterBudget", "requireProvenance");
        budget.string(depth + 1, value.queryId());
        preflightOwner(budget, depth + 1, value.owner());
        if (value.subjectEndUserId().isPresent()) budget.integer(depth + 1, value.subjectEndUserId().orElseThrow().value());
        else budget.nil(depth + 1);
        budget.string(depth + 1, value.intent());
        if (value.taskConstraint().isPresent()) {
            budget.object(depth + 1, "taskId");
            budget.string(depth + 2, value.taskConstraint().orElseThrow().taskId());
        } else budget.nil(depth + 1);
        budget.array(depth + 1, value.scopes().size());
        for (MemoryScope scope : value.scopes()) {
            budget.string(depth + 2, scope.name());
        }
        budget.array(depth + 1, value.types().size());
        for (MemoryTypeRef type : value.types()) {
            budget.string(depth + 2, type.value());
        }
        budget.string(depth + 1, value.profile().name());
        budget.string(depth + 1, value.asOf().toString());
        preflightPolicy(budget, depth + 1, value.retrievalPolicy());
        budget.integer(depth + 1, value.maxItems());
        budget.integer(depth + 1, value.tokenBudget());
        budget.integer(depth + 1, value.characterBudget());
        budget.bool(depth + 1, value.requireProvenance());
    }

    private static void preflightDraft(
            MemoryEncodingBudget budget,
            int depth,
            MemoryFactDraft value) {
        budget.object(depth, "owner", "type", "retentionClass",
                "schemaVersion", "subject", "predicate", "object", "sourceEventIds", "sourceSessionRef",
                "sourceExecutionRef", "exportPath", "extractorVersion", "validFrom", "validTo", "expiresAt",
                "confidence", "importance", "reviewRequired", "derivedFrom", "initialStatus");
        preflightFactBody(budget, depth, value.owner(), value.type(), value.retentionClass(), value.schemaVersion(), value.subject(),
                value.predicate(), value.object(), value.sourceEventIds(), value.sourceSessionRef(),
                value.sourceExecutionRef(), value.exportPath(), value.extractorVersion(), value.validFrom(),
                value.validTo(), value.expiresAt(), value.confidence(), value.importance(), value.reviewRequired(),
                value.derivedFrom());
        budget.string(depth + 1, value.initialStatus().name());
    }

    private static void preflightFact(
            MemoryEncodingBudget budget,
            int depth,
            MemoryFact value) {
        preflightFact(budget, depth, value, value.status());
    }

    private static void preflightFact(
            MemoryEncodingBudget budget,
            int depth,
            MemoryFact value,
            FactStatus status) {
        preflightFactHeader(budget, depth);
        preflightFactRef(budget, depth + 1, value.ref());
        preflightFactBody(budget, depth, value.owner(), value.type(), value.retentionClass(), value.schemaVersion(), value.subject(),
                value.predicate(), value.object(), value.sourceEventIds(), value.sourceSessionRef(),
                value.sourceExecutionRef(), value.exportPath(), value.extractorVersion(), value.validFrom(),
                value.validTo(), value.expiresAt(), value.confidence(), value.importance(), value.reviewRequired(),
                value.derivedFrom());
        budget.integer(depth + 1, value.revision());
        budget.string(depth + 1, value.createdAt().toString());
        if (value.supersededBy().isPresent()) {
            preflightFactRef(budget, depth + 1, value.supersededBy().orElseThrow());
        } else {
            budget.nil(depth + 1);
        }
        budget.decimal(depth + 1, value.decayScore());
        budget.string(depth + 1, status.name());
    }

    private static void preflightProjectedFact(
            MemoryEncodingBudget budget,
            int depth,
            MemoryRuntime.Mutation mutation,
            Optional<MemoryFact> beforeFact,
            Optional<MemoryFact> proposalFact,
            SessionOwner owner,
            Instant occurredAt) {
        if (mutation instanceof MemoryRuntime.Mutation.AcceptRevision accept) {
            preflightFact(budget, depth, activatedProposal(proposalFact.orElseThrow(), accept.expectedProposalRevision()));
            return;
        }
        if (mutation instanceof MemoryRuntime.Mutation.Transition transition) {
            preflightFact(budget, depth, beforeFact.orElseThrow(), transition.target());
            return;
        }
        MemoryFactDraft draft = mutation instanceof MemoryRuntime.Mutation.Create create
                ? create.draft()
                : ((MemoryRuntime.Mutation.Supersede) mutation).replacement();
        preflightFactHeader(budget, depth);
        preflightFactRef(
                budget,
                depth + 1,
                owner.sessionRef().tenantId().value(),
                mutation.idempotencyKey().value());
        preflightFactBody(budget, depth, draft.owner(), draft.type(), draft.retentionClass(), draft.schemaVersion(), draft.subject(),
                draft.predicate(), draft.object(), draft.sourceEventIds(), draft.sourceSessionRef(),
                draft.sourceExecutionRef(), draft.exportPath(), draft.extractorVersion(), draft.validFrom(),
                draft.validTo(), draft.expiresAt(), draft.confidence(), draft.importance(), draft.reviewRequired(),
                draft.derivedFrom());
        budget.integer(depth + 1, 1);
        budget.string(depth + 1, occurredAt.toString());
        budget.nil(depth + 1);
        budget.decimal(depth + 1, 0d);
        budget.string(depth + 1, draft.initialStatus().name());
    }

    private static void preflightFactHeader(MemoryEncodingBudget budget, int depth) {
        budget.object(depth, "ref", "owner", "type", "retentionClass",
                "schemaVersion", "subject", "predicate", "object", "sourceEventIds", "sourceSessionRef",
                "sourceExecutionRef", "exportPath", "extractorVersion", "revision", "createdAt", "validFrom",
                "validTo", "expiresAt", "confidence", "importance", "reviewRequired", "derivedFrom",
                "supersededBy", "decayScore", "status");
    }

    private static void preflightFactBody(
            MemoryEncodingBudget budget,
            int depth,
            MemoryOwner owner,
            MemoryTypeRef type,
            RetentionClassRef retentionClass,
            String schemaVersion,
            String subject,
            String predicate,
            TypedPayload object,
            List<EventId> sourceEventIds,
            SessionRef sourceSessionRef,
            Optional<ExecutionRef> sourceExecutionRef,
            Optional<String> exportPath,
            String extractorVersion,
            Instant validFrom,
            Optional<Instant> validTo,
            Optional<Instant> expiresAt,
            double confidence,
            double importance,
            boolean reviewRequired,
            Optional<MemoryFactRef> derivedFrom) {
        preflightOwner(budget, depth + 1, owner);
        budget.string(depth + 1, type.value());
        budget.string(depth + 1, retentionClass.value());
        budget.string(depth + 1, schemaVersion);
        budget.string(depth + 1, subject);
        budget.string(depth + 1, predicate);
        budget.payload(depth + 1, object);
        budget.array(depth + 1, sourceEventIds.size());
        for (EventId sourceEventId : sourceEventIds) {
            budget.string(depth + 2, sourceEventId.value());
        }
        preflightSessionRef(budget, depth + 1, sourceSessionRef);
        if (sourceExecutionRef.isPresent()) {
            preflightExecutionRef(budget, depth + 1, sourceExecutionRef.orElseThrow());
        } else {
            budget.nil(depth + 1);
        }
        if (exportPath.isPresent()) {
            budget.string(depth + 1, exportPath.orElseThrow());
        } else {
            budget.nil(depth + 1);
        }
        budget.string(depth + 1, extractorVersion);
        budget.string(depth + 1, validFrom.toString());
        if (validTo.isPresent()) {
            budget.string(depth + 1, validTo.orElseThrow().toString());
        } else {
            budget.nil(depth + 1);
        }
        if (expiresAt.isPresent()) {
            budget.string(depth + 1, expiresAt.orElseThrow().toString());
        } else {
            budget.nil(depth + 1);
        }
        budget.decimal(depth + 1, confidence);
        budget.decimal(depth + 1, importance);
        budget.bool(depth + 1, reviewRequired);
        if (derivedFrom.isPresent()) {
            preflightFactRef(budget, depth + 1, derivedFrom.orElseThrow());
        } else {
            budget.nil(depth + 1);
        }
    }

    private static void preflightRetrievalTrace(
            MemoryEncodingBudget budget,
            int depth,
            MemoryRetrievalTrace value) {
        budget.object(depth, "queryId", "candidateIds", "selectedIds", "injectedIds", "referencedIds");
        budget.string(depth + 1, value.queryId());
        preflightStrings(budget, depth + 1, value.candidateIds());
        preflightStrings(budget, depth + 1, value.selectedIds());
        preflightStrings(budget, depth + 1, value.injectedIds());
        preflightStrings(budget, depth + 1, value.referencedIds());
    }

    private static void preflightStrings(MemoryEncodingBudget budget, int depth, List<String> values) {
        budget.array(depth, values.size());
        for (String value : values) {
            budget.string(depth + 1, value);
        }
    }

    private static void preflightRunIdentity(MemoryEncodingBudget budget, int depth, RunIdentity value) {
        budget.object(depth, "owner", "agentId", "causationInputId", "runRef", "trace", "causationIdentityRef");
        preflightOwner(budget, depth + 1, value.owner());
        budget.string(depth + 1, value.agentId().value());
        budget.string(depth + 1, value.causationInputId().value());
        preflightRunRef(budget, depth + 1, value.runRef());
        preflightTrace(budget, depth + 1, value.trace());
        budget.string(depth + 1, value.causationIdentityRef().value());
    }

    private static void preflightTarget(
            MemoryEncodingBudget budget,
            int depth,
            SessionProjectionTarget value) {
        budget.object(depth, "owner", "agentId", "inputId", "runRef", "executionRef", "actionRef", "delegationRef");
        preflightOwner(budget, depth + 1, value.owner());
        budget.string(depth + 1, value.agentId().value());
        budget.string(depth + 1, value.inputId().value());
        if (value.runRef().isPresent()) {
            preflightRunRef(budget, depth + 1, value.runRef().orElseThrow());
        } else {
            budget.nil(depth + 1);
        }
        if (value.executionRef().isPresent()) {
            preflightExecutionRef(budget, depth + 1, value.executionRef().orElseThrow());
        } else {
            budget.nil(depth + 1);
        }
        if (value.actionRef().isPresent()) {
            var ref = value.actionRef().orElseThrow();
            budget.object(depth + 1, "tenantId", "actionId");
            budget.string(depth + 2, ref.tenantId().value());
            budget.string(depth + 2, ref.actionId());
        } else {
            budget.nil(depth + 1);
        }
        if (value.delegationRef().isPresent()) {
            var ref = value.delegationRef().orElseThrow();
            budget.object(depth + 1, "tenantId", "delegationId");
            budget.string(depth + 2, ref.tenantId().value());
            budget.string(depth + 2, ref.delegationId());
        } else {
            budget.nil(depth + 1);
        }
    }

    private static void preflightOwner(MemoryEncodingBudget budget, int depth, SessionOwner value) {
        budget.object(depth, "sessionRef", "targetKind", "targetId", "subject");
        preflightSessionRef(budget, depth + 1, value.sessionRef());
        budget.string(depth + 1, value.target().kind().name());
        budget.string(depth + 1, value.target().id());
        switch (value.subject()) {
            case SessionSubject.User user -> {
                budget.object(depth + 1, "kind", "endUserId");
                budget.string(depth + 2, "USER");
                budget.integer(depth + 2, user.endUserId().value());
            }
            case SessionSubject.Group group -> {
                budget.object(depth + 1, "kind", "tenantId", "groupBindingId");
                budget.string(depth + 2, "GROUP");
                budget.string(depth + 2, group.groupBindingRef().tenantId().value());
                budget.string(depth + 2, group.groupBindingRef().groupBindingId());
            }
        }
    }

    private static void preflightTrace(MemoryEncodingBudget budget, int depth, TraceEnvelope value) {
        budget.object(depth, "traceId", "spanId", "rootInputId", "causedByEventId");
        budget.string(depth + 1, value.traceId());
        budget.string(depth + 1, value.spanId());
        budget.string(depth + 1, value.rootInputId().value());
        if (value.causedByEventId().isPresent()) {
            budget.string(depth + 1, value.causedByEventId().orElseThrow().value());
        } else {
            budget.nil(depth + 1);
        }
    }

    private static void preflightSessionRef(MemoryEncodingBudget budget, int depth, SessionRef value) {
        budget.object(depth, "tenantId", "sessionId");
        budget.string(depth + 1, value.tenantId().value());
        budget.string(depth + 1, value.sessionId());
    }

    private static void preflightRunRef(MemoryEncodingBudget budget, int depth, RunRef value) {
        budget.object(depth, "tenantId", "runId");
        budget.string(depth + 1, value.tenantId().value());
        budget.string(depth + 1, value.runId());
    }

    private static void preflightExecutionRef(MemoryEncodingBudget budget, int depth, ExecutionRef value) {
        budget.object(depth, "tenantId", "executionId");
        budget.string(depth + 1, value.tenantId().value());
        budget.string(depth + 1, value.executionId());
    }

    private static void preflightFactRef(MemoryEncodingBudget budget, int depth, MemoryFactRef value) {
        preflightFactRef(budget, depth, value.tenantId().value(), value.factId());
    }

    private static void preflightFactRef(
            MemoryEncodingBudget budget,
            int depth,
            String tenantId,
            String factId) {
        budget.object(depth, "tenantId", "factId");
        budget.string(depth + 1, tenantId);
        budget.string(depth + 1, factId);
    }

    @FunctionalInterface
    private interface FactPreflight {
        void accept(MemoryEncodingBudget budget, int depth);
    }

    private static MemoryJson.Value writtenValue(
            MemoryRuntime.Mutation mutation,
            MemoryJson.Value before,
            MemoryJson.Value command,
            MemoryJson.Value writtenFact,
            ResultRef accessAuditRef,
            Optional<ResultRef> sourceAuditRef) {
        return object(
                field("operation", string(writtenOperation(mutation))),
                field("idempotencyKey", string(mutation.idempotencyKey().value())),
                field("before", before),
                field("command", command),
                field("fact", writtenFact),
                field("accessAuditRef", string(accessAuditRef.value())),
                field("sourceAuditRef", sourceAuditRef.<MemoryJson.Value>map(
                        value -> string(value.value())).orElseGet(MemoryJson::nil)));
    }

    private static MemoryJson.Value command(MemoryRuntime.Mutation mutation) {
        return switch (mutation) {
            case MemoryRuntime.Mutation.Create value -> object(field("draft", draft(value.draft())));
            case MemoryRuntime.Mutation.Supersede value -> object(
                    field("current", factRef(value.current())),
                    field("expectedRevision", integer(value.expectedRevision())),
                    field("replacement", draft(value.replacement())));
            case MemoryRuntime.Mutation.AcceptRevision value -> object(
                    field("current", factRef(value.current())), field("expectedRevision", integer(value.expectedRevision())),
                    field("proposal", factRef(value.proposal())), field("expectedProposalRevision", integer(value.expectedProposalRevision())));
            case MemoryRuntime.Mutation.Transition value -> object(
                    field("fact", factRef(value.fact())),
                    field("expectedRevision", integer(value.expectedRevision())),
                    field("target", string(value.target().name())),
                    field("reasonCode", string(value.reasonCode())));
        };
    }

    private static String writtenOperation(MemoryRuntime.Mutation mutation) {
        return mutation instanceof MemoryRuntime.Mutation.AcceptRevision ? "ACCEPT_REVISION" : mutation.operation().name();
    }

    /** 只预测原提案激活后的编码预算，不执行事实写入。 */
    private static MemoryFact activatedProposal(MemoryFact proposal, long expectedRevision) {
        return new MemoryFact(proposal.ref(), proposal.owner(), proposal.type(), proposal.retentionClass(), proposal.schemaVersion(),
                proposal.subject(), proposal.predicate(), proposal.object(), proposal.sourceEventIds(), proposal.sourceSessionRef(),
                proposal.sourceExecutionRef(), proposal.exportPath(), proposal.extractorVersion(), Math.incrementExact(expectedRevision),
                proposal.createdAt(), proposal.validFrom(), proposal.validTo(), proposal.expiresAt(), proposal.confidence(), proposal.importance(),
                proposal.reviewRequired(), proposal.derivedFrom(), Optional.empty(), proposal.decayScore(), FactStatus.ACTIVE);
    }

    private static MemoryJson.Value projectedFact(
            MemoryRuntime.Mutation mutation,
            Optional<MemoryFact> beforeFact,
            Optional<MemoryFact> proposalFact,
            SessionOwner owner,
            Instant occurredAt) {
        if (mutation instanceof MemoryRuntime.Mutation.AcceptRevision accept) {
            return fact(activatedProposal(proposalFact.orElseThrow(), accept.expectedProposalRevision()));
        }
        if (mutation instanceof MemoryRuntime.Mutation.Transition transition) {
            return fact(beforeFact.orElseThrow(), transition.target());
        }
        MemoryFactDraft value = mutation instanceof MemoryRuntime.Mutation.Create create
                ? create.draft()
                : ((MemoryRuntime.Mutation.Supersede) mutation).replacement();
        return object(
                field("ref", object(
                        field("tenantId", string(owner.sessionRef().tenantId().value())),
                        field("factId", string(mutation.idempotencyKey().value())))),
                field("owner", memoryOwner(value.owner())),
                field("type", string(value.type().value())),
                field("retentionClass", string(value.retentionClass().value())),
                field("schemaVersion", string(value.schemaVersion())),
                field("subject", string(value.subject())),
                field("predicate", string(value.predicate())),
                field("object", payload(value.object())),
                field("sourceEventIds", array(value.sourceEventIds().stream()
                        .map(id -> string(id.value())).toList())),
                field("sourceSessionRef", sessionRef(value.sourceSessionRef())),
                field("sourceExecutionRef", optional(value.sourceExecutionRef().map(MemoryEventCodec::executionRef))),
                field("exportPath", optional(value.exportPath().map(MemoryJson::string))),
                field("extractorVersion", string(value.extractorVersion())),
                field("revision", integer(1)),
                field("createdAt", string(occurredAt.toString())),
                field("validFrom", string(value.validFrom().toString())),
                field("validTo", optional(value.validTo().map(time -> string(time.toString())))),
                field("expiresAt", optional(value.expiresAt().map(time -> string(time.toString())))),
                field("confidence", decimal(value.confidence())),
                field("importance", decimal(value.importance())),
                field("reviewRequired", bool(value.reviewRequired())),
                field("derivedFrom", optional(value.derivedFrom().map(MemoryEventCodec::factRef))),
                field("supersededBy", nil()),
                field("decayScore", decimal(0d)),
                field("status", string(value.initialStatus().name())));
    }

    private static MemoryJson.Value retrievalValue(RetrievalSnapshot snapshot) {
        return object(
                field("query", query(snapshot.query())),
                field("resultRef", string(snapshot.resultRef().value())),
                field("evaluatedAt", string(snapshot.evaluatedAt().toString())),
                field("phase", string(snapshot.phase().name())),
                field("trace", retrievalTrace(snapshot.trace())),
                field("facts", array(snapshot.facts().stream().map(MemoryEventCodec::fact).toList())),
                field("accessAuditRef", string(snapshot.accessAuditRef().value())),
                field("diagnostics", diagnostics(snapshot.diagnostics())));
    }

    private static MemoryJson.Value query(MemoryQuery value) {
        return object(
                field("queryId", string(value.queryId())),
                field("owner", memoryOwner(value.owner())),
                field("subjectEndUserId", optional(value.subjectEndUserId().map(id -> integer(id.value())))),
                field("intent", string(value.intent())),
                field("taskConstraint", optional(value.taskConstraint().map(task -> object(field("taskId", string(task.taskId())))))),
                field("scopes", sorted(value.scopes().stream().map(scope -> string(scope.name())).toList())),
                field("types", sorted(value.types().stream().map(type -> string(type.value())).toList())),
                field("profile", string(value.profile().name())),
                field("asOf", string(value.asOf().toString())),
                field("retrievalPolicy", policy(value.retrievalPolicy())),
                field("maxItems", integer(value.maxItems())),
                field("tokenBudget", integer(value.tokenBudget())),
                field("characterBudget", integer(value.characterBudget())),
                field("requireProvenance", bool(value.requireProvenance())));
    }

    private static MemoryQuery query(MemoryJson.Value value) {
        value.requireFields("queryId", "owner", "subjectEndUserId", "intent", "taskConstraint",
                "scopes", "types", "profile", "asOf", "retrievalPolicy", "maxItems",
                "tokenBudget", "characterBudget", "requireProvenance");
        return new MemoryQuery(
                value.field("queryId").stringValue(),
                memoryOwner(value.field("owner")),
                optional(value.field("subjectEndUserId"), item -> new EndUserId(positiveLong(item))),
                value.field("intent").stringValue(),
                optional(value.field("taskConstraint"), task -> { task.requireFields("taskId"); return new MemoryTaskConstraint(task.field("taskId").stringValue()); }),
                enumSet(MemoryScope.class, value.field("scopes")),
                value.field("types").arrayValue().stream()
                        .map(item -> new MemoryTypeRef(item.stringValue()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                enumeration(MemoryQueryProfile.class, value.field("profile")),
                instant(value.field("asOf")),
                policy(value.field("retrievalPolicy")),
                positiveInt(value.field("maxItems")),
                positiveInt(value.field("tokenBudget")),
                positiveInt(value.field("characterBudget")),
                value.field("requireProvenance").booleanValue());
    }

    private static MemoryJson.Value draft(MemoryFactDraft value) {
        return object(
                field("owner", memoryOwner(value.owner())),
                field("type", string(value.type().value())),
                field("retentionClass", string(value.retentionClass().value())),
                field("schemaVersion", string(value.schemaVersion())),
                field("subject", string(value.subject())),
                field("predicate", string(value.predicate())),
                field("object", payload(value.object())),
                field("sourceEventIds", array(value.sourceEventIds().stream().map(id -> string(id.value())).toList())),
                field("sourceSessionRef", sessionRef(value.sourceSessionRef())),
                field("sourceExecutionRef", optional(value.sourceExecutionRef().map(MemoryEventCodec::executionRef))),
                field("exportPath", optional(value.exportPath().map(MemoryJson::string))),
                field("extractorVersion", string(value.extractorVersion())),
                field("validFrom", string(value.validFrom().toString())),
                field("validTo", optional(value.validTo().map(time -> string(time.toString())))),
                field("expiresAt", optional(value.expiresAt().map(time -> string(time.toString())))),
                field("confidence", decimal(value.confidence())),
                field("importance", decimal(value.importance())),
                field("reviewRequired", bool(value.reviewRequired())),
                field("derivedFrom", optional(value.derivedFrom().map(MemoryEventCodec::factRef))),
                field("initialStatus", string(value.initialStatus().name())));
    }

    private static MemoryJson.Value fact(MemoryFact value) {
        return fact(value, value.status());
    }

    private static MemoryJson.Value fact(MemoryFact value, FactStatus status) {
        return object(
                field("ref", factRef(value.ref())),
                field("owner", memoryOwner(value.owner())),
                field("type", string(value.type().value())),
                field("retentionClass", string(value.retentionClass().value())),
                field("schemaVersion", string(value.schemaVersion())),
                field("subject", string(value.subject())),
                field("predicate", string(value.predicate())),
                field("object", payload(value.object())),
                field("sourceEventIds", array(value.sourceEventIds().stream().map(id -> string(id.value())).toList())),
                field("sourceSessionRef", sessionRef(value.sourceSessionRef())),
                field("sourceExecutionRef", optional(value.sourceExecutionRef().map(MemoryEventCodec::executionRef))),
                field("exportPath", optional(value.exportPath().map(MemoryJson::string))),
                field("extractorVersion", string(value.extractorVersion())),
                field("revision", integer(value.revision())),
                field("createdAt", string(value.createdAt().toString())),
                field("validFrom", string(value.validFrom().toString())),
                field("validTo", optional(value.validTo().map(time -> string(time.toString())))),
                field("expiresAt", optional(value.expiresAt().map(time -> string(time.toString())))),
                field("confidence", decimal(value.confidence())),
                field("importance", decimal(value.importance())),
                field("reviewRequired", bool(value.reviewRequired())),
                field("derivedFrom", optional(value.derivedFrom().map(MemoryEventCodec::factRef))),
                field("supersededBy", optional(value.supersededBy().map(MemoryEventCodec::factRef))),
                field("decayScore", decimal(value.decayScore())),
                field("status", string(status.name())));
    }

    private static MemoryFact fact(MemoryJson.Value value) {
        value.requireFields("ref", "owner", "type", "retentionClass",
                "schemaVersion", "subject", "predicate", "object", "sourceEventIds", "sourceSessionRef",
                "sourceExecutionRef", "exportPath", "extractorVersion", "revision", "createdAt", "validFrom",
                "validTo", "expiresAt", "confidence", "importance", "reviewRequired", "derivedFrom",
                "supersededBy", "decayScore", "status");
        return new MemoryFact(
                factRef(value.field("ref")),
                memoryOwner(value.field("owner")),
                new MemoryTypeRef(value.field("type").stringValue()),
                new RetentionClassRef(value.field("retentionClass").stringValue()),
                value.field("schemaVersion").stringValue(),
                value.field("subject").stringValue(),
                value.field("predicate").stringValue(),
                payload(value.field("object")),
                value.field("sourceEventIds").arrayValue().stream()
                        .map(item -> new EventId(item.stringValue())).toList(),
                sessionRef(value.field("sourceSessionRef")),
                optional(value.field("sourceExecutionRef"), MemoryEventCodec::executionRef),
                optional(value.field("exportPath"), item -> item.stringValue()),
                value.field("extractorVersion").stringValue(),
                positiveLong(value.field("revision")),
                instant(value.field("createdAt")),
                instant(value.field("validFrom")),
                optional(value.field("validTo"), MemoryEventCodec::instant),
                optional(value.field("expiresAt"), MemoryEventCodec::instant),
                unitDouble(value.field("confidence")),
                unitDouble(value.field("importance")),
                value.field("reviewRequired").booleanValue(),
                optional(value.field("derivedFrom"), MemoryEventCodec::factRef),
                optional(value.field("supersededBy"), MemoryEventCodec::factRef),
                unitDouble(value.field("decayScore")),
                enumeration(FactStatus.class, value.field("status")));
    }

    private static MemoryJson.Value policy(MemoryRetrievalPolicy value) {
        return object(field("id", string(value.id())), field("version", string(value.version())),
                field("projectionVersion", string(value.projectionVersion())), field("analyzerVersion", string(value.analyzerVersion())),
                field("candidateLimit", integer(value.candidateLimit())), field("rrfRankConstant", integer(value.rrfRankConstant())),
                field("minimumCosineSimilarity", decimal(value.minimumCosineSimilarity())),
                field("embeddingModel", optional(value.embeddingModel().map(model -> object(
                        field("modelConfigId", string(model.modelConfigId())), field("revision", integer(model.revision())), field("dimensions", integer(model.dimensions())))))),
                field("vectorFailurePolicy", string(value.vectorFailurePolicy().name())));
    }

    private static MemoryRetrievalPolicy policy(MemoryJson.Value value) {
        value.requireFields("id", "version", "projectionVersion", "analyzerVersion", "candidateLimit", "rrfRankConstant", "minimumCosineSimilarity", "embeddingModel", "vectorFailurePolicy");
        return new MemoryRetrievalPolicy(value.field("id").stringValue(), value.field("version").stringValue(),
                value.field("projectionVersion").stringValue(), value.field("analyzerVersion").stringValue(),
                positiveInt(value.field("candidateLimit")), positiveInt(value.field("rrfRankConstant")),
                finiteDouble(value.field("minimumCosineSimilarity")), optional(value.field("embeddingModel"), model -> {
                    model.requireFields("modelConfigId", "revision", "dimensions");
                    return new MemoryRetrievalPolicy.EmbeddingModel(model.field("modelConfigId").stringValue(), positiveLong(model.field("revision")), positiveInt(model.field("dimensions")));
                }), enumeration(MemoryRetrievalPolicy.VectorFailurePolicy.class, value.field("vectorFailurePolicy")));
    }

    private static MemoryJson.Value diagnostics(MemoryRetrievalDiagnostics value) {
        return object(field("mode", string(value.mode().name())), field("degradationReason", optional(value.degradationReason().map(MemoryJson::string))),
                field("visibleDocumentCount", integer(value.visibleDocumentCount())), field("readyVectorCount", integer(value.readyVectorCount())),
                field("candidates", array(value.candidates().stream().map(candidate -> object(
                        field("fact", factRef(candidate.fact())), field("revision", integer(candidate.revision())),
                        field("lexicalScore", optional(candidate.lexicalScore().map(MemoryJson::decimal))),
                        field("lexicalRank", optional(candidate.lexicalRank().map(MemoryJson::integer))),
                        field("cosineSimilarity", optional(candidate.cosineSimilarity().map(MemoryJson::decimal))),
                        field("vectorRank", optional(candidate.vectorRank().map(MemoryJson::integer))),
                        field("fusedScore", decimal(candidate.fusedScore())))).toList())),
                field("budgetSelectedIds", strings(value.budgetSelectedIds())));
    }

    private static MemoryRetrievalDiagnostics diagnostics(MemoryJson.Value value) {
        value.requireFields("mode", "degradationReason", "visibleDocumentCount", "readyVectorCount", "candidates", "budgetSelectedIds");
        return new MemoryRetrievalDiagnostics(enumeration(MemoryRetrievalDiagnostics.Mode.class, value.field("mode")),
                optional(value.field("degradationReason"), MemoryJson.Value::stringValue), nonNegativeLong(value.field("visibleDocumentCount")),
                nonNegativeLong(value.field("readyVectorCount")), value.field("candidates").arrayValue().stream().map(candidate -> {
                    candidate.requireFields("fact", "revision", "lexicalScore", "lexicalRank", "cosineSimilarity", "vectorRank", "fusedScore");
                    return new MemoryRetrievalDiagnostics.Candidate(factRef(candidate.field("fact")), positiveLong(candidate.field("revision")),
                            optional(candidate.field("lexicalScore"), MemoryEventCodec::finiteDouble), optional(candidate.field("lexicalRank"), MemoryEventCodec::positiveInt),
                            optional(candidate.field("cosineSimilarity"), MemoryEventCodec::finiteDouble), optional(candidate.field("vectorRank"), MemoryEventCodec::positiveInt),
                            finiteDouble(candidate.field("fusedScore")));
                }).toList(), textList(value.field("budgetSelectedIds")));
    }

    private static void preflightPolicy(MemoryEncodingBudget budget, int depth, MemoryRetrievalPolicy value) {
        budget.object(depth, "id", "version", "projectionVersion", "analyzerVersion", "candidateLimit", "rrfRankConstant", "minimumCosineSimilarity", "embeddingModel", "vectorFailurePolicy");
        budget.string(depth + 1, value.id()); budget.string(depth + 1, value.version());
        budget.string(depth + 1, value.projectionVersion()); budget.string(depth + 1, value.analyzerVersion());
        budget.integer(depth + 1, value.candidateLimit()); budget.integer(depth + 1, value.rrfRankConstant());
        budget.decimal(depth + 1, value.minimumCosineSimilarity()); budget.string(depth + 1, value.vectorFailurePolicy().name());
        if (value.embeddingModel().isPresent()) {
            var model = value.embeddingModel().orElseThrow();
            budget.object(depth + 1, "modelConfigId", "revision", "dimensions");
            budget.string(depth + 2, model.modelConfigId()); budget.integer(depth + 2, model.revision()); budget.integer(depth + 2, model.dimensions());
        } else budget.nil(depth + 1);
    }

    private static void preflightDiagnostics(MemoryEncodingBudget budget, int depth, MemoryRetrievalDiagnostics value) {
        budget.object(depth, "mode", "degradationReason", "visibleDocumentCount", "readyVectorCount", "candidates", "budgetSelectedIds");
        budget.string(depth + 1, value.mode().name());
        if (value.degradationReason().isPresent()) budget.string(depth + 1, value.degradationReason().orElseThrow()); else budget.nil(depth + 1);
        budget.integer(depth + 1, value.visibleDocumentCount()); budget.integer(depth + 1, value.readyVectorCount());
        budget.array(depth + 1, value.candidates().size());
        for (var candidate : value.candidates()) {
            budget.object(depth + 2, "fact", "revision", "lexicalScore", "lexicalRank", "cosineSimilarity", "vectorRank", "fusedScore");
            preflightFactRef(budget, depth + 3, candidate.fact()); budget.integer(depth + 3, candidate.revision());
            if (candidate.lexicalScore().isPresent()) budget.decimal(depth + 3, candidate.lexicalScore().orElseThrow()); else budget.nil(depth + 3);
            if (candidate.lexicalRank().isPresent()) budget.integer(depth + 3, candidate.lexicalRank().orElseThrow()); else budget.nil(depth + 3);
            if (candidate.cosineSimilarity().isPresent()) budget.decimal(depth + 3, candidate.cosineSimilarity().orElseThrow()); else budget.nil(depth + 3);
            if (candidate.vectorRank().isPresent()) budget.integer(depth + 3, candidate.vectorRank().orElseThrow()); else budget.nil(depth + 3);
            budget.decimal(depth + 3, candidate.fusedScore());
        }
        preflightStrings(budget, depth + 1, value.budgetSelectedIds());
    }

    private static double finiteDouble(MemoryJson.Value value) {
        double result = Double.parseDouble(value.stringValue());
        if (!Double.isFinite(result)) throw invalid("memory_score_invalid");
        return result;
    }

    private static long nonNegativeLong(MemoryJson.Value value) {
        long result = Long.parseLong(value.stringValue());
        if (result < 0) throw invalid("memory_count_invalid");
        return result;
    }

    private static MemoryJson.Value memoryOwner(MemoryOwner owner) {
        var scope = field("scope", string(owner.scope().name()));
        return switch (owner) {
            case MemoryOwner.Session value -> object(scope, field("sessionRef", sessionRef(value.sessionRef())));
            case MemoryOwner.UserAgent value -> object(scope, field("tenantId", string(value.tenantId().value())), field("endUserId", integer(value.endUserId().value())), field("agentId", string(value.agentId().value())));
            case MemoryOwner.User value -> object(scope, field("tenantId", string(value.tenantId().value())), field("endUserId", integer(value.endUserId().value())));
            case MemoryOwner.Group value -> object(scope, field("tenantId", string(value.tenantId().value())), field("groupBindingId", string(value.groupBindingRef().groupBindingId())));
            case MemoryOwner.GroupMember value -> object(scope, field("tenantId", string(value.tenantId().value())), field("groupBindingId", string(value.groupBindingRef().groupBindingId())), field("subjectEndUserId", integer(value.subjectEndUserId().value())));
        };
    }

    private static MemoryOwner memoryOwner(MemoryJson.Value value) {
        return switch (enumeration(MemoryScope.class, value.field("scope"))) {
            case SESSION -> {
                value.requireFields("scope", "sessionRef");
                yield new MemoryOwner.Session(sessionRef(value.field("sessionRef")));
            }
            case USER_AGENT -> {
                value.requireFields("scope", "tenantId", "endUserId", "agentId");
                yield new MemoryOwner.UserAgent(new TenantId(value.field("tenantId").stringValue()), new EndUserId(positiveLong(value.field("endUserId"))), new AgentId(value.field("agentId").stringValue()));
            }
            case USER -> {
                value.requireFields("scope", "tenantId", "endUserId");
                yield new MemoryOwner.User(new TenantId(value.field("tenantId").stringValue()), new EndUserId(positiveLong(value.field("endUserId"))));
            }
            case GROUP -> {
                value.requireFields("scope", "tenantId", "groupBindingId");
                yield new MemoryOwner.Group(new GroupBindingRef(new TenantId(value.field("tenantId").stringValue()), value.field("groupBindingId").stringValue()));
            }
            case GROUP_MEMBER -> {
                value.requireFields("scope", "tenantId", "groupBindingId", "subjectEndUserId");
                yield new MemoryOwner.GroupMember(new GroupBindingRef(new TenantId(value.field("tenantId").stringValue()), value.field("groupBindingId").stringValue()), new EndUserId(positiveLong(value.field("subjectEndUserId"))));
            }
        };
    }

    private static void preflightOwner(MemoryEncodingBudget budget, int depth, MemoryOwner owner) {
        switch (owner) {
            case MemoryOwner.Session value -> {
                budget.object(depth, "scope", "sessionRef");
                budget.string(depth + 1, owner.scope().name());
                preflightSessionRef(budget, depth + 1, value.sessionRef());
            }
            case MemoryOwner.UserAgent value -> {
                budget.object(depth, "scope", "tenantId", "endUserId", "agentId");
                budget.string(depth + 1, owner.scope().name());
                budget.string(depth + 1, value.tenantId().value());
                budget.integer(depth + 1, value.endUserId().value());
                budget.string(depth + 1, value.agentId().value());
            }
            case MemoryOwner.User value -> {
                budget.object(depth, "scope", "tenantId", "endUserId");
                budget.string(depth + 1, owner.scope().name());
                budget.string(depth + 1, value.tenantId().value());
                budget.integer(depth + 1, value.endUserId().value());
            }
            case MemoryOwner.Group value -> {
                budget.object(depth, "scope", "tenantId", "groupBindingId");
                budget.string(depth + 1, owner.scope().name());
                budget.string(depth + 1, value.tenantId().value());
                budget.string(depth + 1, value.groupBindingRef().groupBindingId());
            }
            case MemoryOwner.GroupMember value -> {
                budget.object(depth, "scope", "tenantId", "groupBindingId", "subjectEndUserId");
                budget.string(depth + 1, owner.scope().name());
                budget.string(depth + 1, value.tenantId().value());
                budget.string(depth + 1, value.groupBindingRef().groupBindingId());
                budget.integer(depth + 1, value.subjectEndUserId().value());
            }
        }
    }

    private static MemoryJson.Value retrievalTrace(MemoryRetrievalTrace value) {
        return object(
                field("queryId", string(value.queryId())),
                field("candidateIds", strings(value.candidateIds())),
                field("selectedIds", strings(value.selectedIds())),
                field("injectedIds", strings(value.injectedIds())),
                field("referencedIds", strings(value.referencedIds())));
    }

    private static MemoryRetrievalTrace retrievalTrace(MemoryJson.Value value) {
        value.requireFields("queryId", "candidateIds", "selectedIds", "injectedIds", "referencedIds");
        return new MemoryRetrievalTrace(
                value.field("queryId").stringValue(),
                textList(value.field("candidateIds")),
                textList(value.field("selectedIds")),
                textList(value.field("injectedIds")),
                textList(value.field("referencedIds")));
    }

    private static MemoryJson.Value runIdentity(RunIdentity value) {
        return object(
                field("owner", owner(value.owner())),
                field("agentId", string(value.agentId().value())),
                field("causationInputId", string(value.causationInputId().value())),
                field("runRef", runRef(value.runRef())),
                field("trace", trace(value.trace())),
                field("causationIdentityRef", string(value.causationIdentityRef().value())));
    }

    private static MemoryJson.Value target(SessionProjectionTarget value) {
        return object(
                field("owner", owner(value.owner())),
                field("agentId", string(value.agentId().value())),
                field("inputId", string(value.inputId().value())),
                field("runRef", optional(value.runRef().map(MemoryEventCodec::runRef))),
                field("executionRef", optional(value.executionRef().map(MemoryEventCodec::executionRef))),
                field("actionRef", optional(value.actionRef().map(ref -> object(
                        field("tenantId", string(ref.tenantId().value())), field("actionId", string(ref.actionId())))))),
                field("delegationRef", optional(value.delegationRef().map(ref -> object(
                        field("tenantId", string(ref.tenantId().value())), field("delegationId", string(ref.delegationId())))))));
    }

    private static MemoryJson.Value owner(SessionOwner value) {
        MemoryJson.Value subject = switch (value.subject()) {
            case SessionSubject.User user -> object(field("kind", string("USER")), field("endUserId", integer(user.endUserId().value())));
            case SessionSubject.Group group -> object(field("kind", string("GROUP")),
                    field("tenantId", string(group.groupBindingRef().tenantId().value())),
                    field("groupBindingId", string(group.groupBindingRef().groupBindingId())));
        };
        return object(field("sessionRef", sessionRef(value.sessionRef())),
                field("targetKind", string(value.target().kind().name())),
                field("targetId", string(value.target().id())), field("subject", subject));
    }

    private static MemoryJson.Value trace(TraceEnvelope value) {
        return object(
                field("traceId", string(value.traceId())),
                field("spanId", string(value.spanId())),
                field("rootInputId", string(value.rootInputId().value())),
                field("causedByEventId", optional(value.causedByEventId().map(id -> string(id.value())))));
    }

    private static MemoryJson.Value payload(TypedPayload value) {
        return object(field("schemaId", string(value.schemaId())), field("canonicalJson", string(value.canonicalJson())));
    }

    private static TypedPayload payload(MemoryJson.Value value) {
        value.requireFields("schemaId", "canonicalJson");
        return new TypedPayload(value.field("schemaId").stringValue(), value.field("canonicalJson").stringValue());
    }

    private static MemoryJson.Value sessionRef(SessionRef value) {
        return object(field("tenantId", string(value.tenantId().value())), field("sessionId", string(value.sessionId())));
    }

    private static SessionRef sessionRef(MemoryJson.Value value) {
        value.requireFields("tenantId", "sessionId");
        return new SessionRef(new TenantId(value.field("tenantId").stringValue()), value.field("sessionId").stringValue());
    }

    private static MemoryJson.Value runRef(RunRef value) {
        return object(field("tenantId", string(value.tenantId().value())), field("runId", string(value.runId())));
    }

    private static MemoryJson.Value executionRef(ExecutionRef value) {
        return object(field("tenantId", string(value.tenantId().value())), field("executionId", string(value.executionId())));
    }

    private static ExecutionRef executionRef(MemoryJson.Value value) {
        value.requireFields("tenantId", "executionId");
        return new ExecutionRef(new TenantId(value.field("tenantId").stringValue()), value.field("executionId").stringValue());
    }

    private static MemoryJson.Value factRef(MemoryFactRef value) {
        return object(field("tenantId", string(value.tenantId().value())), field("factId", string(value.factId())));
    }

    private static MemoryFactRef factRef(MemoryJson.Value value) {
        value.requireFields("tenantId", "factId");
        return new MemoryFactRef(new TenantId(value.field("tenantId").stringValue()), value.field("factId").stringValue());
    }

    private static MemoryJson.Value optional(Optional<MemoryJson.Value> value) {
        return value.orElseGet(MemoryJson::nil);
    }

    private static <T> Optional<T> optional(MemoryJson.Value value, java.util.function.Function<MemoryJson.Value, T> decoder) {
        return value.isNull() ? Optional.empty() : Optional.of(decoder.apply(value));
    }

    private static MemoryJson.Value strings(List<String> values) {
        return array(values.stream().map(MemoryJson::string).toList());
    }

    private static List<String> textList(MemoryJson.Value value) {
        return value.arrayValue().stream().map(MemoryJson.Value::stringValue).toList();
    }

    private static MemoryJson.Value sorted(List<MemoryJson.Value> values) {
        return array(values.stream().sorted(Comparator.comparing(MemoryEventCodec::utf8,
                MemoryEventCodec::compareUnsigned)).toList());
    }

    private static byte[] utf8(MemoryJson.Value value) {
        return encode(value).getBytes(StandardCharsets.UTF_8);
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int count = Math.min(left.length, right.length);
        for (int index = 0; index < count; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static <E extends Enum<E>> Set<E> enumSet(Class<E> type, MemoryJson.Value value) {
        List<MemoryJson.Value> values = value.arrayValue();
        HashSet<E> result = new HashSet<>();
        for (MemoryJson.Value item : values) {
            if (!result.add(enumeration(type, item))) {
                throw invalid("memory_duplicate_enum");
            }
        }
        return Set.copyOf(result);
    }

    private static <E extends Enum<E>> E enumeration(Class<E> type, MemoryJson.Value value) {
        try {
            return Enum.valueOf(type, value.stringValue());
        } catch (IllegalArgumentException exception) {
            throw invalid("memory_unknown_enum");
        }
    }

    private static Instant instant(MemoryJson.Value value) {
        String text = value.stringValue();
        try {
            Instant instant = Instant.parse(text);
            if (!instant.toString().equals(text)) {
                throw invalid("memory_instant_not_canonical");
            }
            return instant;
        } catch (DateTimeParseException exception) {
            throw invalid("memory_instant_invalid");
        }
    }

    private static long positiveLong(MemoryJson.Value value) {
        String text = value.stringValue();
        try {
            long result = Long.parseLong(text);
            if (result <= 0 || !Long.toString(result).equals(text)) {
                throw invalid("memory_integer_invalid");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw invalid("memory_integer_invalid");
        }
    }

    private static int positiveInt(MemoryJson.Value value) {
        long result = positiveLong(value);
        if (result > Integer.MAX_VALUE) {
            throw invalid("memory_integer_invalid");
        }
        return (int) result;
    }

    private static double unitDouble(MemoryJson.Value value) {
        String text = value.stringValue();
        try {
            double result = Double.parseDouble(text);
            if (!Double.isFinite(result) || result < 0 || result > 1
                    || !MemoryJson.decimal(result).stringValue().equals(text)) {
                throw invalid("memory_decimal_invalid");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw invalid("memory_decimal_invalid");
        }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException(reason);
    }
}
