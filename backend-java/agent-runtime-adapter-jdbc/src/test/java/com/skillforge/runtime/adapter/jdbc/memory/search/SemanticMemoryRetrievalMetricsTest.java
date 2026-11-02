package com.skillforge.runtime.adapter.jdbc.memory.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticMemoryRetrievalMetricsTest {
    @Test
    void oneRelevantResultAtRankTwoHasFullRecallButDiscountedGain() {
        var result = SemanticMemoryRetrievalDatabaseEvaluation.metrics(List.of("noise", "relevant"), Map.of("relevant", 2));
        assertEquals(1.0, result.get("recallAt5"));
        assertEquals(0.6309297535714574, ((Number) result.get("ndcgAt5")).doubleValue(), 1e-15);
    }

    @Test
    void idealOrderHasUnitNdcgAndMissingRelevantFactsRemainInRecallDenominator() {
        var ideal = SemanticMemoryRetrievalDatabaseEvaluation.metrics(List.of("a", "b"), Map.of("a", 2, "b", 1));
        assertEquals(1.0, ideal.get("ndcgAt5"));
        var incomplete = SemanticMemoryRetrievalDatabaseEvaluation.metrics(List.of("a"), Map.of("a", 2, "b", 1));
        assertEquals(0.5, incomplete.get("recallAt5"));
    }

    @Test
    void zeroRelevantQueriesAreSeparateAndNeverCountAsPerfectRecall() {
        var empty = SemanticMemoryRetrievalDatabaseEvaluation.metrics(List.of(), Map.of());
        assertNull(empty.get("recallAt5")); assertNull(empty.get("ndcgAt5"));
        assertEquals(false, empty.get("zeroRelevantNonempty"));
        assertEquals(true, SemanticMemoryRetrievalDatabaseEvaluation.metrics(List.of("noise"), Map.of()).get("zeroRelevantNonempty"));
    }

    @Test
    void cutoffAndDuplicateIdentityCannotInflateMetrics() {
        var result = SemanticMemoryRetrievalDatabaseEvaluation.metrics(List.of("n1", "n2", "n3", "n4", "n5", "relevant"), Map.of("relevant", 2));
        assertEquals(0.0, result.get("recallAt5")); assertEquals(0.0, result.get("ndcgAt5"));
        assertThrows(AssertionError.class, () -> SemanticMemoryRetrievalDatabaseEvaluation.metrics(List.of("a", "a"), Map.of("a", 2)));
    }
}
