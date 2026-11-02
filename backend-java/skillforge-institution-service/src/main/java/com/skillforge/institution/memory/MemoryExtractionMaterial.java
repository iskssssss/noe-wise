package com.skillforge.institution.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.core.agent.memory.MemoryJsonCodec;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Evidence;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Relation;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Temporal;
import com.skillforge.runtime.api.port.MemoryPort.MemoryFactDraft;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 提取任务专属持久材料；固定业务字段实体化，开放object值单独使用JSON。 */
public final class MemoryExtractionMaterial {
    private MemoryExtractionMaterial() { }

    public record FactAlias(String alias, String factId, long revision) { }
    public record FrozenInput(Instant sourceTime, List<FactAlias> facts, List<Long> groupSubjects) {
        public FrozenInput {
            java.util.Objects.requireNonNull(sourceTime); facts = List.copyOf(facts); groupSubjects = List.copyOf(groupSubjects);
        }
        public long sourceTimeEpochSecond() { return sourceTime.getEpochSecond(); }
        public int sourceTimeNano() { return sourceTime.getNano(); }
        public int factCount() { return facts.size(); }
        public int subjectCount() { return groupSubjects.size(); }
    }
    public record InputTime(long epochSecond, int nano) { }
    public record PreparedColumns(String type, String scope, String retentionClass, String schemaVersion,
            String payloadSchemaId, long validFromEpochSecond, int validFromNano,
            Long validToEpochSecond, Integer validToNano, Long expiresEpochSecond, Integer expiresNano,
            double confidence, double importance, boolean reviewRequired, String initialStatus,
            String targetFactId, Long targetRevision) {
        public PreparedData data(List<String> reasons) {
            return new PreparedData(type, scope, retentionClass, schemaVersion, payloadSchemaId, validFromEpochSecond, validFromNano,
                    validToEpochSecond, validToNano, expiresEpochSecond, expiresNano, confidence, importance,
                    reviewRequired, initialStatus, targetFactId, targetRevision, reasons);
        }
    }

    /** 原草稿的不变固定字段；正文由原typed候选与本记录唯一重建。 */
    public record PreparedData(String type, String scope, String retentionClass, String schemaVersion,
            String payloadSchemaId, long validFromEpochSecond, int validFromNano,
            Long validToEpochSecond, Integer validToNano, Long expiresEpochSecond, Integer expiresNano,
            double confidence, double importance, boolean reviewRequired, String initialStatus,
            String targetFactId, Long targetRevision, List<String> reviewReasons) {
        public PreparedData { reviewReasons = List.copyOf(reviewReasons); }
        public Instant validFrom() { return Instant.ofEpochSecond(validFromEpochSecond, validFromNano); }
        public Optional<Instant> validTo() { return instant(validToEpochSecond, validToNano); }
        public Optional<Instant> expiresAt() { return instant(expiresEpochSecond, expiresNano); }
        public int reviewReasonCount() { return reviewReasons.size(); }
        public static PreparedData from(MemoryFactDraft draft, List<String> reasons, String targetFact, Long targetRevision) {
            if (!draft.derivedFrom().map(value -> value.factId()).equals(Optional.ofNullable(targetFact)))
                throw new IllegalArgumentException("memory_extraction_prepared_target_mismatch");
            return new PreparedData(draft.type().value(), draft.scope().name(), draft.retentionClass().value(), draft.schemaVersion(),
                    draft.object().schemaId(), draft.validFrom().getEpochSecond(), draft.validFrom().getNano(),
                    draft.validTo().map(Instant::getEpochSecond).orElse(null), draft.validTo().map(Instant::getNano).orElse(null),
                    draft.expiresAt().map(Instant::getEpochSecond).orElse(null), draft.expiresAt().map(Instant::getNano).orElse(null),
                    draft.confidence(), draft.importance(), draft.reviewRequired(), draft.initialStatus().name(), targetFact, targetRevision, reasons);
        }
    }

    /** 只投影候选固定列；valueJson是唯一开放业务值，不序列化整个Candidate。 */
    public record CandidateData(int ordinal, String commandKey, String status, String type,
            String subject, String predicate, String valueJson, String signal, boolean sensitive, String scope,
            String temporalKind, Long temporalFromEpochSecond, Integer temporalFromNano,
            Long temporalToEpochSecond, Integer temporalToNano, String relationKind, String relationTarget,
            int conditionCount, int uncertaintyCount, int evidenceCount,
            String targetFactId, Long targetRevision, String factId, Long factRevision, String errorCode) {
        public static CandidateData from(int ordinal, String key, Candidate candidate) {
            var from = Optional.ofNullable(candidate.temporal().from());
            var to = Optional.ofNullable(candidate.temporal().to());
            return new CandidateData(ordinal, key, "READY", candidate.type(), candidate.subject(), candidate.predicate(),
                    MemoryJsonCodec.encode(candidate.object()), candidate.signal(), candidate.sensitive(), candidate.scope(), candidate.temporal().kind(),
                    from.map(Instant::getEpochSecond).orElse(null), from.map(Instant::getNano).orElse(null),
                    to.map(Instant::getEpochSecond).orElse(null), to.map(Instant::getNano).orElse(null),
                    candidate.relation().kind(), candidate.relation().target(), candidate.conditions().size(), candidate.uncertainties().size(),
                    candidate.evidence().size(), null, null, null, null, null);
        }
        public Candidate candidate(List<String> conditions, List<Evidence> evidence, List<String> uncertainties) {
            if (conditions.size() != conditionCount || uncertainties.size() != uncertaintyCount || evidence.size() != evidenceCount)
                throw new IllegalStateException("memory_extraction_material_incomplete");
            return new Candidate(type, subject, predicate,
                        MemoryJsonCodec.decode(valueJson, JsonNode.class, "memory_extraction_value_invalid"), signal, sensitive, scope,
                        new Temporal(temporalKind, instant(temporalFromEpochSecond, temporalFromNano).orElse(null),
                                instant(temporalToEpochSecond, temporalToNano).orElse(null)), conditions, evidence,
                        new Relation(relationKind, relationTarget), uncertainties);
        }
    }

    private static Optional<Instant> instant(Long second, Integer nano) {
        if ((second == null) != (nano == null)) throw new IllegalArgumentException("memory_extraction_time_incomplete");
        return second == null ? Optional.empty() : Optional.of(Instant.ofEpochSecond(second, nano));
    }
}
