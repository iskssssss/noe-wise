package com.skillforge.institution.memory;

import com.skillforge.core.agent.memory.MemoryContent;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryOwner;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.MemoryScope;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactRead;
import com.skillforge.runtime.api.port.MemoryPort.CurrentFactReadBatch;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 候选治理只接受已绑定来源的解释；模型关联不能授权修改旧事实。 */
@Component
final class MemoryCandidateGovernance {
    private static final String PREFERENCE = "preference";
    private final JdbcMemoryPort memories;
    private final MemoryAccessPolicy access;

    MemoryCandidateGovernance(JdbcMemoryPort memories, MemoryAccessPolicy access) {
        this.memories = Objects.requireNonNull(memories);
        this.access = Objects.requireNonNull(access);
    }

    /** 模型工作已结束后进入逻辑槽位短串行区，避免两个不同值同时通过检查。 */
    <T> T withSlot(PortCallContext context, MemoryFactDraft draft, Supplier<T> write) {
        return memories.withExtractionSlot(context, draft.owner(), draft.type(),
                draft.subject(), draft.predicate(), write);
    }

    /** 冻结别名对应原审核对象；版本变化后仍保留原目标，确认时由双 CAS 拒绝。 */
    Decision evaluate(PortCallContext context, MemoryFactDraft draft, Candidate candidate,
            Optional<MemoryFact> referencedSnapshot) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(referencedSnapshot);
        String relation = candidate.relation().kind();
        boolean revision = Set.of("correction", "possible_conflict").contains(relation);
        MemoryFact target = revision ? referencedSnapshot.orElseThrow(() -> rejected("memory_relation_target_missing")) : null;
        if (revision) requireCompatible(draft, target);
        var reasons = baseReasons(context, draft, candidate.sensitive());
        var semantics = semantics(draft.object().canonicalJson());
        if (PREFERENCE.equals(draft.type().value())) {
            if (!"stable".equals(semantics.temporal().kind())) reasons.add("memory_temporal_review_required");
            if ("observed".equals(candidate.signal())) reasons.add("memory_preference_confirmation_required");
        }
        if (!candidate.uncertainties().isEmpty()) reasons.add("memory_interpretation_review_required");
        if ("repeated_confirmed".equals(candidate.signal())
                && candidate.evidence().stream().map(evidence -> evidence.sourceId()).distinct().count() < 2) {
            reasons.add("memory_independent_confirmation_required");
        }

        boolean canQuery = authorizeQuery(context, draft, reasons);
        if (canQuery) inspectGovernedSources(context, draft, reasons);
        if (revision) {
            var current = canQuery ? currentTarget(context, target.ref(), reasons) : Optional.<MemoryFact>empty();
            if (current.isPresent() && current.orElseThrow().revision() != target.revision()) {
                reasons.add("memory_revision_target_changed");
            }
            reasons.add("correction".equals(relation)
                    ? "memory_revision_confirmation_required" : "memory_possible_conflict_review_required");
            return new Decision(true, reasons, Optional.of(new RevisionTarget(target.ref(), target.revision())));
        }

        if (canQuery) inspectCurrentDifferences(context, draft, reasons);
        return new Decision(!reasons.isEmpty(), reasons, Optional.empty());
    }

    /** 群来源作者由已提交事件给出，不从系统提取任务的 requester 推导。 */
    Decision evaluateGroup(PortCallContext context, MemoryFactDraft draft, Candidate candidate,
            Optional<MemoryFact> referencedSnapshot, EndUserId sourceAuthor) {
        Objects.requireNonNull(sourceAuthor);
        Decision decision = evaluate(context, draft, candidate, referencedSnapshot);
        if (draft.owner() instanceof MemoryOwner.GroupMember member
                && !member.subjectEndUserId().equals(sourceAuthor)) {
            var reasons = new ArrayList<>(decision.reasons());
            reasons.add("memory_group_member_subject_confirmation_required");
            return new Decision(true, reasons, decision.revisionTarget());
        }
        return decision;
    }

    /** 正式结构化提案没有模型修订权；保留共享、敏感及可能差异的审核。 */
    Decision evaluateStructured(PortCallContext context, MemoryFactDraft draft, boolean sensitive) {
        var reasons = baseReasons(context, draft, sensitive);
        if (authorizeQuery(context, draft, reasons)) {
            inspectGovernedSources(context, draft, reasons);
            inspectCurrentDifferences(context, draft, reasons);
        }
        return new Decision(!reasons.isEmpty(), reasons, Optional.empty());
    }

    private void inspectGovernedSources(PortCallContext context, MemoryFactDraft draft, List<String> reasons) {
        JdbcMemoryPort.ExtractionGovernance governance;
        try {
            governance = memories.extractionGovernance(context, draft);
        } catch (RuntimeException unavailable) {
            reasons.add("memory_current_fact_read_unavailable");
            return;
        }
        switch (governance) {
            case ALLOWED -> { }
            case FORGOTTEN_SOURCE, SUPERSEDED_SOURCE -> throw rejected(governance.reasonCode());
            case FORGOTTEN_SLOT_REVIEW -> reasons.add(governance.reasonCode());
        }
    }

    private ArrayList<String> baseReasons(PortCallContext context, MemoryFactDraft draft, boolean sensitive) {
        var reasons = new ArrayList<String>();
        if (draft.scope() == MemoryScope.USER && !context.identity().scopes().contains("memory:manage")) {
            reasons.add("memory_user_scope_confirmation_required");
        }
        if (sensitive) reasons.add("memory_sensitive_review_required");
        return reasons;
    }

    private Optional<MemoryFact> currentTarget(PortCallContext context, MemoryFactRef target, List<String> reasons) {
        try {
            var values = memories.findCurrent(new CurrentFactReadBatch(List.of(new CurrentFactRead(context, target))));
            if (values.size() != 1 || values.getFirst().isEmpty()) {
                reasons.add("memory_revision_target_unavailable");
                return Optional.empty();
            }
            MemoryFact current = values.getFirst().orElseThrow();
            if (!current.ref().equals(target)) throw rejected("memory_relation_target_mismatch");
            if (!authorized(context, current)) {
                reasons.add("memory_current_access_denied");
                return Optional.empty();
            }
            return Optional.of(current);
        } catch (RuntimeException unavailable) {
            reasons.add("memory_current_fact_read_unavailable");
            return Optional.empty();
        }
    }

    private boolean authorizeQuery(PortCallContext context, MemoryFactDraft draft, List<String> reasons) {
        try {
            var decision = access.authorize(new MemoryAccessPolicy.Request(context, draft.owner(),
                    MemoryAccessPolicy.Operation.QUERY, Set.of(draft.scope()), Set.of(draft.type()), Optional.empty()));
            if (decision instanceof MemoryAccessPolicy.Decision.Allowed) return true;
            reasons.add("memory_current_access_denied");
        } catch (RuntimeException unavailable) {
            reasons.add("memory_current_fact_read_unavailable");
        }
        return false;
    }

    private void inspectCurrentDifferences(PortCallContext context, MemoryFactDraft draft, List<String> reasons) {
        try {
            for (MemoryFact fact : memories.relatedCurrentFacts(context, draft.owner(), draft.type(),
                    draft.subject(), draft.predicate())) {
                // 新的情节或不同条件可以并存；不同 JSON 值不是已确认的语义矛盾。
                if (PREFERENCE.equals(draft.type().value()) && compatible(draft, fact)
                        && !semanticBody(draft.object().canonicalJson()).equals(semanticBody(fact.object().canonicalJson()))) {
                    reasons.add("memory_possible_difference_review_required");
                    return;
                }
            }
        } catch (RuntimeException unavailable) {
            reasons.add("memory_current_fact_read_unavailable");
        }
    }

    private boolean authorized(PortCallContext context, MemoryFact fact) {
        return access.authorize(new MemoryAccessPolicy.Request(context, fact.owner(),
                MemoryAccessPolicy.Operation.QUERY, Set.of(fact.scope()), Set.of(fact.type()), Optional.empty()))
                instanceof MemoryAccessPolicy.Decision.Allowed;
    }

    private void requireCompatible(MemoryFactDraft draft, MemoryFact target) {
        if (!compatible(draft, target)) throw rejected("memory_relation_target_incompatible");
    }

    private boolean compatible(MemoryFactDraft draft, MemoryFact target) {
        return draft.owner().equals(target.owner()) && draft.type().equals(target.type())
                && draft.schemaVersion().equals(target.schemaVersion())
                && draft.object().schemaId().equals(target.object().schemaId())
                && draft.subject().equals(target.subject()) && draft.predicate().equals(target.predicate())
                && semantics(draft.object().canonicalJson()).conditions()
                        .equals(semantics(target.object().canonicalJson()).conditions())
                && overlaps(draft.validFrom(), end(draft.validTo(), draft.expiresAt()),
                        target.validFrom(), end(target.validTo(), target.expiresAt()));
    }

    private static Optional<Instant> end(Optional<Instant> validTo, Optional<Instant> expiresAt) {
        if (validTo.isEmpty()) return expiresAt;
        if (expiresAt.isEmpty()) return validTo;
        return Optional.of(validTo.orElseThrow().isBefore(expiresAt.orElseThrow())
                ? validTo.orElseThrow() : expiresAt.orElseThrow());
    }

    private static boolean overlaps(Instant leftStart, Optional<Instant> leftEnd,
            Instant rightStart, Optional<Instant> rightEnd) {
        return leftEnd.map(value -> value.isAfter(rightStart)).orElse(true)
                && rightEnd.map(value -> value.isAfter(leftStart)).orElse(true);
    }

    private MemoryContent.Semantics semantics(String payload) {
        return MemoryContent.semantics(MemoryContent.decode(payload));
    }

    private String semanticBody(String payload) {
        return MemoryContent.contentText(MemoryContent.decode(payload));
    }

    /** 原因用于审核展示与恢复；可能差异不会冒充已确认冲突。 */
    record Decision(boolean reviewRequired, List<String> reasons, Optional<RevisionTarget> revisionTarget) {
        Decision {
            reasons = List.copyOf(reasons.stream().distinct().toList());
            revisionTarget = Objects.requireNonNull(revisionTarget);
        }
    }

    record RevisionTarget(MemoryFactRef fact, long expectedRevision) {
        RevisionTarget {
            Objects.requireNonNull(fact);
            if (expectedRevision < 1) throw rejected("memory_revision_invalid");
        }
    }

    private static IllegalArgumentException rejected(String reason) {
        return new IllegalArgumentException(reason);
    }
}
