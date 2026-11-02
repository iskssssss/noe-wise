package com.skillforge.runtime.api;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** 冻结实际检索方式、覆盖情况、预预算候选与采用结果，不把相关分声明为真值概率。 */
public record MemoryRetrievalDiagnostics(
        Mode mode,
        Optional<String> degradationReason,
        long visibleDocumentCount,
        long readyVectorCount,
        List<Candidate> candidates,
        List<String> budgetSelectedIds) {
    public MemoryRetrievalDiagnostics {
        mode = ApiContracts.requireNonNull(mode, "mode");
        degradationReason = ApiContracts.requireOptional(degradationReason, "degradationReason");
        degradationReason.ifPresent(value -> ApiContracts.requireText(value, "degradationReason"));
        visibleDocumentCount = ApiContracts.requireNonNegative(visibleDocumentCount, "visibleDocumentCount");
        readyVectorCount = ApiContracts.requireNonNegative(readyVectorCount, "readyVectorCount");
        if (readyVectorCount > visibleDocumentCount) throw new IllegalArgumentException("ready vectors must belong to visible documents");
        candidates = ApiContracts.immutableDistinctList(candidates, "candidates");
        budgetSelectedIds = ApiContracts.immutableDistinctTextList(budgetSelectedIds, "budgetSelectedIds");
        var refs = new HashSet<MemoryFactRef>();
        var ids = new HashSet<String>();
        for (Candidate candidate : candidates) {
            if (!refs.add(candidate.fact())) throw new IllegalArgumentException("candidate fact identity must be unique");
            ids.add(candidate.fact().factId());
        }
        if (!ids.containsAll(budgetSelectedIds)) throw new IllegalArgumentException("budget selections must name diagnostic candidates");
        if (mode == Mode.HYBRID && degradationReason.isPresent()) throw new IllegalArgumentException("hybrid result must not declare lexical degradation");
    }

    /** 没有可见索引文档且没有候选的正常词法查询。 */
    public static MemoryRetrievalDiagnostics empty() {
        return new MemoryRetrievalDiagnostics(Mode.LEXICAL, Optional.empty(), 0, 0, List.of(), List.of());
    }

    public enum Mode { LEXICAL, HYBRID }

    /** 同一候选的各通道原量纲得分和名次；不存在的通道必须同时缺分与名次。 */
    public record Candidate(MemoryFactRef fact, long revision,
            Optional<Double> lexicalScore, Optional<Integer> lexicalRank,
            Optional<Double> cosineSimilarity, Optional<Integer> vectorRank, double fusedScore) {
        public Candidate {
            fact = ApiContracts.requireNonNull(fact, "fact");
            revision = ApiContracts.requirePositive(revision, "revision");
            lexicalScore = ApiContracts.requireOptional(lexicalScore, "lexicalScore");
            lexicalRank = ApiContracts.requireOptional(lexicalRank, "lexicalRank");
            cosineSimilarity = ApiContracts.requireOptional(cosineSimilarity, "cosineSimilarity");
            vectorRank = ApiContracts.requireOptional(vectorRank, "vectorRank");
            if (lexicalScore.isPresent() != lexicalRank.isPresent() || cosineSimilarity.isPresent() != vectorRank.isPresent()
                    || lexicalScore.isEmpty() && cosineSimilarity.isEmpty()) throw new IllegalArgumentException("candidate channel score and rank must be paired");
            lexicalScore.ifPresent(value -> { if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid lexical score"); });
            lexicalRank.ifPresent(value -> ApiContracts.requirePositive(value, "lexicalRank"));
            cosineSimilarity.ifPresent(value -> { if (!Double.isFinite(value) || value < -1 || value > 1) throw new IllegalArgumentException("invalid cosine similarity"); });
            vectorRank.ifPresent(value -> ApiContracts.requirePositive(value, "vectorRank"));
            if (!Double.isFinite(fusedScore) || fusedScore < 0) throw new IllegalArgumentException("invalid fused score");
        }
    }
}
