package com.skillforge.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 检索用途、冻结策略和各通道原量纲分别表达，不沿用旧词命中阈值。 */
class MemoryRetrievalContractTest {
    private static final MemoryFactRef REF = new MemoryFactRef(new TenantId("retrieval-tenant"), "fact-1");

    @Test
    void explicitLexicalPolicyIsCompleteAndImmutable() {
        var policy = MemoryRetrievalPolicy.lexicalV1();
        assertEquals("memory-lexical", policy.id());
        assertEquals("lucene-cjk-9.12.3-v1", policy.analyzerVersion());
        assertEquals(Optional.empty(), policy.embeddingModel());
        assertEquals(MemoryRetrievalPolicy.VectorFailurePolicy.FAIL, policy.vectorFailurePolicy());
        assertEquals("task-1", new MemoryTaskConstraint("task-1").taskId());
        assertThrows(IllegalArgumentException.class, () -> new MemoryTaskConstraint(" "));
    }

    @Test
    void policyRejectsInvalidModelDimensionsAndNonfiniteSimilarity() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryRetrievalPolicy.EmbeddingModel("model", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new MemoryRetrievalPolicy.EmbeddingModel("model", 0, 3));
        assertThrows(IllegalArgumentException.class, () -> policy(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> policy(1.1));
        assertThrows(IllegalArgumentException.class, () -> policy(-1.1));
    }

    @Test
    void diagnosticsKeepExactRevisionRanksAndNativeScores() {
        var candidate = new MemoryRetrievalDiagnostics.Candidate(REF, 3, Optional.of(4.25), Optional.of(2),
                Optional.of(0.92), Optional.of(1), 1d / 62 + 1d / 61);
        var diagnostics = new MemoryRetrievalDiagnostics(MemoryRetrievalDiagnostics.Mode.HYBRID,
                Optional.empty(), 5, 5, List.of(candidate), List.of("fact-1"));
        assertEquals(3, diagnostics.candidates().getFirst().revision());
        assertEquals(Optional.of(4.25), diagnostics.candidates().getFirst().lexicalScore());
        assertThrows(IllegalArgumentException.class, () -> new MemoryRetrievalDiagnostics(
                MemoryRetrievalDiagnostics.Mode.HYBRID, Optional.empty(), 5, 5,
                List.of(candidate), List.of("missing")));
        assertThrows(IllegalArgumentException.class, () -> new MemoryRetrievalDiagnostics(
                MemoryRetrievalDiagnostics.Mode.HYBRID, Optional.empty(), 5, 5,
                List.of(candidate, new MemoryRetrievalDiagnostics.Candidate(REF, 4, Optional.of(1d),
                        Optional.of(3), Optional.empty(), Optional.empty(), 0.1)), List.of()));
    }

    @Test
    void diagnosticsRejectMissingRankAndInconsistentCoverage() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryRetrievalDiagnostics.Candidate(
                REF, 1, Optional.of(1d), Optional.empty(), Optional.empty(), Optional.empty(), 0.1));
        assertThrows(IllegalArgumentException.class, () -> new MemoryRetrievalDiagnostics(
                MemoryRetrievalDiagnostics.Mode.LEXICAL, Optional.empty(), 1, 2, List.of(), List.of()));
        assertEquals(0, MemoryRetrievalDiagnostics.empty().visibleDocumentCount());
    }

    private static MemoryRetrievalPolicy policy(double minimumSimilarity) {
        return new MemoryRetrievalPolicy("hybrid", "1", "memory-body-v1", "lucene-cjk-9.12.3-v1",
                64, 60, minimumSimilarity, Optional.of(new MemoryRetrievalPolicy.EmbeddingModel("model", 2, 3)),
                MemoryRetrievalPolicy.VectorFailurePolicy.LEXICAL_FALLBACK);
    }
}
