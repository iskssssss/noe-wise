package com.skillforge.institution.runtime;

import static com.skillforge.core.capability.connector.MemorySearchToolContract.*;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryQuery;
import com.skillforge.runtime.api.MemoryQueryProfile;
import com.skillforge.runtime.api.MemoryRetrievalTrace;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.MemoryTypeRef;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.MemoryTaskConstraint;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.group.GroupBindingService;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 按当前可信会话身份显式检索持久记忆；普通对话不会自动调用或注入该能力。 */
@Component
public final class MemorySearchToolHandler implements PlatformToolHandler {
    private static final Set<String> PARAMETERS = Set.of(QUERY, LIMIT, SUBJECT_END_USER_ID);
    private static final Set<MemoryScope> SCOPES = Set.of(
            MemoryScope.SESSION, MemoryScope.USER_AGENT, MemoryScope.USER);
    private static final Set<MemoryTypeRef> TYPES = Set.of(
            new MemoryTypeRef("preference"), new MemoryTypeRef("episode"));
    private static final Set<MemoryScope> GROUP_SCOPES = Set.of(MemoryScope.GROUP, MemoryScope.GROUP_MEMBER);
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 16;
    private static final int TOKEN_BUDGET = 4_096;
    private static final int CHARACTER_BUDGET = 16_384;

    private final MemoryRuntime runtime;
    private final Clock clock;
    private final MemorySearchProperties settings;
    private final GroupBindingService groups;

    public MemorySearchToolHandler(MemoryRuntime runtime, Clock clock,MemorySearchProperties settings, GroupBindingService groups) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings=Objects.requireNonNull(settings,"settings");
        this.groups=Objects.requireNonNull(groups,"groups");
    }

    @Override public String toolName() { return NAME; }

    @Override
    public Map<String, Object> execute(
            Map<String, Object> arguments,
            PlatformToolExecutionContext context) {
        try {
            return executeResolved(arguments, context);
        } catch (OutcomeUnknown unknown) {
            throw unknown;
        } catch (Rejected rejected) {
            return rejected(rejected.reason);
        } catch(IllegalStateException failure) {
            if(failure.getMessage()!=null&&failure.getMessage().startsWith("memory_frozen_fact_"))
                return rejected(failure.getMessage());
            throw failure;
        }
    }

    private Map<String, Object> executeResolved(
            Map<String, Object> arguments,
            PlatformToolExecutionContext context) {
        requireActive(arguments, context);
        if (!PARAMETERS.containsAll(arguments.keySet())) {
            throw argumentsRejected();
        }
        Object rawQuery = arguments.get(QUERY);
        if (!(rawQuery instanceof String query)
                || query.isBlank()
                || query.codePointCount(0, query.length()) > 2_000) {
            throw argumentsRejected();
        }
        SearchArguments request = new SearchArguments(query, integer(arguments, LIMIT, DEFAULT_LIMIT, 1, MAX_LIMIT), subject(arguments, context));
        requireGroupSubject(context,request.subjectEndUserId());
        String queryId = "search-memory:" + context.invocationId();

        Optional<MemoryRuntime.RetrievalReceipt> frozen;
        try {
            frozen = runtime.findLatestRetrieval(context.callContext(), queryId);
        } catch (IllegalStateException rejected) {
            throw rejectedFailure(rejected.getMessage());
        }
        MemoryRuntime.RetrievalReceipt receipt;
        if (frozen.isPresent()) {
            receipt = frozen.orElseThrow();
            MemoryQuery expected = query(context, queryId, request, receipt.query().asOf(),
                    receipt.query().retrievalPolicy(),receipt.query().taskConstraint());
            if (!receipt.query().equals(expected)) {
                throw rejectedFailure("memory_search_retrieval_mismatch");
            }
            requireHandlerTrace(receipt);
        } else {
            receipt = recorded(runtime.retrieve(new MemoryRuntime.RetrievalCall(
                    context.callContext(), query(context, queryId, request, clock.instant(),settings.freeze(),Optional.empty()))));
        }

        if (receipt.phase() == MemoryRuntime.Phase.CANDIDATE) {
            List<String> selectedIds = receipt.trace().candidateIds();
            var selected = new MemoryRetrievalTrace(
                    queryId, receipt.trace().candidateIds(), selectedIds, List.of(), List.of());
            receipt = recorded(runtime.advance(
                    receipt, MemoryRuntime.Phase.SELECTED, selected, occurredAt(receipt)));
        }
        if (receipt.phase() == MemoryRuntime.Phase.SELECTED) {
            var injected = new MemoryRetrievalTrace(
                    queryId, receipt.trace().candidateIds(), receipt.trace().selectedIds(),
                    receipt.trace().selectedIds(), List.of());
            receipt = recorded(runtime.advance(
                    receipt, MemoryRuntime.Phase.INJECTED, injected, occurredAt(receipt)));
        }
        if (receipt.phase() != MemoryRuntime.Phase.INJECTED
                && receipt.phase() != MemoryRuntime.Phase.REFERENCED) {
            throw rejectedFailure("memory_search_phase_invalid");
        }
        requireHandlerTrace(receipt);
        requireActive(arguments, context);

        var result=new LinkedHashMap<String,Object>();
        result.put(SUCCESS,true);
        result.put(QUERY_ID,receipt.query().queryId());
        result.put(RETRIEVAL_EVENT_ID,receipt.committedEvent().eventId().value());
        result.put(AS_OF,receipt.query().asOf().toString());
        result.put(RETRIEVAL_MODE,receipt.diagnostics().mode().name().toLowerCase(java.util.Locale.ROOT));
        result.put(RETRIEVAL_STATUS,receipt.diagnostics().degradationReason().isPresent()?"degraded":"normal");
        receipt.diagnostics().degradationReason().ifPresent(reason->{
            result.put(DEGRADATION_REASON,safeReason(reason));
            result.put(NOTICE,"本次向量检索不可用，已按冻结策略使用词法检索；结果可能不覆盖语义相近的表述。");
        });
        result.put(FACTS, receipt.injectedFacts().stream()
                        .map(MemorySearchToolHandler::factView)
                        .toList());
        return Map.copyOf(result);
    }

    private MemoryQuery query(
            PlatformToolExecutionContext context,
            String queryId,
            SearchArguments request,
            Instant asOf,MemoryRetrievalPolicy policy,Optional<MemoryTaskConstraint> task) {
        var owner = context.run().owner();
        MemoryOwner memoryOwner = owner.subject() instanceof SessionSubject.Group group
                ? new MemoryOwner.Group(group.groupBindingRef()) : new MemoryOwner.Session(owner.sessionRef());
        return new MemoryQuery(
                queryId,
                memoryOwner,
                request.subjectEndUserId(),
                request.query(),
                task,
                owner.subject() instanceof SessionSubject.Group ? GROUP_SCOPES : SCOPES,
                TYPES,
                MemoryQueryProfile.DEFAULT,
                asOf,
                policy,
                request.limit(),
                TOKEN_BUDGET,
                CHARACTER_BUDGET,
                true);
    }

    private Optional<EndUserId> subject(Map<String, Object> arguments, PlatformToolExecutionContext context) {
        if (!(context.run().owner().subject() instanceof SessionSubject.Group)) {
            if (arguments.containsKey(SUBJECT_END_USER_ID)) throw argumentsRejected();
            return Optional.empty();
        }
        if (!(context.run().requester() instanceof RunRequester.EndUser author))
            throw rejectedFailure("memory_group_requester_required");
        if (!arguments.containsKey(SUBJECT_END_USER_ID)) return Optional.of(author.endUserId());
        Object raw = arguments.get(SUBJECT_END_USER_ID);
        if (!(raw instanceof String value) || !value.matches("[1-9][0-9]{0,18}")) throw argumentsRejected();
        try { return Optional.of(new EndUserId(Long.parseLong(value))); }
        catch (IllegalArgumentException invalid) { throw argumentsRejected(); }
    }

    private record SearchArguments(String query, int limit, Optional<EndUserId> subjectEndUserId) { }

    private void requireGroupSubject(PlatformToolExecutionContext context,Optional<EndUserId> subject) {
        if (!(context.run().owner().subject() instanceof SessionSubject.Group group)) return;
        if (!(context.callContext().identity().principal() instanceof PrincipalRef.ServicePrincipal principal))
            throw rejectedFailure("memory_group_principal_required");
        try {
            groups.requireHistoricalMember(group.groupBindingRef().tenantId().value(),principal.integrationId(),
                    group.groupBindingRef().groupBindingId(),subject.orElseThrow().value());
        } catch (ApiException denied) {
            throw rejectedFailure("memory_group_subject_not_in_group".equals(denied.getMessage())
                    ? "memory_group_subject_not_in_group" : "memory_group_current_authorization_denied");
        }
    }

    private Instant occurredAt(MemoryRuntime.RetrievalReceipt receipt) {
        Instant now = clock.instant();
        Instant committedAt = receipt.committedEvent().occurredAt();
        return now.isBefore(committedAt) ? committedAt : now;
    }

    /** 对结果未知只重放一次相同 append，绝不重新查询或重新选择。 */
    private MemoryRuntime.RetrievalReceipt recorded(MemoryRuntime.RetrievalResult result) {
        if (result instanceof MemoryRuntime.RetrievalResult.Pending pending) {
            result = runtime.retry(pending.pending());
            if (!(result instanceof MemoryRuntime.RetrievalResult.Recorded)) {
                throw new OutcomeUnknown();
            }
        }
        return switch (result) {
            case MemoryRuntime.RetrievalResult.Recorded recorded -> recorded.receipt();
            case MemoryRuntime.RetrievalResult.Pending ignored -> throw new OutcomeUnknown();
            case MemoryRuntime.RetrievalResult.Stopped stopped ->
                    throw rejectedFailure(stopped.failure().reasonCode());
        };
    }

    private static void requireHandlerTrace(MemoryRuntime.RetrievalReceipt receipt) {
        List<String> candidateIds = receipt.trace().candidateIds();
        if (receipt.phase().ordinal() >= MemoryRuntime.Phase.SELECTED.ordinal()
                && !receipt.trace().selectedIds().equals(candidateIds)) {
            throw rejectedFailure("memory_search_retrieval_mismatch");
        }
        if (receipt.phase().ordinal() >= MemoryRuntime.Phase.INJECTED.ordinal()
                && !receipt.trace().injectedIds().equals(candidateIds)) {
            throw rejectedFailure("memory_search_retrieval_mismatch");
        }
    }

    private static Map<String, Object> factView(MemoryFact fact) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put(FACT_ID, fact.ref().factId());
        view.put(REVISION, fact.revision());
        view.put(SCOPE, fact.scope().name());
        view.put(TYPE, fact.type().value());
        view.put(SUBJECT, fact.subject());
        view.put(PREDICATE, fact.predicate());
        view.put(OBJECT_SCHEMA, fact.object().schemaId());
        view.put(OBJECT, fact.object().canonicalJson());
        view.put(VALID_FROM, fact.validFrom().toString());
        fact.validTo().ifPresent(value -> view.put(VALID_TO, value.toString()));
        fact.expiresAt().ifPresent(value -> view.put(EXPIRES_AT, value.toString()));
        return Map.copyOf(view);
    }

    private void requireActive(
            Map<String, Object> arguments,
            PlatformToolExecutionContext context) {
        if (arguments == null || context == null) {
            throw argumentsRejected();
        }
        try {
            if (context.cancellationSignal().isCancellationRequested()
                    || !clock.instant().isBefore(context.deadlineAt())) {
                throw argumentsRejected();
            }
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException rejected
                    && "search_memory_arguments_rejected".equals(rejected.getMessage())) {
                throw rejected;
            }
            throw argumentsRejected();
        }
    }

    private static int integer(
            Map<String, Object> arguments,
            String name,
            int fallback,
            int minimum,
            int maximum) {
        if (!arguments.containsKey(name)) {
            return fallback;
        }
        Object raw = arguments.get(name);
        if (!(raw instanceof Number number)) {
            throw argumentsRejected();
        }
        try {
            int value = new BigDecimal(number.toString()).intValueExact();
            if (value < minimum || value > maximum) {
                throw argumentsRejected();
            }
            return value;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw argumentsRejected();
        }
    }

    private static Rejected argumentsRejected() {
        return new Rejected("search_memory_arguments_rejected");
    }

    private static Rejected rejectedFailure(String reason) {
        return new Rejected(safeReason(reason));
    }

    private static Map<String, Object> rejected(String reason) {
        return Map.of(SUCCESS, false, ERROR, safeReason(reason));
    }

    private static String safeReason(String reason) {
        if (reason != null
                && reason.length() <= 128
                && reason.matches("(?:memory|search_memory)_[a-z0-9_]+")) {
            return reason;
        }
        return "memory_search_failed";
    }

    private static final class Rejected extends RuntimeException {
        private final String reason;

        private Rejected(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    private static final class OutcomeUnknown extends RuntimeException {
        private OutcomeUnknown() {
            super("memory_search_append_outcome_unknown");
        }
    }
}
