package com.skillforge.institution.memory;

import java.util.List;
import java.util.ArrayList;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Candidate;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Evidence;
import com.skillforge.institution.memory.MemoryExtractionMaterial.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 来源队列及其候选明细共用父任务的领取 fence，不另建提取状态机。 */
@Mapper
public interface MemoryExtractionMapper {
    Job next(@Param("tenant") String tenant, @Param("extractor") String extractor, @Param("policy") String policy);
    int claim(@Param("job") Job job, @Param("extractor") String extractor);
    String lockClaim(@Param("job") Job job, @Param("extractor") String extractor);
    default FrozenInput input(Job job, String extractor) {
        var time = inputTime(job, extractor);
        return time == null ? null : new FrozenInput(java.time.Instant.ofEpochSecond(time.epochSecond(), time.nano()),
                aliases(job, extractor), subjects(job, extractor));
    }
    default int freezeInput(Job job, String extractor, FrozenInput input) {
        int changed = freezeInputTime(job, extractor, input);
        if (changed != 1) return changed;
        requireOne(insertSourceAlias(job, extractor));
        for (int ordinal = 0; ordinal < input.facts().size(); ordinal++)
            requireOne(insertAlias(job, extractor, ordinal, input.facts().get(ordinal)));
        for (int ordinal = 0; ordinal < input.groupSubjects().size(); ordinal++)
            requireOne(insertSubject(job, extractor, ordinal, input.groupSubjects().get(ordinal)));
        return changed;
    }
    InputTime inputTime(@Param("job") Job job, @Param("extractor") String extractor);
    List<FactAlias> aliases(@Param("job") Job job, @Param("extractor") String extractor);
    int freezeInputTime(@Param("job") Job job, @Param("extractor") String extractor, @Param("input") FrozenInput input);
    int insertAlias(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal, @Param("alias") FactAlias alias);
    int insertSourceAlias(@Param("job") Job job, @Param("extractor") String extractor);
    List<Long> subjects(@Param("job") Job job, @Param("extractor") String extractor);
    int insertSubject(@Param("job") Job job, @Param("extractor") String extractor,
            @Param("ordinal") int ordinal, @Param("subject") long subject);
    int modelResult(@Param("job") Job job, @Param("extractor") String extractor,
            @Param("status") String status, @Param("run") String run, @Param("configuration") String configuration,
            @Param("invocation") String invocation);
    default List<CandidateRow> candidates(Job job, String extractor) {
        List<CandidateRow> result = new ArrayList<>();
        for (var row : candidateData(job, extractor)) {
            Candidate candidate = row.candidate(conditions(job, extractor, row.ordinal()),
                    evidence(job, extractor, row.ordinal()), uncertainties(job, extractor, row.ordinal()));
            PreparedColumns preparedColumns = preparedColumns(job, extractor, row.ordinal());
            PreparedData prepared = preparedColumns == null ? null : preparedColumns.data(reviewReasons(job, extractor, row.ordinal()));
            result.add(new CandidateRow(row.ordinal(), row.commandKey(), row.status(), candidate, prepared,
                    row.targetFactId(), row.targetRevision(), row.factId(), row.factRevision(), row.errorCode()));
        }
        return List.copyOf(result);
    }
    default int insertCandidate(Job job, String extractor, int ordinal, String key, Candidate candidate) {
        int changed = insertCandidateData(job, extractor, CandidateData.from(ordinal, key, candidate));
        if (changed != 1) return changed;
        for (int i = 0; i < candidate.conditions().size(); i++) requireOne(insertCondition(job, extractor, ordinal, i, candidate.conditions().get(i)));
        for (int i = 0; i < candidate.uncertainties().size(); i++) requireOne(insertUncertainty(job, extractor, ordinal, i, candidate.uncertainties().get(i)));
        for (int i = 0; i < candidate.evidence().size(); i++) requireOne(insertEvidence(job, extractor, ordinal, i, candidate.evidence().get(i)));
        return changed;
    }
    default int prepareCandidate(Job job, String extractor, int ordinal, PreparedData prepared) {
        int changed = prepareCandidateData(job, extractor, ordinal, prepared);
        if (changed != 1) return changed;
        for (int i = 0; i < prepared.reviewReasons().size(); i++) requireOne(insertReviewReason(job, extractor, ordinal, i, prepared.reviewReasons().get(i)));
        return changed;
    }
    List<CandidateData> candidateData(@Param("job") Job job, @Param("extractor") String extractor);
    PreparedColumns preparedColumns(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal);
    int insertCandidateData(@Param("job") Job job, @Param("extractor") String extractor, @Param("candidate") CandidateData candidate);
    int prepareCandidateData(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal, @Param("prepared") PreparedData prepared);
    List<String> conditions(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal);
    List<String> uncertainties(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal);
    List<String> reviewReasons(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal);
    List<Evidence> evidence(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal);
    int insertCondition(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal, @Param("item") int item, @Param("text") String text);
    int insertUncertainty(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal, @Param("item") int item, @Param("text") String text);
    int insertReviewReason(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal, @Param("item") int item, @Param("text") String text);
    int insertEvidence(@Param("job") Job job, @Param("extractor") String extractor, @Param("ordinal") int ordinal, @Param("item") int item, @Param("evidence") Evidence evidence);
    int candidateResult(@Param("job") Job job, @Param("extractor") String extractor,
            @Param("ordinal") int ordinal, @Param("status") String status, @Param("fact") String fact,
            @Param("revision") Long revision, @Param("error") String error);
    String sourcePayload(@Param("job") Job job);
    int finish(@Param("job") Job job, @Param("extractor") String extractor,
            @Param("status") String status, @Param("fact") String fact,
            @Param("revision") Long revision, @Param("error") String error);
    int compactTerminalCandidates(@Param("tenant") String tenant, @Param("limit") int limit);

    record Job(String tenantId, String sourceEventId, String memoryType, String canonicalKey,
            String agentId, String sourceTargetKind, String sourceTargetId, long endUserId,
            String sessionId, String sourceExecutionId, String sourceEventType,
            String policyVersion, Long sourceTimeEpochSecond, Integer sourceTimeNano, String modelStatus, String systemRunId,
            String configurationRef, String invocationRef, String claimToken, String integrationId, String groupBindingId) {
        Job claimed(String token) {
            return new Job(tenantId, sourceEventId, memoryType, canonicalKey, agentId, sourceTargetKind,
                    sourceTargetId, endUserId, sessionId, sourceExecutionId, sourceEventType,
                    policyVersion, sourceTimeEpochSecond, sourceTimeNano, modelStatus, systemRunId, configurationRef, invocationRef, token,
                    integrationId, groupBindingId);
        }
        boolean group() { return "GROUP".equals(sourceTargetKind); }
    }

    /** 候选原解释和已准备草稿不可变；状态、命令和事实关联均为类型化列。 */
    record CandidateRow(int ordinal, String commandKey, String status, Candidate candidate,
            PreparedData prepared, String targetFactId, Long targetRevision,
            String factId, Long factRevision, String errorCode) {}
    private static void requireOne(int count) { if (count != 1) throw new IllegalStateException("memory_extraction_fence_lost"); }
}
