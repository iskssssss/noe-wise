package com.skillforge.runtime.memory;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryRetrievalTrace;
import com.skillforge.runtime.api.MemoryRetrievalDiagnostics;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryCall;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryOutcome;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryResult;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionLogPort.AppendResult;
import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import com.skillforge.runtime.api.port.SessionLogPort.ReadSessionLog;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 协调 MemoryPort、来源事实与 SessionLog；不实现存储、默认策略或 Core 上下文选择。
 */
public final class MemoryRuntime {
    private static final int RETRIEVAL_SCAN_PAGE_SIZE = 128;
    private static final int CURRENT_FACT_BATCH_SIZE = 200;
    private final MemoryPort memory;
    private final SessionLogPort log;
    private final MemoryAccessPolicy access;
    private final MemoryTypeRegistry types;
    private final MemorySourcePolicy sources;
    private final CurrentFactSource currentFacts;
    private final Clock clock;

    /** 所有权限、类型、来源与当前事实能力都必须显式注入。 */
    public MemoryRuntime(
            MemoryPort memory,
            SessionLogPort log,
            MemoryAccessPolicy access,
            MemoryTypeRegistry types,
            MemorySourcePolicy sources,
            CurrentFactSource currentFacts,
            Clock clock) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.log = Objects.requireNonNull(log, "log");
        this.access = Objects.requireNonNull(access, "access");
        this.types = Objects.requireNonNull(types, "types");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.currentFacts = Objects.requireNonNull(currentFacts, "currentFacts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 查询并先记录 CANDIDATE；只有精确 SessionLog ACK 才返回可供 Core 选择的候选回执。
     */
    public RetrievalResult retrieve(RetrievalCall call) {
        Objects.requireNonNull(call, "call");
        Failure stopped = liveFailure(call.context());
        if (stopped != null) {
            return new RetrievalResult.Stopped(stopped);
        }
        Access accessResult = authorize(
                call.context(),
                call.query().owner(),
                MemoryAccessPolicy.Operation.QUERY,
                call.query().scopes(),
                call.query().types(),
                Optional.empty());
        if (accessResult.failure() != null) {
            return new RetrievalResult.Stopped(accessResult.failure());
        }
        try {
            requireQuery(call.context(), call.query());
        } catch (RuntimeException exception) {
            return stopped("memory_query_contract_rejected");
        }
        try {
            MemoryEventCodec.preflightRetrieval(
                    call.context().runIdentity(),
                    call.query(),
                    accessResult.auditRef(),
                    target(call.context()));
        } catch (RuntimeException exception) {
            return stopped("memory_event_encoding_rejected");
        }
        try {
            requireQueryTypes(call.query());
        } catch (RuntimeException exception) {
            return stopped("memory_query_contract_rejected");
        }

        MemoryQueryOutcome outcome;
        try {
            outcome = Objects.requireNonNull(memory.query(new MemoryQueryCall(call.context(), call.query())));
        } catch (RuntimeException exception) {
            return stopped("memory_query_failed");
        }
        if (outcome instanceof MemoryQueryOutcome.Failed failed) {
            return new RetrievalResult.Stopped(failure(failed.failure()));
        }
        if (!(outcome instanceof MemoryQueryOutcome.Retrieved retrieved)) {
            return stopped("memory_query_outcome_invalid");
        }

        MemoryQueryResult result = overlayAdoptedPreferences(retrieved.result());
        try {
            MemoryEventCodec.preflightRetrieved(call.query(), result, accessResult.auditRef());
        } catch (RuntimeException exception) {
            return stopped("memory_event_encoding_rejected");
        }
        try {
            requireResult(call.context(), call.query(), result, true);
        } catch (RuntimeException exception) {
            return stopped("memory_query_result_rejected");
        }
        stopped = liveFailure(call.context());
        if (stopped != null) {
            return new RetrievalResult.Stopped(stopped);
        }

        List<String> candidateIds = result.candidates().stream().map(fact -> fact.ref().factId()).toList();
        MemoryRetrievalTrace trace = new MemoryRetrievalTrace(
                call.query().queryId(), candidateIds, List.of(), List.of(), List.of());
        MemoryEventCodec.RetrievalSnapshot snapshot = new MemoryEventCodec.RetrievalSnapshot(
                call.query(), result.candidates(), result.resultRef(), result.evaluatedAt(),
                Phase.CANDIDATE, trace, accessResult.auditRef(), result.diagnostics());
        try {
            PendingRetrieval pending = pending(call.context(), snapshot, result.evaluatedAt(), 0);
            return appendRetrieval(pending);
        } catch (RuntimeException exception) {
            return stopped("memory_event_encoding_rejected");
        }
    }

    /** 重放完全冻结的候选或后续轨迹，不调用 MemoryPort 或当前类型注册表。 */
    public RetrievalReceipt replayRetrieval(PortCallContext context, CommittedSessionEvent event) {
        RetrievalReceipt receipt = reconstructRetrieval(context, event);
        requireCurrentMaterial(context, receipt.snapshot);
        return receipt;
    }

    private RetrievalReceipt reconstructRetrieval(PortCallContext context, CommittedSessionEvent event) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(event, "event");
        SessionProjectionTarget expectedTarget = target(context);
        requireEventTarget(expectedTarget, event);
        if (!MemoryEventCodec.RETRIEVAL_EVENT.equals(event.eventType())
                || !MemoryEventCodec.EVENT_VERSION.equals(event.eventVersion())) {
            throw failureState("memory_replay_event_type_mismatch");
        }
        MemoryEventCodec.RetrievalSnapshot snapshot = MemoryEventCodec.decodeRetrieval(event.payload());
        requireQuery(context, snapshot.query());
        requireResult(context, snapshot.query(), new MemoryQueryResult(
                snapshot.query().queryId(), snapshot.facts(), snapshot.resultRef(), snapshot.evaluatedAt(), snapshot.diagnostics()), false);
        requireTrace(snapshot);
        if (snapshot.phase() == Phase.CANDIDATE
                ? !event.occurredAt().equals(snapshot.evaluatedAt())
                : event.occurredAt().isBefore(snapshot.evaluatedAt())) {
            throw failureState("memory_replay_event_time_mismatch");
        }
        EventId eventId = MemoryEventCodec.retrievalEventId(context.runIdentity(), snapshot);
        if (!event.eventId().equals(eventId)
                || !event.trace().equals(context.runIdentity().trace())
                || !event.eventType().equals(MemoryEventCodec.RETRIEVAL_EVENT)
                || !event.eventVersion().equals(MemoryEventCodec.EVENT_VERSION)
                || !event.payload().equals(MemoryEventCodec.retrievalPayload(snapshot))) {
            throw failureState("memory_replay_event_mismatch");
        }
        return new RetrievalReceipt(this, context, snapshot, event);
    }

    /**
     * 在当前 Run 的完整投影目标内查找稳定 queryId 对应的最新冻结阶段。
     *
     * <p>查询链仍以 SessionLog 为唯一事实源；返回前重新检查当前调用控制和查询权限，
     * 但不改写原始快照中的授权审计引用。
     */
    public Optional<RetrievalReceipt> findLatestRetrieval(
            PortCallContext context,
            String queryId) {
        Objects.requireNonNull(context, "context");
        if (queryId == null || queryId.isBlank()) {
            throw new IllegalArgumentException("queryId must not be blank");
        }
        Failure stopped = liveFailure(context);
        if (stopped != null) {
            throw failureState(stopped.reasonCode());
        }

        SessionProjectionTarget expectedTarget = target(context);
        RetrievalReceipt latest = null;
        long cursor = 0;
        while (true) {
            List<CommittedSessionEvent> page;
            try {
                page = Objects.requireNonNull(log.readAfter(
                        new ReadSessionLog(expectedTarget.owner(), cursor, RETRIEVAL_SCAN_PAGE_SIZE),
                        Set.of(MemoryEventCodec.RETRIEVAL_EVENT)));
            } catch (RuntimeException exception) {
                throw failureState("memory_retrieval_scan_failed");
            }
            if (page.size() > RETRIEVAL_SCAN_PAGE_SIZE) {
                throw failureState("memory_retrieval_scan_page_invalid");
            }
            if (page.isEmpty()) {
                break;
            }

            long previousSequence = cursor;
            for (CommittedSessionEvent event : page) {
                if (event == null
                        || !event.owner().equals(expectedTarget.owner())
                        || event.sessionSequence() <= previousSequence
                        || !MemoryEventCodec.RETRIEVAL_EVENT.equals(event.eventType())) {
                    throw failureState("memory_retrieval_scan_page_invalid");
                }
                previousSequence = event.sessionSequence();
                if (!matchesTarget(expectedTarget, event)) {
                    continue;
                }
                RetrievalReceipt replayed;
                try {
                    replayed = reconstructRetrieval(context, event);
                } catch (RuntimeException exception) {
                    throw failureState("memory_retrieval_chain_invalid");
                }
                if (!replayed.query().queryId().equals(queryId)) {
                    continue;
                }
                if (latest == null) {
                    if (replayed.phase() != Phase.CANDIDATE) {
                        throw failureState("memory_retrieval_chain_invalid");
                    }
                } else {
                    requireRetrievalContinuation(latest.snapshot, replayed.snapshot);
                }
                latest = replayed;
            }
            if (previousSequence <= cursor) {
                throw failureState("memory_retrieval_scan_cursor_invalid");
            }
            cursor = previousSequence;
            if (page.size() < RETRIEVAL_SCAN_PAGE_SIZE) {
                break;
            }
        }
        if (latest == null) {
            return Optional.empty();
        }

        stopped = liveFailure(context);
        if (stopped != null) {
            throw failureState(stopped.reasonCode());
        }
        Access currentAccess = authorize(
                context,
                latest.query().owner(),
                MemoryAccessPolicy.Operation.QUERY,
                latest.query().scopes(),
                latest.query().types(),
                Optional.empty());
        if (currentAccess.failure() != null) {
            throw failureState(currentAccess.failure().reasonCode());
        }
        stopped = liveFailure(context);
        if (stopped != null) {
            throw failureState(stopped.reasonCode());
        }
        requireCurrentFacts(context, latest.snapshot);
        return Optional.of(latest);
    }

    /** 供 Session 冻结来源识别 Runtime Memory 检索事件，不要求 Session 依赖私有 codec。 */
    public static boolean isRetrievalEvent(CommittedSessionEvent event) {
        return event != null && MemoryEventCodec.RETRIEVAL_EVENT.equals(event.eventType())
                && MemoryEventCodec.EVENT_VERSION.equals(event.eventVersion())
                && MemoryEventCodec.RETRIEVAL_SCHEMA.equals(event.payload().schemaId());
    }

    /**
     * 按 CANDIDATE→SELECTED→INJECTED→REFERENCED 推进；Core 只提供收窄结果，不反向依赖本模块。
     */
    public RetrievalResult advance(
            RetrievalReceipt previous,
            Phase next,
            MemoryRetrievalTrace trace,
            Instant occurredAt) {
        requireReceiptOwned(previous);
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Failure stopped = liveFailure(previous.context);
        if (stopped != null) {
            return new RetrievalResult.Stopped(stopped);
        }
        try { requireCurrentMaterial(previous.context, previous.snapshot); }
        catch (RuntimeException exception) { return stopped(reason(exception, "memory_current_governance_unavailable")); }
        try {
            if (next.ordinal() != previous.snapshot.phase().ordinal() + 1
                    || occurredAt.isBefore(previous.event.occurredAt())) {
                throw failureState("memory_retrieval_phase_invalid");
            }
            requireAdvance(previous.snapshot, next, trace);
        } catch (RuntimeException exception) {
            return stopped("memory_retrieval_phase_rejected");
        }
        try {
            MemoryEventCodec.RetrievalSnapshot snapshot = new MemoryEventCodec.RetrievalSnapshot(
                    previous.snapshot.query(), previous.snapshot.facts(), previous.snapshot.resultRef(),
                    previous.snapshot.evaluatedAt(), next, trace, previous.snapshot.accessAuditRef(), previous.snapshot.diagnostics());
            PendingRetrieval pending = pending(
                    previous.context, snapshot, occurredAt, previous.event.sessionSequence());
            return appendRetrieval(pending);
        } catch (RuntimeException exception) {
            return stopped("memory_event_encoding_rejected");
        }
    }

    /** 对未确认的检索事件只重放原 SessionLog append，不重新查询或重新选择。 */
    public RetrievalResult retry(PendingRetrieval pending) {
        requirePending(pending);
        Failure stopped = liveFailure(pending.context);
        return stopped == null ? appendRetrieval(pending) : new RetrievalResult.Stopped(stopped);
    }

    /**
     * 提交 Create/Supersede/Transition，并只在 memory.written 得到精确 ACK 后返回 Recorded。
     */
    public MutationResult mutate(MutationCall call) {
        Objects.requireNonNull(call, "call");
        Failure stopped = liveFailure(call.context());
        if (stopped != null) {
            return new MutationResult.Stopped(stopped);
        }

        Mutation mutation = call.mutation();
        Optional<MemoryFact> current;
        Optional<MemoryFact> proposal = Optional.empty();
        try {
            if (mutation instanceof Mutation.AcceptRevision accept) {
                List<MemoryFact> pair = requireRevisionPair(call.context(), accept);
                current = Optional.of(pair.get(0));
                proposal = Optional.of(pair.get(1));
            } else {
                current = switch (mutation) {
                    case Mutation.Create ignored -> Optional.empty();
                    case Mutation.Supersede value -> Optional.of(requireCurrent(call.context(), value.current(), value.scope(), value.type()));
                    case Mutation.Transition value -> Optional.of(requireCurrent(call.context(), value.fact(), value.scope(), value.type()));
                    case Mutation.AcceptRevision ignored -> throw new AssertionError("handled by batch read");
                };
            }
        } catch (RuntimeException exception) {
            return new MutationResult.Stopped(new Failure(reason(exception, "memory_mutation_contract_rejected"), Optional.empty()));
        }
        MemoryOwner mutationOwner = current.map(MemoryFact::owner).orElseGet(() -> ((Mutation.Create) mutation).draft().owner());
        Access accessResult = authorize(call.context(), mutationOwner, mutation.operation(),
                Set.of(mutation.scope()), Set.of(mutation.type()), mutation.factRef());
        if (accessResult.failure() != null) return new MutationResult.Stopped(accessResult.failure());
        try {
            MemoryEventCodec.preflightMutation(mutation);
        } catch (RuntimeException exception) {
            return new MutationResult.Stopped(
                    new Failure("memory_event_encoding_rejected", Optional.empty()));
        }

        PreparedMutation prepared;
        try {
            prepared = prepare(call.context(), mutation, accessResult.auditRef(), current, proposal);
        } catch (RuntimeException exception) {
            if (exception instanceof PolicyRejected rejected) {
                return new MutationResult.Stopped(
                        new Failure(rejected.getMessage(), Optional.of(rejected.auditRef)));
            }
            return new MutationResult.Stopped(
                    new Failure(reason(exception, "memory_mutation_contract_rejected"), Optional.empty()));
        }
        stopped = liveFailure(call.context());
        if (stopped != null) {
            return new MutationResult.Stopped(stopped);
        }
        return submit(new PendingSubmission(this, call.context(), call.mutation(), call.occurredAt(), prepared));
    }

    /** 对未知结果复用原命令与来源快照，重新验证当前权限后读取同键确定结果。 */
    public MutationResult retry(PendingSubmission pending) {
        requirePending(pending);
        MemoryOwner owner = pending.prepared.before().map(MemoryFact::owner)
                .orElseGet(() -> ((Mutation.Create) pending.mutation).draft().owner());
        Access currentAccess = authorize(pending.context, owner, pending.mutation.operation(),
                Set.of(pending.mutation.scope()), Set.of(pending.mutation.type()), pending.mutation.factRef());
        if (currentAccess.failure() != null) return new MutationResult.Stopped(currentAccess.failure());
        return submit(pending);
    }

    private MutationResult submit(PendingSubmission pending) {
        Mutation mutation = pending.mutation;
        PreparedMutation prepared = pending.prepared;
        try {
            MemoryEventCodec.preflightSubmission(
                    mutation,
                    prepared.before(),
                    prepared.proposal(),
                    pending.context.runIdentity().owner(),
                    prepared.accessAuditRef(),
                    prepared.sourceAuditRef(),
                    target(pending.context),
                    pending.context.runIdentity().trace(),
                    pending.occurredAt);
        } catch (RuntimeException exception) {
            return new MutationResult.Stopped(
                    new Failure("memory_event_encoding_rejected", Optional.empty()));
        }
        MemoryMutationResult outcome;
        try {
            outcome = Objects.requireNonNull(memory.submit(prepared.command()));
        } catch (RuntimeException exception) {
            return new MutationResult.Uncertain(
                    pending, new Failure("memory_submit_outcome_unknown", Optional.empty()));
        }
        if (outcome instanceof MemoryMutationResult.Rejected rejected) {
            return new MutationResult.Rejected(rejected.failure());
        }
        if (outcome instanceof MemoryMutationResult.Conflict conflict) {
            try {
                requireConflict(mutation, conflict, pending.context.runIdentity().owner());
                return new MutationResult.Conflict(
                        conflict.fact(), conflict.currentRevision(), conflict.auditRef());
            } catch (RuntimeException exception) {
                return new MutationResult.Uncertain(
                        pending, new Failure("memory_conflict_ack_mismatch", Optional.empty()));
            }
        }
        if (!(outcome instanceof MemoryMutationResult.Applied applied)) {
            return new MutationResult.Uncertain(
                    pending, new Failure("memory_mutation_outcome_invalid", Optional.empty()));
        }
        try {
            MemoryEventCodec.preflightAppliedFact(applied.fact());
        } catch (RuntimeException exception) {
            return new MutationResult.Uncertain(
                    pending, new Failure("memory_event_encoding_rejected", Optional.empty()));
        }
        try {
            validateAppliedBefore(pending.context, prepared, applied);
            prepared = new PreparedMutation(prepared.mutation(), prepared.command(), applied.before(), prepared.proposal(),
                    prepared.accessAuditRef(), prepared.sourceAuditRef());
            requireApplied(pending.context, prepared, applied.fact());
        } catch (RuntimeException exception) {
            return new MutationResult.Uncertain(
                    pending, new Failure("memory_mutation_ack_mismatch", Optional.empty()));
        }

        if (applied.replayed()) {
            try {
                EventId eventId = MemoryEventCodec.writtenEventId(pending.context.runIdentity().owner(), mutation.idempotencyKey());
                Optional<CommittedSessionEvent> original = Objects.requireNonNull(log.findByEvent(pending.context.runIdentity().owner(), eventId));
                if (original.isPresent()) {
                    CommittedSessionEvent event = original.orElseThrow();
                    if (!event.owner().equals(pending.context.runIdentity().owner())
                            || !event.agentId().equals(pending.context.runIdentity().agentId())
                            || !event.eventType().equals(MemoryEventCodec.WRITTEN_EVENT)
                            || !event.eventVersion().equals(MemoryEventCodec.EVENT_VERSION)
                            || !MemoryEventCodec.matchesWritten(event.payload(), mutation, prepared.before(), applied.fact())) {
                        throw failureState("memory_replayed_event_mismatch");
                    }
                    return new MutationResult.Recorded(applied.fact(), event, true);
                }
            } catch (RuntimeException exception) {
                return new MutationResult.Uncertain(pending, new Failure(reason(exception, "memory_replayed_event_read_failed"), Optional.empty()));
            }
        }

        PendingMutation recordPending;
        try {
            var payload = MemoryEventCodec.writtenPayload(
                    mutation, prepared.before(), applied.fact(), prepared.accessAuditRef(), prepared.sourceAuditRef());
            var target = target(pending.context);
            var eventId = MemoryEventCodec.writtenEventId(
                    pending.context.runIdentity().owner(), mutation.idempotencyKey());
            var draft = new SessionEventDraft(
                    eventId,
                    MemoryEventCodec.WRITTEN_EVENT,
                    MemoryEventCodec.EVENT_VERSION,
                    MemoryEventCodec.eventFingerprint(target, eventId, MemoryEventCodec.WRITTEN_EVENT,
                            payload, pending.context.runIdentity().trace(), pending.occurredAt),
                    pending.context.runIdentity().trace(),
                    payload,
                    pending.occurredAt);
            recordPending = new PendingMutation(
                    this, pending.context, mutation, applied.fact(), applied.replayed(),
                    new AppendSessionEvent(target, draft));
        } catch (RuntimeException exception) {
            return new MutationResult.Uncertain(
                    pending, new Failure("memory_event_encoding_rejected", Optional.empty()));
        }
        return appendMutation(recordPending);
    }

    /** 已应用事实的日志补记只重放冻结事件，即使调用随后取消也必须尝试闭合审计。 */
    public MutationResult retry(PendingMutation pending) {
        requirePending(pending);
        return appendMutation(pending);
    }

    private PreparedMutation prepare(
            PortCallContext context,
            Mutation mutation,
            ResultRef accessAuditRef,
            Optional<MemoryFact> loadedCurrent,
            Optional<MemoryFact> loadedProposal) {
        return switch (mutation) {
            case Mutation.Create create -> {
                SourceAuthorization source = requireDraft(context, create.draft());
                yield new PreparedMutation(
                        mutation,
                        new MemoryCommand.Create(context, create.idempotencyKey(), create.draft()),
                        Optional.empty(), Optional.empty(),
                        accessAuditRef,
                        Optional.of(source.auditRef()));
            }
            case Mutation.Supersede supersede -> {
                MemoryFact current = loadedCurrent.orElseThrow();
                requireType(current);
                requireSourceEvents(context, current, false);
                // revision 已变化时由权威 Port 判定 CAS 并记录冲突；身份与来源校验仍须先通过。
                if ((current.revision() == supersede.expectedRevision() && current.status() != FactStatus.ACTIVE)
                        || !supersede.replacement().derivedFrom().equals(Optional.of(current.ref()))
                        || !supersede.replacement().owner().equals(current.owner())
                        || !supersede.replacement().type().equals(current.type())) {
                    throw failureState("memory_supersede_source_mismatch");
                }
                SourceAuthorization source = requireDraft(context, supersede.replacement());
                yield new PreparedMutation(
                        mutation,
                        new MemoryCommand.Supersede(context, supersede.idempotencyKey(), current.ref(),
                                supersede.expectedRevision(), supersede.replacement()),
                        Optional.of(current), Optional.empty(),
                        accessAuditRef,
                        Optional.of(source.auditRef()));
            }
            case Mutation.AcceptRevision accept -> {
                MemoryFact current = loadedCurrent.orElseThrow();
                MemoryFact proposal = loadedProposal.orElseThrow();
                requireType(current);
                requireType(proposal);
                requireSourceEvents(context, current, false);
                if (!current.owner().equals(proposal.owner()) || !current.type().equals(proposal.type())
                        || !proposal.derivedFrom().equals(Optional.of(current.ref()))
                        || current.revision() == accept.expectedRevision() && (current.status() != FactStatus.ACTIVE || current.supersededBy().isPresent())
                        || proposal.revision() == accept.expectedProposalRevision() && (proposal.status() != FactStatus.PENDING_CONFIRM || proposal.supersededBy().isPresent())) {
                    throw failureState("memory_accept_revision_source_mismatch");
                }
                MemoryFactDraft draft = proposalDraft(proposal);
                List<CommittedSessionEvent> events = requireSourceEvents(context, proposal, false);
                SourceAuthorization source = authorizeSource(context, draft, events);
                yield new PreparedMutation(mutation,
                        new MemoryCommand.AcceptRevision(context, accept.idempotencyKey(), current.ref(), accept.expectedRevision(),
                                proposal.ref(), accept.expectedProposalRevision()), Optional.of(current), Optional.of(proposal),
                        accessAuditRef, Optional.of(source.auditRef()));
            }
            case Mutation.Transition transition -> {
                MemoryFact current = loadedCurrent.orElseThrow();
                requireType(current);
                requireSourceEvents(context, current, false);
                if (current.revision() == transition.expectedRevision()) {
                    // 带原事实引用的待审修订只能通过双 CAS 接受，不能单边激活。
                    if (current.status() == FactStatus.PENDING_CONFIRM && current.derivedFrom().isPresent()
                            && transition.target() == FactStatus.ACTIVE) throw failureState("memory_revision_acceptance_required");
                    requireTransition(current.status(), transition.target());
                }
                yield new PreparedMutation(
                        mutation,
                        new MemoryCommand.Transition(context, transition.idempotencyKey(), current.ref(),
                                transition.expectedRevision(), transition.target(), transition.reasonCode()),
                        Optional.of(current), Optional.empty(),
                        accessAuditRef,
                        Optional.empty());
            }
        };
    }

    private List<MemoryFact> requireRevisionPair(PortCallContext context, Mutation.AcceptRevision accept) {
        List<MemoryFactRef> refs = List.of(accept.current(), accept.proposal());
        List<Optional<MemoryFact>> found = Objects.requireNonNull(memory.findCurrent(new MemoryPort.CurrentFactReadBatch(refs.stream()
                .map(ref -> new MemoryPort.CurrentFactRead(context, ref)).toList())));
        if (found.size() != refs.size()) throw failureState("memory_current_governance_batch_mismatch");
        List<MemoryFact> result = new ArrayList<>();
        for (int index = 0; index < refs.size(); index++) {
            MemoryFact fact = Objects.requireNonNull(found.get(index)).orElseThrow(() -> failureState("memory_current_fact_missing"));
            MemoryEventCodec.preflightAppliedFact(fact);
            if (!fact.ref().equals(refs.get(index)) || fact.revision() == Long.MAX_VALUE
                    || fact.scope() != accept.scope() || !fact.type().equals(accept.type())) throw failureState("memory_current_fact_mismatch");
            requireFactOwner(context, fact, false);
            result.add(fact);
        }
        return List.copyOf(result);
    }

    private static MemoryFactDraft proposalDraft(MemoryFact proposal) {
        return new MemoryFactDraft(proposal.owner(), proposal.type(), proposal.retentionClass(), proposal.schemaVersion(),
                proposal.subject(), proposal.predicate(), proposal.object(), proposal.sourceEventIds(), proposal.sourceSessionRef(),
                proposal.sourceExecutionRef(), proposal.exportPath(), proposal.extractorVersion(), proposal.validFrom(),
                proposal.validTo(), proposal.expiresAt(), proposal.confidence(), proposal.importance(), proposal.reviewRequired(),
                proposal.derivedFrom(), FactStatus.ACTIVE);
    }

    private MemoryFact requireCurrent(
            PortCallContext context,
            MemoryFactRef ref,
            MemoryScope expectedScope,
            MemoryTypeRef expectedType) {
        Optional<MemoryFact> loaded = Objects.requireNonNull(currentFacts.find(context, ref));
        MemoryFact current = loaded.orElseThrow(() -> failureState("memory_current_fact_missing"));
        try {
            MemoryEventCodec.preflightAppliedFact(current);
        } catch (RuntimeException exception) {
            throw failureState("memory_event_encoding_rejected");
        }
        if (!current.ref().equals(ref)
                || current.revision() == Long.MAX_VALUE
                || current.scope() != expectedScope
                || !current.type().equals(expectedType)) {
            throw failureState("memory_current_fact_mismatch");
        }
        requireFactOwner(context, current, false);
        return current;
    }

    private SourceAuthorization requireDraft(PortCallContext context, MemoryFactDraft draft) {
        requireDraftOwner(context, draft);
        requireType(draft);
        List<CommittedSessionEvent> sourceEvents = requireSourceEvents(context, draft);
        return authorizeSource(context, draft, sourceEvents);
    }

    private SourceAuthorization authorizeSource(PortCallContext context, MemoryFactDraft draft, List<CommittedSessionEvent> sourceEvents) {
        MemorySourcePolicy.Decision decision = Objects.requireNonNull(
                sources.authorize(new MemorySourcePolicy.Request(context, draft, sourceEvents)));
        return switch (decision) {
            case MemorySourcePolicy.Decision.Allowed allowed -> new SourceAuthorization(allowed.auditRef());
            case MemorySourcePolicy.Decision.Denied denied ->
                    throw new PolicyRejected(denied.reasonCode(), denied.auditRef());
        };
    }

    private void validateAppliedBefore(PortCallContext context, PreparedMutation prepared, MemoryMutationResult.Applied applied) {
        if (prepared.mutation() instanceof Mutation.Create) {
            if (applied.before().isPresent()) throw failureState("memory_create_before_mismatch");
            return;
        }
        MemoryFact before = applied.before().orElseThrow(() -> failureState("memory_before_missing"));
        MemoryFactRef expectedRef;
        long expectedRevision;
        if (prepared.mutation() instanceof Mutation.Supersede mutation) {
            expectedRef = mutation.current();
            expectedRevision = mutation.expectedRevision();
        } else if (prepared.mutation() instanceof Mutation.AcceptRevision mutation) {
            expectedRef = mutation.current();
            expectedRevision = mutation.expectedRevision();
        } else {
            Mutation.Transition mutation = (Mutation.Transition) prepared.mutation();
            expectedRef = mutation.fact();
            expectedRevision = mutation.expectedRevision();
        }
        if (!before.ref().equals(expectedRef) || before.revision() != expectedRevision
                || !before.owner().equals(prepared.before().orElseThrow().owner())
                || !applied.replayed() && !prepared.before().equals(applied.before())) {
            throw failureState("memory_before_mismatch");
        }
        requireFactOwner(context, before, false);
        requireType(before);
        requireSourceEvents(context, before, false);
        if (prepared.mutation() instanceof Mutation.Transition mutation) requireTransition(before.status(), mutation.target());
        if ((prepared.mutation() instanceof Mutation.Supersede || prepared.mutation() instanceof Mutation.AcceptRevision) && before.status() != FactStatus.ACTIVE) {
            throw failureState("memory_supersede_before_mismatch");
        }
    }

    private void requireApplied(PortCallContext context, PreparedMutation prepared, MemoryFact fact) {
        requireFactOwner(context, fact, false);
        requireType(fact);
        requireSourceEvents(context, fact, false);
        switch (prepared.mutation()) {
            case Mutation.Create create -> requireCreated(create.draft(), fact);
            case Mutation.Supersede supersede -> requireCreated(supersede.replacement(), fact);
            case Mutation.AcceptRevision accept -> {
                MemoryFact proposal = prepared.proposal().orElseThrow();
                if (!fact.ref().equals(accept.proposal()) || fact.revision() != Math.incrementExact(accept.expectedProposalRevision())
                        || fact.status() != FactStatus.ACTIVE || !sameImmutableFact(proposal, fact)
                        || !proposal.validTo().equals(fact.validTo()) || !proposal.expiresAt().equals(fact.expiresAt())
                        || fact.supersededBy().isPresent() || !fact.derivedFrom().equals(Optional.of(accept.current()))) {
                    throw failureState("memory_accept_revision_ack_mismatch");
                }
            }
            case Mutation.Transition transition -> {
                MemoryFact current = prepared.before().orElseThrow();
                if (!sameImmutableFact(current, fact)
                        || fact.revision() != Math.incrementExact(current.revision())
                        || fact.status() != transition.target()) {
                    throw failureState("memory_transition_ack_mismatch");
                }
            }
        }
    }

    /** USER_AGENT 同 subject+predicate 覆盖本次采用的 USER 偏好，不改持久事实。 */
    static MemoryQueryResult overlayAdoptedPreferences(MemoryQueryResult result) {
        List<MemoryFact> adopted = overlayUserAgentOverUser(result.candidates());
        if (adopted.equals(result.candidates())) {
            return result;
        }
        return new MemoryQueryResult(
                result.queryId(), adopted, result.resultRef(), result.evaluatedAt(),
                new MemoryRetrievalDiagnostics(result.diagnostics().mode(), result.diagnostics().degradationReason(),
                        result.diagnostics().visibleDocumentCount(), result.diagnostics().readyVectorCount(),
                        result.diagnostics().candidates(), adopted.stream().map(fact -> fact.ref().factId()).toList()));
    }

    static List<MemoryFact> overlayUserAgentOverUser(List<MemoryFact> facts) {
        HashSet<String> agentKeys = new HashSet<>();
        for (MemoryFact fact : facts) {
            if (fact.scope() == MemoryScope.USER_AGENT) {
                agentKeys.add(overlayKey(fact));
            }
        }
        if (agentKeys.isEmpty()) {
            return facts;
        }
        List<MemoryFact> selected = new ArrayList<>();
        for (MemoryFact fact : facts) {
            if (fact.scope() != MemoryScope.USER || !agentKeys.contains(overlayKey(fact))) {
                selected.add(fact);
            }
        }
        return List.copyOf(selected);
    }

    private static String overlayKey(MemoryFact fact) {
        return fact.subject() + "\u0000" + fact.predicate();
    }

    private static void requireCreated(MemoryFactDraft draft, MemoryFact fact) {
        if (!sameDraft(draft, fact)
                || fact.revision() != 1
                || fact.decayScore() != 0d
                || fact.supersededBy().isPresent()) {
            throw failureState("memory_created_fact_mismatch");
        }
    }

    private static boolean sameDraft(MemoryFactDraft draft, MemoryFact fact) {
        return draft.owner().equals(fact.owner())
                && draft.type().equals(fact.type())
                && draft.retentionClass().equals(fact.retentionClass())
                && draft.schemaVersion().equals(fact.schemaVersion())
                && draft.subject().equals(fact.subject())
                && draft.predicate().equals(fact.predicate())
                && draft.object().equals(fact.object())
                && draft.sourceEventIds().equals(fact.sourceEventIds())
                && draft.sourceSessionRef().equals(fact.sourceSessionRef())
                && draft.sourceExecutionRef().equals(fact.sourceExecutionRef())
                && draft.exportPath().equals(fact.exportPath())
                && draft.extractorVersion().equals(fact.extractorVersion())
                && draft.validFrom().equals(fact.validFrom())
                && draft.validTo().equals(fact.validTo())
                && draft.expiresAt().equals(fact.expiresAt())
                && Double.compare(draft.confidence(), fact.confidence()) == 0
                && Double.compare(draft.importance(), fact.importance()) == 0
                && draft.reviewRequired() == fact.reviewRequired()
                && draft.derivedFrom().equals(fact.derivedFrom())
                && draft.initialStatus() == fact.status();
    }

    /** Transition 只可改变治理字段；事实正文、身份、来源与创建身份必须保持。 */
    private static boolean sameImmutableFact(MemoryFact before, MemoryFact after) {
        return before.ref().equals(after.ref())
                && before.owner().equals(after.owner())
                && before.type().equals(after.type())
                && before.retentionClass().equals(after.retentionClass())
                && before.schemaVersion().equals(after.schemaVersion())
                && before.subject().equals(after.subject())
                && before.predicate().equals(after.predicate())
                && before.object().equals(after.object())
                && before.sourceEventIds().equals(after.sourceEventIds())
                && before.sourceSessionRef().equals(after.sourceSessionRef())
                && before.sourceExecutionRef().equals(after.sourceExecutionRef())
                && before.exportPath().equals(after.exportPath())
                && before.extractorVersion().equals(after.extractorVersion())
                && before.createdAt().equals(after.createdAt())
                && before.validFrom().equals(after.validFrom())
                && before.derivedFrom().equals(after.derivedFrom());
    }

    private static void requireTransition(FactStatus source, FactStatus target) {
        boolean allowed = switch (source) {
            case ACTIVE -> Set.of(FactStatus.DECAYED, FactStatus.PENDING_CONFIRM,
                    FactStatus.ARCHIVED, FactStatus.FORGOTTEN).contains(target);
            case DECAYED -> target == FactStatus.ACTIVE;
            case PENDING_CONFIRM -> target == FactStatus.ACTIVE || target == FactStatus.REJECTED
                    || target == FactStatus.FORGOTTEN;
            case FORGOTTEN -> target == FactStatus.ACTIVE || target == FactStatus.PENDING_CONFIRM;
            case REJECTED, ARCHIVED -> false;
        };
        if (!allowed) {
            throw failureState("memory_transition_not_allowed");
        }
    }

    private void requireQueryTypes(MemoryQuery query) {
        List<MemoryTypeRegistry.Registration> registrations = query.types().stream()
                .map(this::registration).toList();
        if (query.profile() == MemoryQueryProfile.RECENT_EPISODIC) {
            if (!query.scopes().equals(Set.of(MemoryScope.USER_AGENT))
                    || registrations.isEmpty()
                    || registrations.stream().anyMatch(registration -> !registration.recentEpisodic())) {
                throw failureState("memory_recent_episodic_contract_mismatch");
            }
        }
        if (!registrations.isEmpty()) {
            for (MemoryScope scope : query.scopes()) {
                if (registrations.stream().noneMatch(registration -> registration.allowedScopes().contains(scope))) {
                    throw failureState("memory_query_scope_not_registered");
                }
            }
            for (MemoryTypeRegistry.Registration registration : registrations) {
                if (registration.allowedScopes().stream().noneMatch(query.scopes()::contains)) {
                    throw failureState("memory_query_type_scope_mismatch");
                }
            }
        }
    }

    private void requireResult(
            PortCallContext context,
            MemoryQuery query,
            MemoryQueryResult result,
            boolean verifyExternalSources) {
        if (!result.queryId().equals(query.queryId())
                || result.candidates().size() > query.maxItems()
                || !result.evaluatedAt().isBefore(context.control().deadlineAt())) {
            throw failureState("memory_query_result_identity_mismatch");
        }
        requireDiagnostics(context, query, result.diagnostics());
        HashSet<MemoryFactRef> refs = new HashSet<>();
        for (MemoryFact fact : result.candidates()) {
            if (!refs.add(fact.ref())) {
                throw failureState("memory_query_duplicate_fact");
            }
            requireFactOwner(context, fact, true);
            if (!querySubjectMatches(context, query, fact)
                    || !query.scopes().contains(fact.scope())
                    || !query.types().isEmpty() && !query.types().contains(fact.type())
                    || fact.status() != FactStatus.ACTIVE
                    || fact.validFrom().isAfter(query.asOf())
                    || fact.validTo().filter(end -> !query.asOf().isBefore(end)).isPresent()
                    || fact.expiresAt().filter(end -> !query.asOf().isBefore(end)).isPresent()
                    || fact.createdAt().isAfter(result.evaluatedAt())
                    || fact.supersededBy().isPresent()) {
                throw failureState("memory_query_fact_rejected");
            }
            if (verifyExternalSources) {
                requireType(fact);
                requireSourceEvents(context, fact, true);
            }
        }
    }

    private static void requireDiagnostics(PortCallContext context, MemoryQuery query, MemoryRetrievalDiagnostics diagnostics) {
        if (diagnostics.candidates().size() > query.retrievalPolicy().candidateLimit()) throw failureState("memory_diagnostics_candidate_limit");
        for (var candidate : diagnostics.candidates()) {
            if (!candidate.fact().tenantId().equals(context.runIdentity().owner().sessionRef().tenantId())) throw failureState("memory_diagnostics_tenant_mismatch");
        }
        boolean vectorRequested = query.retrievalPolicy().embeddingModel().isPresent();
        if (diagnostics.mode() == MemoryRetrievalDiagnostics.Mode.HYBRID && !vectorRequested
                || diagnostics.degradationReason().isPresent() && (!vectorRequested
                    || query.retrievalPolicy().vectorFailurePolicy() != MemoryRetrievalPolicy.VectorFailurePolicy.LEXICAL_FALLBACK)) {
            throw failureState("memory_diagnostics_policy_mismatch");
        }
        if (vectorRequested && diagnostics.visibleDocumentCount() > 0 && diagnostics.mode() == MemoryRetrievalDiagnostics.Mode.LEXICAL
                && diagnostics.degradationReason().isEmpty()) throw failureState("memory_vector_failure_undeclared");
    }

    /** 只复验当前可用性，不重新搜索或把普通新 revision 替换进历史快照。 */
    private void requireCurrentMaterial(PortCallContext context, MemoryEventCodec.RetrievalSnapshot snapshot) {
        Failure stopped = liveFailure(context);
        if (stopped != null) throw failureState(stopped.reasonCode());
        Access decision = authorize(context, snapshot.query().owner(), MemoryAccessPolicy.Operation.QUERY,
                snapshot.query().scopes(), snapshot.query().types(), Optional.empty());
        if (decision.failure() != null) throw failureState(decision.failure().reasonCode());
        requireCurrentFacts(context, snapshot);
    }

    private void requireCurrentFacts(PortCallContext context, MemoryEventCodec.RetrievalSnapshot snapshot) {
        requireCurrentFacts(context, snapshot, memory::findCurrent);
    }

    /** 借用宿主派发事务的当前事实读取；只复核既有注入，不执行新的检索或外部调用。 */
    @FunctionalInterface
    public interface CurrentFactBatchReader {
        List<Optional<MemoryFact>> read(MemoryPort.CurrentFactReadBatch batch);
    }

    public boolean requireCurrentInjectedRetrievals(PortCallContext context, CurrentFactBatchReader reader) {
        Objects.requireNonNull(reader);
        var latest = new java.util.LinkedHashMap<String, RetrievalReceipt>();
        long cursor = 0;
        while (true) {
            var page = log.readAfter(new ReadSessionLog(context.runIdentity().owner(), cursor, RETRIEVAL_SCAN_PAGE_SIZE),
                    Set.of(MemoryEventCodec.RETRIEVAL_EVENT));
            if (page.isEmpty()) break;
            for (var event : page) {
                if (event.sessionSequence() <= cursor) throw failureState("memory_retrieval_scan_page_invalid");
                cursor = event.sessionSequence();
                if (!matchesTarget(target(context), event)) continue;
                var receipt = reconstructRetrieval(context, event);
                var prior = latest.put(receipt.query().queryId(), receipt);
                if (prior != null) requireRetrievalContinuation(prior.snapshot, receipt.snapshot);
                else if (receipt.phase() != Phase.CANDIDATE) throw failureState("memory_retrieval_chain_invalid");
            }
            if (page.size() < RETRIEVAL_SCAN_PAGE_SIZE) break;
        }
        boolean injected = false;
        for (var receipt : latest.values()) {
            if (receipt.phase() != Phase.INJECTED && receipt.phase() != Phase.REFERENCED) continue;
            injected = true;
            Failure stopped = liveFailure(context);
            if (stopped != null) throw failureState(stopped.reasonCode());
            var decision = authorize(context, receipt.query().owner(), MemoryAccessPolicy.Operation.QUERY,
                    receipt.query().scopes(), receipt.query().types(), Optional.empty());
            if (decision.failure() != null) throw failureState(decision.failure().reasonCode());
            requireCurrentFacts(context, receipt.snapshot, reader);
        }
        return injected;
    }

    private void requireCurrentFacts(PortCallContext context, MemoryEventCodec.RetrievalSnapshot snapshot,
            CurrentFactBatchReader reader) {
        List<String> selected = snapshot.phase() == Phase.CANDIDATE ? snapshot.trace().candidateIds() : snapshot.trace().selectedIds();
        List<MemoryFact> frozenFacts = snapshot.facts().stream().filter(fact -> selected.contains(fact.ref().factId())).toList();
        for (int offset = 0; offset < frozenFacts.size(); offset += CURRENT_FACT_BATCH_SIZE) {
            List<MemoryFact> batch = frozenFacts.subList(offset, Math.min(frozenFacts.size(), offset + CURRENT_FACT_BATCH_SIZE));
            List<Optional<MemoryFact>> found;
            try {
                found = Objects.requireNonNull(reader.read(new MemoryPort.CurrentFactReadBatch(batch.stream()
                        .map(fact -> new MemoryPort.CurrentFactRead(context, fact.ref())).toList())));
                if (found.size() != batch.size() || found.stream().anyMatch(Objects::isNull)) {
                    throw failureState("memory_current_governance_batch_mismatch");
                }
            } catch (RuntimeException failure) {
                throw failureState(reason(failure, "memory_current_governance_unavailable"));
            }
            Instant now = clock.instant();
            for (int index = 0; index < batch.size(); index++) {
                MemoryFact frozen = batch.get(index);
                MemoryFact current = found.get(index).orElseThrow(() -> failureState("memory_frozen_fact_unavailable"));
                requireFactOwner(context, current, true);
                if (!current.ref().equals(frozen.ref()) || !current.owner().equals(frozen.owner())
                        || !current.type().equals(frozen.type()) || !currentLineageUsable(context, frozen, current, reader) || current.validFrom().isAfter(now)
                        || current.status() == FactStatus.ACTIVE && current.validTo().filter(end -> !now.isBefore(end)).isPresent()
                        || current.status() == FactStatus.ACTIVE && current.expiresAt().filter(end -> !now.isBefore(end)).isPresent()
                        || frozen.validTo().filter(end -> !now.isBefore(end)).isPresent()
                        || frozen.expiresAt().filter(end -> !now.isBefore(end)).isPresent()) {
                    throw failureState("memory_frozen_fact_no_longer_usable");
                }
            }
            Failure stopped = liveFailure(context);
            if (stopped != null) throw failureState(stopped.reasonCode());
        }
    }

    /** 普通纠正保留在途原文；任何后继的遗忘或禁止使用仍阻止旧冻结材料。 */
    private boolean currentLineageUsable(PortCallContext context, MemoryFact frozen, MemoryFact current,
            CurrentFactBatchReader reader) {
        var seen = new HashSet<MemoryFactRef>();
        while (current.status() == FactStatus.ARCHIVED && current.supersededBy().isPresent()) {
            if (!seen.add(current.ref())) return false;
            var next = current.supersededBy().orElseThrow();
            var result = reader.read(new MemoryPort.CurrentFactReadBatch(List.of(new MemoryPort.CurrentFactRead(context, next))));
            if (result.size() != 1 || result.getFirst().isEmpty()) return false;
            current = result.getFirst().orElseThrow();
            requireFactOwner(context, current, true);
            if (!current.ref().equals(next) || !current.owner().equals(frozen.owner()) || !current.type().equals(frozen.type())) return false;
        }
        Instant now = clock.instant();
        return current.status() == FactStatus.ACTIVE && current.supersededBy().isEmpty()
                && !current.validFrom().isAfter(now)
                && current.validTo().filter(end -> !now.isBefore(end)).isEmpty()
                && current.expiresAt().filter(end -> !now.isBefore(end)).isEmpty();
    }

    private static void requireQuery(PortCallContext context, MemoryQuery query) {
        try { new MemoryQueryCall(context, query); }
        catch (IllegalArgumentException exception) { throw failureState("memory_query_owner_mismatch"); }
    }

    private static void requireFactOwner(PortCallContext context, MemoryFact fact, boolean retrieval) {
        if (!ownerMatches(context, fact.owner())) throw failureState("memory_fact_owner_mismatch");
        if (fact.owner() instanceof MemoryOwner.Session session
                && !session.sessionRef().equals(fact.sourceSessionRef())) {
            throw failureState("memory_session_fact_mismatch");
        }
    }

    private static boolean ownerMatches(PortCallContext context, MemoryOwner owner) {
        SessionOwner session = context.runIdentity().owner();
        if (!owner.tenantId().equals(session.sessionRef().tenantId())) return false;
        return switch (owner) {
            case MemoryOwner.Session value -> value.sessionRef().equals(session.sessionRef());
            case MemoryOwner.UserAgent value -> session.subject() instanceof SessionSubject.User user
                    && value.endUserId().equals(user.endUserId())
                    && value.agentId().equals(context.runIdentity().agentId());
            case MemoryOwner.User value -> session.subject() instanceof SessionSubject.User user
                    && value.endUserId().equals(user.endUserId());
            case MemoryOwner.Group value -> session.subject() instanceof SessionSubject.Group group
                    && value.groupBindingRef().equals(group.groupBindingRef());
            case MemoryOwner.GroupMember value -> session.subject() instanceof SessionSubject.Group group
                    && value.groupBindingRef().equals(group.groupBindingRef());
        };
    }

    private static void requireDraftOwner(PortCallContext context, MemoryFactDraft draft) {
        if (!ownerMatches(context, draft.owner())
                || !draft.sourceSessionRef().equals(context.runIdentity().owner().sessionRef())) {
            throw failureState("memory_draft_owner_mismatch");
        }
    }

    private static boolean querySubjectMatches(PortCallContext context, MemoryQuery query, MemoryFact fact) {
        if (!(fact.owner() instanceof MemoryOwner.GroupMember member)) return true;
        Optional<com.skillforge.runtime.api.EndUserId> subject = query.owner() instanceof MemoryOwner.GroupMember owner
                ? Optional.of(owner.subjectEndUserId()) : query.subjectEndUserId();
        if (subject.isEmpty() && context.runIdentity().requester() instanceof RunRequester.EndUser requester) {
            subject = Optional.of(requester.endUserId());
        }
        return subject.filter(member.subjectEndUserId()::equals).isPresent();
    }

    private void requireType(MemoryFactDraft draft) {
        MemoryTypeRegistry.Registration registration = registration(draft.type());
        requireRegistration(registration, draft.scope(), draft.retentionClass(), draft.schemaVersion(), draft.object());
        if (registration.forceReview()
                && (draft.initialStatus() != FactStatus.PENDING_CONFIRM || !draft.reviewRequired())) {
            throw failureState("memory_review_required");
        }
    }

    private void requireType(MemoryFact fact) {
        MemoryTypeRegistry.Registration registration = registration(fact.type());
        requireRegistration(registration, fact.scope(), fact.retentionClass(), fact.schemaVersion(), fact.object());
        if (registration.forceReview()
                && fact.status() == FactStatus.ACTIVE && !fact.reviewRequired()) {
            throw failureState("memory_review_fact_mismatch");
        }
    }

    private MemoryTypeRegistry.Registration registration(MemoryTypeRef type) {
        Optional<MemoryTypeRegistry.Registration> resolved = Objects.requireNonNull(types.find(type));
        MemoryTypeRegistry.Registration registration = resolved.orElseThrow(
                () -> failureState("memory_type_not_registered"));
        if (!registration.type().equals(type)) {
            throw failureState("memory_type_registry_mismatch");
        }
        return registration;
    }

    private static void requireRegistration(
            MemoryTypeRegistry.Registration registration,
            MemoryScope scope,
            com.skillforge.runtime.api.RetentionClassRef retentionClass,
            String schemaVersion,
            com.skillforge.runtime.api.TypedPayload payload) {
        MemoryTypeRegistry.Schema schema = registration.schema(schemaVersion).orElseThrow(
                () -> failureState("memory_schema_not_registered"));
        if (!registration.allowedScopes().contains(scope)
                || !registration.retentionClass().equals(retentionClass)
                || !schema.payloadSchemaId().equals(payload.schemaId())) {
            throw failureState("memory_type_contract_mismatch");
        }
        MemoryTypeRegistry.Validation validation = Objects.requireNonNull(
                registration.validator().validate(schemaVersion, payload));
        if (validation instanceof MemoryTypeRegistry.Validation.Rejected rejected) {
            throw failureState(rejected.reasonCode());
        }
        if (!(validation instanceof MemoryTypeRegistry.Validation.Allowed)) {
            throw failureState("memory_payload_validation_invalid");
        }
    }

    private List<CommittedSessionEvent> requireSourceEvents(PortCallContext context, MemoryFactDraft draft) {
        return requireSourceEvents(context, draft.owner(), draft.sourceSessionRef(), draft.sourceEventIds(),
                draft.sourceExecutionRef(), draft.exportPath(), false);
    }

    private List<CommittedSessionEvent> requireSourceEvents(
            PortCallContext context, MemoryFact fact, boolean retrieval) {
        if (retrieval && fact.owner() instanceof MemoryOwner.User) {
            if (fact.sourceEventIds().isEmpty()
                    || !fact.sourceSessionRef().tenantId().equals(context.runIdentity().owner().sessionRef().tenantId())) {
                throw failureState("memory_source_event_missing");
            }
            return List.of();
        }
        return requireSourceEvents(context, fact.owner(), fact.sourceSessionRef(), fact.sourceEventIds(),
                fact.sourceExecutionRef(), fact.exportPath(), !retrieval);
    }

    private List<CommittedSessionEvent> requireSourceEvents(
            PortCallContext context,
            MemoryOwner memoryOwner,
            com.skillforge.runtime.api.SessionRef sessionRef,
            List<EventId> eventIds,
            Optional<com.skillforge.runtime.api.ExecutionRef> executionRef,
            Optional<String> exportPath,
            boolean persistedFact) {
        if (exportPath.isPresent() && executionRef.isEmpty()) {
            throw failureState("memory_export_missing_execution");
        }
        SessionOwner current = context.runIdentity().owner();
        // 已授权持久USER事实的原来源可以属于另一Agent；新草稿仍只接受当前owner来源。
        boolean userSource = persistedFact && memoryOwner instanceof MemoryOwner.User
                && !sessionRef.equals(current.sessionRef());
        SessionOwner owner = userSource ? current : new SessionOwner(sessionRef, current.subject(), current.target());
        boolean personalAgentBoundary = memoryOwner instanceof MemoryOwner.UserAgent || memoryOwner instanceof MemoryOwner.User;
        List<CommittedSessionEvent> result = new ArrayList<>();
        for (EventId eventId : eventIds) {
            Optional<CommittedSessionEvent> found = Objects.requireNonNull(userSource
                    ? log.findUserSource(sessionRef, ((MemoryOwner.User) memoryOwner).endUserId(), eventId)
                    : log.findByEvent(owner, eventId));
            CommittedSessionEvent event = found.orElseThrow(() -> failureState("memory_source_event_missing"));
            boolean ownerMatches = userSource
                    ? event.owner().sessionRef().equals(sessionRef)
                            && event.owner().subject() instanceof SessionSubject.User user
                            && user.endUserId().equals(((MemoryOwner.User) memoryOwner).endUserId())
                    : event.owner().equals(owner);
            if (!ownerMatches || !event.eventId().equals(eventId)
                    || !userSource && personalAgentBoundary && !event.agentId().equals(context.runIdentity().agentId())) {
                throw failureState("memory_source_event_mismatch");
            }
            result.add(event);
        }
        if (executionRef.isPresent() && result.stream().noneMatch(event -> event.executionRef().equals(executionRef))) {
            throw failureState("memory_source_execution_mismatch");
        }
        return List.copyOf(result);
    }

    private Access authorize(
            PortCallContext context,
            MemoryOwner owner,
            MemoryAccessPolicy.Operation operation,
            Set<MemoryScope> scopes,
            Set<MemoryTypeRef> types,
            Optional<MemoryFactRef> factRef) {
        try {
            MemoryAccessPolicy.Decision decision = Objects.requireNonNull(
                    access.authorize(new MemoryAccessPolicy.Request(context, owner, operation, scopes, types, factRef)));
            return switch (decision) {
                case MemoryAccessPolicy.Decision.Allowed allowed -> new Access(allowed.auditRef(), null);
                case MemoryAccessPolicy.Decision.Denied denied ->
                        new Access(null, new Failure(denied.reasonCode(), Optional.of(denied.auditRef())));
            };
        } catch (RuntimeException exception) {
            return new Access(null, new Failure("memory_access_policy_failed", Optional.empty()));
        }
    }

    private PendingRetrieval pending(
            PortCallContext context,
            MemoryEventCodec.RetrievalSnapshot snapshot,
            Instant occurredAt,
            long afterSequence) {
        requireTrace(snapshot);
        SessionProjectionTarget target = target(context);
        var payload = MemoryEventCodec.retrievalPayload(snapshot);
        var eventId = MemoryEventCodec.retrievalEventId(context.runIdentity(), snapshot);
        var draft = new SessionEventDraft(
                eventId,
                MemoryEventCodec.RETRIEVAL_EVENT,
                MemoryEventCodec.EVENT_VERSION,
                MemoryEventCodec.eventFingerprint(target, eventId, MemoryEventCodec.RETRIEVAL_EVENT,
                        payload, context.runIdentity().trace(), occurredAt),
                context.runIdentity().trace(), payload, occurredAt);
        return new PendingRetrieval(
                this, context, snapshot, new AppendSessionEvent(target, draft), afterSequence);
    }

    private RetrievalResult appendRetrieval(PendingRetrieval pending) {
        AppendAttempt attempt = append(pending.append, pending.afterSequence);
        if (attempt.event() != null) {
            return new RetrievalResult.Recorded(new RetrievalReceipt(
                    this, pending.context, pending.snapshot, attempt.event()));
        }
        if (attempt.conflict()) {
            return new RetrievalResult.Stopped(attempt.failure());
        }
        return new RetrievalResult.Pending(pending, attempt.failure());
    }

    private MutationResult appendMutation(PendingMutation pending) {
        AppendAttempt attempt = append(pending.append, 0);
        if (attempt.event() != null) {
            return new MutationResult.Recorded(
                    pending.fact, attempt.event(), pending.memoryReplayed);
        }
        return new MutationResult.Pending(pending, attempt.failure());
    }

    private AppendAttempt append(AppendSessionEvent command, long afterSequence) {
        AppendResult result;
        try {
            result = Objects.requireNonNull(log.append(command));
        } catch (RuntimeException exception) {
            return AppendAttempt.failed("memory_session_log_outcome_unknown", Optional.empty(), false);
        }
        if (result instanceof AppendResult.Conflict conflict) {
            return AppendAttempt.failed(
                    "memory_session_event_conflict", Optional.of(conflict.conflict().auditRef()), true);
        }
        CommittedSessionEvent event = switch (result) {
            case AppendResult.Appended appended -> appended.event();
            case AppendResult.Replayed replayed -> replayed.event();
            case AppendResult.Conflict ignored -> throw new AssertionError();
        };
        if (!sameAppend(command, event) || event.sessionSequence() <= afterSequence) {
            return AppendAttempt.failed("memory_session_log_ack_mismatch", Optional.empty(), false);
        }
        return new AppendAttempt(event, null, false);
    }

    private static boolean sameAppend(AppendSessionEvent command, CommittedSessionEvent event) {
        SessionProjectionTarget target = command.target();
        SessionEventDraft draft = command.event();
        return event.owner().equals(target.owner())
                && event.agentId().equals(target.agentId())
                && event.inputId().equals(target.inputId())
                && event.runRef().equals(target.runRef())
                && event.executionRef().equals(target.executionRef())
                && event.actionRef().equals(target.actionRef())
                && event.delegationRef().equals(target.delegationRef())
                && event.eventId().equals(draft.eventId())
                && event.eventType().equals(draft.eventType())
                && event.eventVersion().equals(draft.eventVersion())
                && event.trace().equals(draft.trace())
                && event.payload().equals(draft.payload())
                && event.occurredAt().equals(draft.occurredAt());
    }

    private static void requireConflict(
            Mutation mutation,
            MemoryMutationResult.Conflict conflict,
            SessionOwner owner) {
        if (!conflict.fact().tenantId().equals(owner.sessionRef().tenantId())) {
            throw failureState("memory_conflict_tenant_mismatch");
        }
        if (mutation instanceof Mutation.AcceptRevision accept) {
            if (!conflict.fact().equals(accept.current()) && !conflict.fact().equals(accept.proposal())) throw failureState("memory_conflict_fact_mismatch");
            return;
        }
        mutation.factRef().ifPresent(expected -> {
            if (!expected.equals(conflict.fact())) {
                throw failureState("memory_conflict_fact_mismatch");
            }
        });
    }

    private static void requireAdvance(
            MemoryEventCodec.RetrievalSnapshot previous,
            Phase next,
            MemoryRetrievalTrace trace) {
        if (!trace.queryId().equals(previous.query().queryId())
                || !trace.candidateIds().equals(previous.trace().candidateIds())) {
            throw failureState("memory_retrieval_trace_identity_mismatch");
        }
        switch (next) {
            case CANDIDATE -> throw failureState("memory_retrieval_phase_invalid");
            case SELECTED -> {
                if (!trace.injectedIds().isEmpty() || !trace.referencedIds().isEmpty()) {
                    throw failureState("memory_selected_trace_invalid");
                }
            }
            case INJECTED -> {
                if (!trace.selectedIds().equals(previous.trace().selectedIds())
                        || !trace.referencedIds().isEmpty()) {
                    throw failureState("memory_injected_trace_invalid");
                }
            }
            case REFERENCED -> {
                if (!trace.selectedIds().equals(previous.trace().selectedIds())
                        || !trace.injectedIds().equals(previous.trace().injectedIds())) {
                    throw failureState("memory_referenced_trace_invalid");
                }
            }
        }
    }

    private static void requireRetrievalContinuation(
            MemoryEventCodec.RetrievalSnapshot previous,
            MemoryEventCodec.RetrievalSnapshot next) {
        if (next.phase().ordinal() != previous.phase().ordinal() + 1
                || !next.query().equals(previous.query())
                || !next.facts().equals(previous.facts())
                || !next.resultRef().equals(previous.resultRef())
                || !next.evaluatedAt().equals(previous.evaluatedAt())
                || !next.accessAuditRef().equals(previous.accessAuditRef())
                || !next.diagnostics().equals(previous.diagnostics())) {
            throw failureState("memory_retrieval_chain_invalid");
        }
        requireAdvance(previous, next.phase(), next.trace());
    }

    private static void requireTrace(MemoryEventCodec.RetrievalSnapshot snapshot) {
        List<String> candidateIds = snapshot.facts().stream().map(fact -> fact.ref().factId()).toList();
        MemoryRetrievalTrace trace = snapshot.trace();
        if (!trace.queryId().equals(snapshot.query().queryId())
                || !trace.candidateIds().equals(candidateIds)) {
            throw failureState("memory_retrieval_trace_mismatch");
        }
        switch (snapshot.phase()) {
            case CANDIDATE -> {
                if (!trace.selectedIds().isEmpty() || !trace.injectedIds().isEmpty()
                        || !trace.referencedIds().isEmpty()) {
                    throw failureState("memory_candidate_trace_invalid");
                }
            }
            case SELECTED -> {
                if (!trace.injectedIds().isEmpty() || !trace.referencedIds().isEmpty()) {
                    throw failureState("memory_selected_trace_invalid");
                }
            }
            case INJECTED -> {
                if (!trace.referencedIds().isEmpty()) {
                    throw failureState("memory_injected_trace_invalid");
                }
            }
            case REFERENCED -> { }
        }
    }

    private Failure liveFailure(PortCallContext context) {
        try {
            if (context.control().cancellationSignal().isCancellationRequested()) {
                return new Failure("memory_call_cancelled", Optional.empty());
            }
            if (!clock.instant().isBefore(context.control().deadlineAt())) {
                return new Failure("memory_call_deadline_reached", Optional.empty());
            }
            return null;
        } catch (RuntimeException exception) {
            return new Failure("memory_control_failed", Optional.empty());
        }
    }

    private static SessionProjectionTarget target(PortCallContext context) {
        return new SessionProjectionTarget(
                context.runIdentity().owner(),
                context.runIdentity().agentId(),
                context.runIdentity().causationInputId(),
                Optional.of(context.runIdentity().runRef()),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void requireEventTarget(
            SessionProjectionTarget expected,
            CommittedSessionEvent event) {
        if (!event.owner().equals(expected.owner())
                || !event.inputId().equals(expected.inputId())
                || !event.runRef().equals(expected.runRef())
                || !event.executionRef().equals(expected.executionRef())
                || !event.actionRef().equals(expected.actionRef())
                || !event.delegationRef().equals(expected.delegationRef())) {
            throw failureState("memory_replay_target_mismatch");
        }
    }

    private static boolean matchesTarget(
            SessionProjectionTarget expected,
            CommittedSessionEvent event) {
        return event.owner().equals(expected.owner())
                && event.inputId().equals(expected.inputId())
                && event.runRef().equals(expected.runRef())
                && event.executionRef().equals(expected.executionRef())
                && event.actionRef().equals(expected.actionRef())
                && event.delegationRef().equals(expected.delegationRef());
    }

    private void requirePending(PendingRetrieval pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.issuer != this) {
            throw failureState("memory_pending_issuer_mismatch");
        }
    }

    private void requirePending(PendingMutation pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.issuer != this) {
            throw failureState("memory_pending_issuer_mismatch");
        }
    }

    private void requirePending(PendingSubmission pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.issuer != this) {
            throw failureState("memory_pending_issuer_mismatch");
        }
    }

    private void requireReceiptOwned(RetrievalReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (receipt.issuer != this) {
            throw failureState("memory_receipt_issuer_mismatch");
        }
    }

    private static Failure failure(PortFailure value) {
        return new Failure(value.reasonCode(), Optional.of(value.resultRef()));
    }

    private static RetrievalResult.Stopped stopped(String reason) {
        return new RetrievalResult.Stopped(new Failure(reason, Optional.empty()));
    }

    private static String reason(RuntimeException exception, String fallback) {
        String value = exception.getMessage();
        return value != null && value.startsWith("memory_") ? value : fallback;
    }

    private static IllegalStateException failureState(String reason) {
        return new IllegalStateException(reason);
    }

    /** 当前事实读取必须来自宿主权威存储；实现不得返回缓存自证或内存 fallback。 */
    @FunctionalInterface
    public interface CurrentFactSource {
        Optional<MemoryFact> find(PortCallContext context, MemoryFactRef factRef);
    }

    /** 检索轨迹的四个单调阶段。 */
    public enum Phase {
        CANDIDATE,
        SELECTED,
        INJECTED,
        REFERENCED
    }

    /** 当前身份下的一次精确 Memory 查询。 */
    public record RetrievalCall(PortCallContext context, MemoryQuery query) {
        public RetrievalCall {
            context = Objects.requireNonNull(context, "context");
            query = Objects.requireNonNull(query, "query");
        }
    }

    /** 调用方必须稳定保存 occurredAt，以便不确定结果后重放同一写命令。 */
    public record MutationCall(PortCallContext context, Mutation mutation, Instant occurredAt) {
        public MutationCall {
            context = Objects.requireNonNull(context, "context");
            mutation = Objects.requireNonNull(mutation, "mutation");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /** 不复制 API 命令，只补齐当前事实查询所需的期望 scope/type。 */
    public sealed interface Mutation permits Mutation.Create, Mutation.Supersede, Mutation.Transition, Mutation.AcceptRevision {
        IdempotencyKey idempotencyKey();
        MemoryScope scope();
        MemoryTypeRef type();
        Optional<MemoryFactRef> factRef();
        MemoryAccessPolicy.Operation operation();

        record Create(IdempotencyKey idempotencyKey, MemoryFactDraft draft) implements Mutation {
            public Create {
                idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
                draft = Objects.requireNonNull(draft, "draft");
            }
            @Override public MemoryScope scope() { return draft.scope(); }
            @Override public MemoryTypeRef type() { return draft.type(); }
            @Override public Optional<MemoryFactRef> factRef() { return Optional.empty(); }
            @Override public MemoryAccessPolicy.Operation operation() { return MemoryAccessPolicy.Operation.CREATE; }
        }

        record Supersede(
                IdempotencyKey idempotencyKey,
                MemoryFactRef current,
                long expectedRevision,
                MemoryFactDraft replacement) implements Mutation {
            public Supersede {
                idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
                current = Objects.requireNonNull(current, "current");
                replacement = Objects.requireNonNull(replacement, "replacement");
                if (expectedRevision <= 0) {
                    throw new IllegalArgumentException("expectedRevision must be positive");
                }
            }
            @Override public MemoryScope scope() { return replacement.scope(); }
            @Override public MemoryTypeRef type() { return replacement.type(); }
            @Override public Optional<MemoryFactRef> factRef() { return Optional.of(current); }
            @Override public MemoryAccessPolicy.Operation operation() { return MemoryAccessPolicy.Operation.SUPERSEDE; }
        }

        /** 接受已持久化提案，两个版本一起 CAS；授权仍使用 SUPERSEDE。 */
        record AcceptRevision(IdempotencyKey idempotencyKey, MemoryFactRef current, long expectedRevision,
                MemoryFactRef proposal, long expectedProposalRevision, MemoryScope scope, MemoryTypeRef type) implements Mutation {
            public AcceptRevision {
                idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
                current = Objects.requireNonNull(current, "current");
                proposal = Objects.requireNonNull(proposal, "proposal");
                scope = Objects.requireNonNull(scope, "scope");
                type = Objects.requireNonNull(type, "type");
                if (!current.tenantId().equals(proposal.tenantId()) || current.equals(proposal)
                        || expectedRevision <= 0 || expectedProposalRevision <= 0) {
                    throw new IllegalArgumentException("revision acceptance requires distinct same-tenant facts and positive versions");
                }
            }
            @Override public Optional<MemoryFactRef> factRef() { return Optional.of(current); }
            @Override public MemoryAccessPolicy.Operation operation() { return MemoryAccessPolicy.Operation.SUPERSEDE; }
        }

        record Transition(
                IdempotencyKey idempotencyKey,
                MemoryFactRef fact,
                long expectedRevision,
                MemoryScope scope,
                MemoryTypeRef type,
                FactStatus target,
                String reasonCode) implements Mutation {
            public Transition {
                idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
                fact = Objects.requireNonNull(fact, "fact");
                scope = Objects.requireNonNull(scope, "scope");
                type = Objects.requireNonNull(type, "type");
                target = Objects.requireNonNull(target, "target");
                reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
                if (expectedRevision <= 0 || reasonCode.isBlank()) {
                    throw new IllegalArgumentException("transition revision/reason is invalid");
                }
            }
            @Override public Optional<MemoryFactRef> factRef() { return Optional.of(fact); }
            @Override public MemoryAccessPolicy.Operation operation() { return MemoryAccessPolicy.Operation.TRANSITION; }
        }
    }

    /** 安全错误只携带稳定 reason 与可选审计引用。 */
    public record Failure(String reasonCode, Optional<ResultRef> auditRef) {
        public Failure {
            reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
            auditRef = Objects.requireNonNull(auditRef, "auditRef");
            if (reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must not be blank");
            }
        }
    }

    /** 检索可能已记录、尚待重放记录，或在返回候选前停止。 */
    public sealed interface RetrievalResult
            permits RetrievalResult.Recorded, RetrievalResult.Pending, RetrievalResult.Stopped {
        record Recorded(RetrievalReceipt receipt) implements RetrievalResult {
            public Recorded { receipt = Objects.requireNonNull(receipt, "receipt"); }
        }
        record Pending(PendingRetrieval pending, Failure failure) implements RetrievalResult {
            public Pending {
                pending = Objects.requireNonNull(pending, "pending");
                failure = Objects.requireNonNull(failure, "failure");
            }
        }
        record Stopped(Failure failure) implements RetrievalResult {
            public Stopped { failure = Objects.requireNonNull(failure, "failure"); }
        }
    }

    /** 只在已提交候选事件后创建；INJECTED 前不提供模型可见事实。 */
    public static final class RetrievalReceipt {
        private final MemoryRuntime issuer;
        private final PortCallContext context;
        private final MemoryEventCodec.RetrievalSnapshot snapshot;
        private final CommittedSessionEvent event;

        private RetrievalReceipt(
                MemoryRuntime issuer,
                PortCallContext context,
                MemoryEventCodec.RetrievalSnapshot snapshot,
                CommittedSessionEvent event) {
            this.issuer = issuer;
            this.context = context;
            this.snapshot = snapshot;
            this.event = event;
        }

        public MemoryQuery query() { return snapshot.query(); }
        public Phase phase() { return snapshot.phase(); }
        public MemoryRetrievalTrace trace() { return snapshot.trace(); }
        public MemoryRetrievalDiagnostics diagnostics() { return snapshot.diagnostics(); }
        public List<MemoryFact> candidateFacts() { issuer.requireCurrentMaterial(context, snapshot); return snapshot.facts(); }
        public List<MemoryFact> selectedFacts() {
            if (snapshot.trace().selectedIds().isEmpty()) return List.of();
            issuer.requireCurrentMaterial(context, snapshot);
            return facts(snapshot.facts(), snapshot.trace().selectedIds());
        }
        public List<MemoryFact> injectedFacts() {
            if (phase().ordinal() < Phase.INJECTED.ordinal()) return List.of();
            issuer.requireCurrentMaterial(context, snapshot);
            return facts(snapshot.facts(), snapshot.trace().injectedIds());
        }
        public List<MemoryFact> referencedFacts() {
            if (phase() != Phase.REFERENCED) return List.of();
            issuer.requireCurrentMaterial(context, snapshot);
            return facts(snapshot.facts(), snapshot.trace().referencedIds());
        }
        public CommittedSessionEvent committedEvent() { return event; }

        private static List<MemoryFact> facts(List<MemoryFact> candidates, List<String> ids) {
            List<MemoryFact> result = new ArrayList<>();
            for (String id : ids) {
                result.add(candidates.stream().filter(fact -> fact.ref().factId().equals(id))
                        .findFirst().orElseThrow(() -> failureState("memory_trace_fact_missing")));
            }
            return List.copyOf(result);
        }

        @Override public String toString() { return "MemoryRetrievalReceipt[REDACTED]"; }
    }

    /** 未获 ACK 的冻结检索事件；不暴露候选内容，只允许交回原 Runtime 重放。 */
    public static final class PendingRetrieval {
        private final MemoryRuntime issuer;
        private final PortCallContext context;
        private final MemoryEventCodec.RetrievalSnapshot snapshot;
        private final AppendSessionEvent append;
        private final long afterSequence;

        private PendingRetrieval(
                MemoryRuntime issuer,
                PortCallContext context,
                MemoryEventCodec.RetrievalSnapshot snapshot,
                AppendSessionEvent append,
                long afterSequence) {
            this.issuer = issuer;
            this.context = context;
            this.snapshot = snapshot;
            this.append = append;
            this.afterSequence = afterSequence;
        }

        public Phase phase() { return snapshot.phase(); }
        public EventId eventId() { return append.event().eventId(); }
        @Override public String toString() { return "PendingMemoryRetrieval[REDACTED]"; }
    }

    /** 写入的最终结果区分 Port 拒绝/CAS 冲突、日志 pending 与完整记录。 */
    public sealed interface MutationResult
            permits MutationResult.Recorded, MutationResult.Rejected, MutationResult.Conflict,
                    MutationResult.Pending, MutationResult.Uncertain, MutationResult.Stopped {
        record Recorded(MemoryFact fact, CommittedSessionEvent event, boolean memoryReplayed)
                implements MutationResult {
            public Recorded {
                fact = Objects.requireNonNull(fact, "fact");
                event = Objects.requireNonNull(event, "event");
            }
        }
        record Rejected(PortFailure failure) implements MutationResult {
            public Rejected { failure = Objects.requireNonNull(failure, "failure"); }
        }
        record Conflict(MemoryFactRef fact, long currentRevision, ResultRef auditRef)
                implements MutationResult {
            public Conflict {
                fact = Objects.requireNonNull(fact, "fact");
                auditRef = Objects.requireNonNull(auditRef, "auditRef");
                if (currentRevision <= 0) {
                    throw new IllegalArgumentException("currentRevision must be positive");
                }
            }
        }
        record Pending(PendingMutation pending, Failure failure) implements MutationResult {
            public Pending {
                pending = Objects.requireNonNull(pending, "pending");
                failure = Objects.requireNonNull(failure, "failure");
            }
        }
        record Uncertain(PendingSubmission pending, Failure failure) implements MutationResult {
            public Uncertain {
                pending = Objects.requireNonNull(pending, "pending");
                failure = Objects.requireNonNull(failure, "failure");
            }
        }
        record Stopped(Failure failure) implements MutationResult {
            public Stopped { failure = Objects.requireNonNull(failure, "failure"); }
        }
    }

    /** MemoryPort 调用结果未知时冻结原命令，避免重试时更换授权、来源或时间事实。 */
    public static final class PendingSubmission {
        private final MemoryRuntime issuer;
        private final PortCallContext context;
        private final Mutation mutation;
        private final Instant occurredAt;
        private final PreparedMutation prepared;

        private PendingSubmission(
                MemoryRuntime issuer,
                PortCallContext context,
                Mutation mutation,
                Instant occurredAt,
                PreparedMutation prepared) {
            this.issuer = issuer;
            this.context = context;
            this.mutation = mutation;
            this.occurredAt = occurredAt;
            this.prepared = prepared;
        }

        public IdempotencyKey idempotencyKey() { return mutation.idempotencyKey(); }
        @Override public String toString() { return "PendingMemorySubmission[REDACTED]"; }
    }

    /** Memory 已返回 Applied 但 SessionLog 尚未确认；事实只作“可能已写”提示。 */
    public static final class PendingMutation {
        private final MemoryRuntime issuer;
        private final PortCallContext context;
        private final Mutation mutation;
        private final MemoryFact fact;
        private final boolean memoryReplayed;
        private final AppendSessionEvent append;

        private PendingMutation(
                MemoryRuntime issuer,
                PortCallContext context,
                Mutation mutation,
                MemoryFact fact,
                boolean memoryReplayed,
                AppendSessionEvent append) {
            this.issuer = issuer;
            this.context = context;
            this.mutation = mutation;
            this.fact = fact;
            this.memoryReplayed = memoryReplayed;
            this.append = append;
        }

        public MemoryFact possiblyAppliedFact() { return fact; }
        public EventId eventId() { return append.event().eventId(); }
        @Override public String toString() { return "PendingMemoryMutation[REDACTED]"; }
    }

    private record Access(ResultRef auditRef, Failure failure) { }
    private record SourceAuthorization(ResultRef auditRef) { }
    private record PreparedMutation(
            Mutation mutation,
            MemoryCommand command,
            Optional<MemoryFact> before,
            Optional<MemoryFact> proposal,
            ResultRef accessAuditRef,
            Optional<ResultRef> sourceAuditRef) { }
    private record AppendAttempt(CommittedSessionEvent event, Failure failure, boolean conflict) {
        private static AppendAttempt failed(String reason, Optional<ResultRef> audit, boolean conflict) {
            return new AppendAttempt(null, new Failure(reason, audit), conflict);
        }
    }

    private static final class PolicyRejected extends IllegalStateException {
        private final ResultRef auditRef;
        private PolicyRejected(String reason, ResultRef auditRef) {
            super(reason);
            this.auditRef = Objects.requireNonNull(auditRef, "auditRef");
        }
    }
}
