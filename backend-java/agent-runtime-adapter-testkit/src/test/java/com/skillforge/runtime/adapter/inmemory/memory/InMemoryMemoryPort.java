package com.skillforge.runtime.adapter.inmemory.memory;

import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.adapter.testkit.MemoryRetrievalFixtures;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.api.port.MemoryPort.MemoryCommand;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.MemoryPort.MemoryMutationResult;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryCall;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryOutcome;
import com.skillforge.runtime.api.port.MemoryPort.MemoryQueryResult;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.RetryMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 仅供契约测试使用的 MemoryPort；语义与生产 Store 共用一致性套件。 */
public final class InMemoryMemoryPort implements MemoryPort {

    private static final Duration RECENT_WINDOW = Duration.ofDays(30);
    private final State state;
    private final Clock clock;

    public InMemoryMemoryPort() {
        this(new State(), Clock.systemUTC());
    }

    public InMemoryMemoryPort(State state, Clock clock) {
        this.state = Objects.requireNonNull(state, "state");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MemoryQueryOutcome query(MemoryQueryCall call) {
        Objects.requireNonNull(call, "call");
        Instant evaluatedAt = clock.instant();
        if (!callAllowed(call.context(), evaluatedAt)) {
            return failed("memory_query_cancelled", call.query().queryId());
        }
        try {
            MemoryQuery query = call.query();
            if (query.profile() == MemoryQueryProfile.RECENT_EPISODIC
                    && (!query.scopes().equals(Set.of(MemoryScope.USER_AGENT))
                    || query.types().isEmpty())) {
                return failed("memory_recent_episodic_contract_mismatch", query.queryId());
            }
            // 此夹具只枚举受控可见事实，真实词法/向量检索由 JDBC 的组件与集成测试验证。
            List<MemoryFact> candidates = new ArrayList<>();
            synchronized (state.monitor) {
                for (List<MemoryFact> history : state.histories.values()) {
                    MemoryFact fact = history.getLast();
                    if (visible(call.context(), query, fact, evaluatedAt)) {
                        candidates.add(fact);
                    }
                }
            }
            List<MemoryFact> selected = budget(query, candidates);
            if (!callAllowed(call.context(), clock.instant())) {
                return failed("memory_query_cancelled", query.queryId());
            }
            return new MemoryQueryOutcome.Retrieved(new MemoryQueryResult(
                    query.queryId(), selected,
                    new ResultRef("memory-query:" + digest(query.queryId() + "\u0000"
                            + evaluatedAt + "\u0000" + selected.stream()
                            .map(value -> value.ref().factId() + ":" + value.revision())
                            .reduce("", (left, right) -> left + "\u0000" + right))),
                    evaluatedAt, MemoryRetrievalFixtures.controlledDiagnostics(candidates, selected)));
        } catch (RuntimeException failure) {
            return failed("memory_store_read_failure", call.query().queryId());
        }
    }

    @Override
    public MemoryMutationResult submit(MemoryCommand command) {
        Objects.requireNonNull(command, "command");
        PortCallContext context = context(command);
        Instant now = clock.instant();
        if (!callAllowed(context, now)) {
            return rejected("memory_mutation_cancelled", key(command));
        }
        synchronized (state.monitor) {
            OperationKey operationKey = new OperationKey(
                    context.runIdentity().owner().sessionRef().tenantId().value(), key(command));
            Operation existing = state.operations.get(operationKey);
            if (existing != null) {
                if (sameCommand(existing.command, command)) {
                    return new MemoryMutationResult.Applied(existing.output, existing.before, true);
                }
                return conflict(existing.output.ref(), currentRevision(existing.output.ref()),
                        operationKey, "memory_idempotency_conflict");
            }
            try {
                Optional<MemoryFact> before = switch (command) {
                    case MemoryCommand.Create ignored -> Optional.empty();
                    case MemoryCommand.Supersede supersede -> findCurrent(context, supersede.current());
                    case MemoryCommand.AcceptRevision accept -> findCurrent(context, accept.current());
                    case MemoryCommand.Transition transition -> findCurrent(context, transition.fact());
                };
                MemoryFact applied = switch (command) {
                    case MemoryCommand.Create create -> create(create, now);
                    case MemoryCommand.Supersede supersede -> supersede(supersede, now);
                    case MemoryCommand.AcceptRevision accept -> acceptRevision(accept, now);
                    case MemoryCommand.Transition transition -> transition(transition);
                };
                state.operations.put(operationKey, new Operation(command, applied, before));
                return new MemoryMutationResult.Applied(applied, before, false);
            } catch (StaleFact stale) {
                return conflict(stale.ref, stale.revision, operationKey, stale.reason);
            } catch (RejectedFact rejected) {
                return rejected(rejected.reason, key(command));
            } catch (RuntimeException failure) {
                return rejected("memory_store_write_failure", key(command));
            }
        }
    }

    /** 批次在一次存储锁内观察所有当前 revision，并保留每项独立 owner。 */
    @Override
    public List<Optional<MemoryFact>> findCurrent(CurrentFactReadBatch batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.reads().isEmpty()) return List.of();
        synchronized (state.monitor) {
            List<Optional<MemoryFact>> result = new ArrayList<>(batch.reads().size());
            for (CurrentFactRead read : batch.reads()) {
                List<MemoryFact> history = state.histories.get(read.fact());
                MemoryFact fact = history == null ? null : history.getLast();
                result.add(fact != null && owns(read.context(), fact)
                        ? Optional.of(fact) : Optional.empty());
            }
            return List.copyOf(result);
        }
    }

    /** 为 MemoryRuntime.CurrentFactSource 提供 scope-aware 当前事实读取。 */
    public Optional<MemoryFact> findCurrent(PortCallContext context, MemoryFactRef ref) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(ref, "ref");
        synchronized (state.monitor) {
            List<MemoryFact> history = state.histories.get(ref);
            if (history == null) {
                return Optional.empty();
            }
            MemoryFact fact = history.getLast();
            return owns(context, fact) ? Optional.of(fact) : Optional.empty();
        }
    }

    private MemoryFact create(MemoryCommand.Create command, Instant now) {
        MemoryFactDraft draft = command.fact();
        String id = "memory-fact:" + digest(draft.sourceSessionRef().tenantId().value()
                + "\u0000" + command.idempotencyKey().value());
        MemoryFactRef ref = new MemoryFactRef(draft.sourceSessionRef().tenantId(), id);
        if (state.histories.containsKey(ref)) {
            throw new RejectedFact("memory_fact_identity_conflict");
        }
        MemoryFact fact = fromDraft(ref, draft, now);
        state.histories.put(ref, new ArrayList<>(List.of(fact)));
        return fact;
    }

    private MemoryFact supersede(MemoryCommand.Supersede command, Instant now) {
        MemoryFact current = current(command.context(), command.current(), command.expectedRevision());
        MemoryFactDraft replacement = command.replacement();
        if (current.status() != FactStatus.ACTIVE || current.supersededBy().isPresent()
                || !replacement.owner().equals(current.owner())
                || !replacement.type().equals(current.type())
                || !replacement.derivedFrom().equals(Optional.of(current.ref()))) {
            throw new RejectedFact("memory_supersede_source_mismatch");
        }
        String id = "memory-fact:" + digest(current.ref().tenantId().value()
                + "\u0000" + command.idempotencyKey().value());
        MemoryFactRef replacementRef = new MemoryFactRef(current.ref().tenantId(), id);
        if (state.histories.containsKey(replacementRef)) {
            throw new RejectedFact("memory_fact_identity_conflict");
        }
        MemoryFact next = fromDraft(replacementRef, replacement, now);
        MemoryFact archived = copy(current, current.revision() + 1, FactStatus.ARCHIVED,
                closeAt(current, now), Optional.of(replacementRef));
        state.histories.get(current.ref()).add(archived);
        state.histories.put(replacementRef, new ArrayList<>(List.of(next)));
        return next;
    }

    private MemoryFact acceptRevision(MemoryCommand.AcceptRevision command, Instant now) {
        // 双版本全部核实成功后才推进任一事实；失败不留下单边 ACTIVE/ARCHIVED。
        MemoryFact current = current(command.context(), command.current(), command.expectedRevision());
        MemoryFact proposal = current(command.context(), command.proposal(), command.expectedProposalRevision());
        if (current.status() != FactStatus.ACTIVE || current.supersededBy().isPresent()
                || proposal.status() != FactStatus.PENDING_CONFIRM || proposal.supersededBy().isPresent()
                || !current.owner().equals(proposal.owner()) || !current.type().equals(proposal.type())
                || !proposal.derivedFrom().equals(Optional.of(current.ref()))) {
            throw new RejectedFact("memory_accept_revision_source_mismatch");
        }
        if (proposal.validFrom().isAfter(now)
                || proposal.validTo().filter(end -> !now.isBefore(end)).isPresent()
                || proposal.expiresAt().filter(end -> !now.isBefore(end)).isPresent()) {
            throw new RejectedFact("memory_revision_not_effective");
        }
        MemoryFact archived = copy(current, Math.incrementExact(current.revision()), FactStatus.ARCHIVED,
                closeAt(current, now), Optional.of(proposal.ref()));
        MemoryFact activated = copy(proposal, Math.incrementExact(proposal.revision()), FactStatus.ACTIVE,
                proposal.validTo(), proposal.supersededBy());
        state.histories.get(current.ref()).add(archived);
        state.histories.get(proposal.ref()).add(activated);
        return activated;
    }

    private MemoryFact transition(MemoryCommand.Transition command) {
        MemoryFact current = current(command.context(), command.fact(), command.expectedRevision());
        if (current.status() == FactStatus.PENDING_CONFIRM && current.derivedFrom().isPresent()
                && command.target() == FactStatus.ACTIVE) throw new RejectedFact("memory_revision_acceptance_required");
        if (current.supersededBy().isPresent() || !legal(current.status(), command.target())) {
            throw new RejectedFact("memory_transition_not_allowed");
        }
        if (current.status() == FactStatus.FORGOTTEN) {
            var history = state.histories.get(current.ref());
            if (command.target() != history.get(history.size() - 2).status()) {
                throw new RejectedFact("memory_restore_status_mismatch");
            }
        }
        MemoryFact next = copy(current, current.revision() + 1, command.target(),
                current.validTo(), current.supersededBy());
        state.histories.get(current.ref()).add(next);
        return next;
    }

    private MemoryFact current(PortCallContext context, MemoryFactRef ref, long revision) {
        Optional<MemoryFact> loaded = findCurrent(context, ref);
        if (loaded.isEmpty()) {
            throw new RejectedFact("memory_current_fact_missing");
        }
        MemoryFact current = loaded.orElseThrow();
        if (current.revision() != revision) {
            throw new StaleFact(ref, current.revision(), "memory_revision_conflict");
        }
        if (revision == Long.MAX_VALUE) {
            throw new RejectedFact("memory_revision_exhausted");
        }
        return current;
    }

    private static MemoryFact fromDraft(MemoryFactRef ref, MemoryFactDraft draft, Instant createdAt) {
        return new MemoryFact(ref, draft.owner(),
                draft.type(), draft.retentionClass(), draft.schemaVersion(), draft.subject(),
                draft.predicate(), draft.object(), draft.sourceEventIds(), draft.sourceSessionRef(),
                draft.sourceExecutionRef(), draft.exportPath(), draft.extractorVersion(), 1,
                createdAt, draft.validFrom(), draft.validTo(), draft.expiresAt(), draft.confidence(),
                draft.importance(), draft.reviewRequired(), draft.derivedFrom(), Optional.empty(),
                0d, draft.initialStatus());
    }

    private static MemoryFact copy(MemoryFact fact, long revision, FactStatus status,
            Optional<Instant> validTo, Optional<MemoryFactRef> supersededBy) {
        return new MemoryFact(fact.ref(), fact.owner(), fact.type(), fact.retentionClass(),
                fact.schemaVersion(), fact.subject(),
                fact.predicate(), fact.object(), fact.sourceEventIds(), fact.sourceSessionRef(),
                fact.sourceExecutionRef(), fact.exportPath(), fact.extractorVersion(), revision,
                fact.createdAt(), fact.validFrom(), validTo, fact.expiresAt(), fact.confidence(),
                fact.importance(), fact.reviewRequired(), fact.derivedFrom(), supersededBy,
                fact.decayScore(), status);
    }

    private static Optional<Instant> closeAt(MemoryFact current, Instant now) {
        Instant boundary = now.isBefore(current.validFrom()) ? current.validFrom() : now;
        return current.validTo().filter(value -> value.isBefore(boundary)).or(() -> Optional.of(boundary));
    }

    private static boolean visible(PortCallContext context, MemoryQuery query,
            MemoryFact fact, Instant evaluatedAt) {
        if (!owns(context, fact) || !matchesQueryOwner(context, query, fact.owner())
                || !query.scopes().contains(fact.scope())
                || !query.types().isEmpty() && !query.types().contains(fact.type())
                || fact.status() != FactStatus.ACTIVE || fact.supersededBy().isPresent()
                || fact.createdAt().isAfter(evaluatedAt) || fact.validFrom().isAfter(query.asOf())
                || fact.validTo().filter(end -> !query.asOf().isBefore(end)).isPresent()
                || fact.expiresAt().filter(end -> !query.asOf().isBefore(end)).isPresent()) {
            return false;
        }
        return query.profile() != MemoryQueryProfile.RECENT_EPISODIC
                || !fact.validFrom().isBefore(query.asOf().minus(RECENT_WINDOW));
    }

    private static boolean owns(PortCallContext context, MemoryFact fact) {
        var sessionOwner = context.runIdentity().owner();
        if (!fact.owner().tenantId().equals(sessionOwner.sessionRef().tenantId())) return false;
        return switch (fact.owner()) {
            case MemoryOwner.Session value -> value.sessionRef().equals(sessionOwner.sessionRef());
            case MemoryOwner.UserAgent value -> sessionOwner.subject() instanceof SessionSubject.User user
                    && value.endUserId().equals(user.endUserId())
                    && value.agentId().equals(context.runIdentity().agentId());
            case MemoryOwner.User value -> sessionOwner.subject() instanceof SessionSubject.User user
                    && value.endUserId().equals(user.endUserId());
            case MemoryOwner.Group value -> sessionOwner.subject() instanceof SessionSubject.Group group
                    && value.groupBindingRef().equals(group.groupBindingRef());
            case MemoryOwner.GroupMember value -> sessionOwner.subject() instanceof SessionSubject.Group group
                    && value.groupBindingRef().equals(group.groupBindingRef());
        };
    }

    private static boolean matchesQueryOwner(
            PortCallContext context, MemoryQuery query, MemoryOwner factOwner) {
        return switch (query.owner()) {
            case MemoryOwner.Session value -> factOwner instanceof MemoryOwner.Session session
                    ? value.equals(session)
                    : factOwner instanceof MemoryOwner.UserAgent || factOwner instanceof MemoryOwner.User;
            case MemoryOwner.UserAgent value -> value.equals(factOwner)
                    || factOwner instanceof MemoryOwner.User user
                    && value.tenantId().equals(user.tenantId())
                    && value.endUserId().equals(user.endUserId());
            case MemoryOwner.User value -> value.equals(factOwner);
            case MemoryOwner.Group value -> value.equals(factOwner)
                    || factOwner instanceof MemoryOwner.GroupMember member
                    && value.groupBindingRef().equals(member.groupBindingRef())
                    && query.subjectEndUserId()
                            .or(() -> context.runIdentity().requester() instanceof RunRequester.EndUser user
                                    ? Optional.of(user.endUserId()) : Optional.empty())
                            .filter(member.subjectEndUserId()::equals).isPresent();
            case MemoryOwner.GroupMember value -> value.equals(factOwner);
        };
    }

    private static List<MemoryFact> budget(MemoryQuery query, List<MemoryFact> candidates) {
        List<MemoryFact> selected = new ArrayList<>();
        long characters = 0;
        long tokens = 0;
        for (MemoryFact candidate : candidates) {
            if (selected.size() == query.maxItems()) {
                break;
            }
            String document = document(candidate);
            long nextCharacters = document.codePointCount(0, document.length());
            long nextTokens = document.getBytes(StandardCharsets.UTF_8).length;
            if (characters + nextCharacters > query.characterBudget()
                    || tokens + nextTokens > query.tokenBudget()) {
                break;
            }
            selected.add(candidate);
            characters += nextCharacters;
            tokens += nextTokens;
        }
        return List.copyOf(selected);
    }

    private static String document(MemoryFact fact) {
        return fact.subject() + "\n" + fact.predicate() + "\n" + fact.object().canonicalJson();
    }

    private static boolean legal(FactStatus source, FactStatus target) {
        return switch (source) {
            case ACTIVE -> Set.of(FactStatus.DECAYED, FactStatus.PENDING_CONFIRM,
                    FactStatus.ARCHIVED, FactStatus.FORGOTTEN).contains(target);
            case DECAYED -> target == FactStatus.ACTIVE;
            case PENDING_CONFIRM -> target == FactStatus.ACTIVE || target == FactStatus.REJECTED
                    || target == FactStatus.FORGOTTEN;
            case FORGOTTEN -> target == FactStatus.ACTIVE || target == FactStatus.PENDING_CONFIRM;
            case REJECTED, ARCHIVED -> false;
        };
    }

    private static boolean callAllowed(PortCallContext context, Instant now) {
        try {
            return now.isBefore(context.control().deadlineAt())
                    && !context.control().cancellationSignal().isCancellationRequested();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private long currentRevision(MemoryFactRef ref) {
        List<MemoryFact> history = state.histories.get(ref);
        return history == null ? 1 : history.getLast().revision();
    }

    private static MemoryMutationResult conflict(MemoryFactRef ref, long revision,
            OperationKey key, String reason) {
        return new MemoryMutationResult.Conflict(ref, revision,
                new ResultRef("memory-conflict:" + digest(key.tenant + "\u0000" + key.id + "\u0000"
                        + reason + "\u0000" + ref.factId() + "\u0000" + revision)));
    }

    private static MemoryQueryOutcome failed(String reason, String identity) {
        return new MemoryQueryOutcome.Failed(new PortFailure(reason,
                RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new ResultRef("memory-failure:" + digest(reason + "\u0000" + identity))));
    }

    private static MemoryMutationResult rejected(String reason, String identity) {
        return new MemoryMutationResult.Rejected(new PortFailure(reason,
                RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new ResultRef("memory-failure:" + digest(reason + "\u0000" + identity))));
    }

    private static PortCallContext context(MemoryCommand command) {
        return switch (command) {
            case MemoryCommand.Create value -> value.context();
            case MemoryCommand.Supersede value -> value.context();
            case MemoryCommand.Transition value -> value.context();
            case MemoryCommand.AcceptRevision value -> value.context();
        };
    }

    private static String key(MemoryCommand command) {
        return switch (command) {
            case MemoryCommand.Create value -> value.idempotencyKey().value();
            case MemoryCommand.Supersede value -> value.idempotencyKey().value();
            case MemoryCommand.Transition value -> value.idempotencyKey().value();
            case MemoryCommand.AcceptRevision value -> value.idempotencyKey().value();
        };
    }

    /** InvocationControl 是本次调用的瞬时护栏，不进入跨进程业务幂等身份。 */
    private static boolean sameCommand(MemoryCommand left, MemoryCommand right) {
        if (!left.getClass().equals(right.getClass())
                || !sameContext(context(left), context(right))
                || !key(left).equals(key(right))) {
            return false;
        }
        return switch (left) {
            case MemoryCommand.Create value -> value.fact().equals(
                    ((MemoryCommand.Create) right).fact());
            case MemoryCommand.Supersede value -> {
                MemoryCommand.Supersede other = (MemoryCommand.Supersede) right;
                yield value.current().equals(other.current())
                        && value.expectedRevision() == other.expectedRevision()
                        && value.replacement().equals(other.replacement());
            }
            case MemoryCommand.AcceptRevision value -> {
                MemoryCommand.AcceptRevision other = (MemoryCommand.AcceptRevision) right;
                yield value.current().equals(other.current()) && value.expectedRevision() == other.expectedRevision()
                        && value.proposal().equals(other.proposal()) && value.expectedProposalRevision() == other.expectedProposalRevision();
            }
            case MemoryCommand.Transition value -> {
                MemoryCommand.Transition other = (MemoryCommand.Transition) right;
                yield value.fact().equals(other.fact())
                        && value.expectedRevision() == other.expectedRevision()
                        && value.target() == other.target()
                        && value.reasonCode().equals(other.reasonCode());
            }
        };
    }

    private static boolean sameContext(PortCallContext left, PortCallContext right) {
        return left.runIdentity().equals(right.runIdentity())
                && left.identity().equals(right.identity());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 显式共享时模拟 development adapter 重建；生产 profile 禁止使用。 */
    public static final class State {
        private final Object monitor = new Object();
        private final Map<MemoryFactRef, List<MemoryFact>> histories = new LinkedHashMap<>();
        private final Map<OperationKey, Operation> operations = new HashMap<>();
    }

    private record OperationKey(String tenant, String id) { }
    private record Operation(MemoryCommand command, MemoryFact output, Optional<MemoryFact> before) { }

    private static final class RejectedFact extends RuntimeException {
        private final String reason;
        private RejectedFact(String reason) { this.reason = reason; }
    }

    private static final class StaleFact extends RuntimeException {
        private final MemoryFactRef ref;
        private final long revision;
        private final String reason;
        private StaleFact(MemoryFactRef ref, long revision, String reason) {
            this.ref = ref;
            this.revision = revision;
            this.reason = reason;
        }
    }
}
