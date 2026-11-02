package com.skillforge.runtime.adapter.jdbc.memory.search;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.jdbc.memory.search.MemoryRankFusion.Hit;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemoryRankFusion.Leg;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemoryRankFusion.RankedHit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryRankFusionTest {
    @Test
    void fusesStandardReciprocalRanksAndPreservesBothOriginalLegs() {
        var result = MemoryRankFusion.fuse(
                List.of(new Hit("a", 1, 5000), new Hit("b", 4, 20), new Hit("c", 2, 5)),
                List.of(new Hit("b", 4, 0.95), new Hit("d", 3, 0.9), new Hit("a", 1, -0.2)), 60, 3);
        assertEquals(List.of("b", "a", "d", "c"), result.stream().map(RankedHit::factId).toList());
        assertEquals(1.0 / 62 + 1.0 / 61, result.getFirst().fusedScore(), 1e-15);
        assertEquals(Optional.of(new Leg(2, 20)), result.getFirst().lexical());
        assertEquals(Optional.of(new Leg(1, 0.95)), result.getFirst().vector());
        assertEquals(Optional.of(new Leg(3, -0.2)), result.get(1).vector());
        assertEquals(Optional.empty(), result.get(2).lexical());
    }

    @Test
    void topKAppliesToEachOriginalListAndRawScoreScaleDoesNotChangeRrf() {
        var result = MemoryRankFusion.fuse(
                List.of(new Hit("a", 1, 0.0001), new Hit("not-recalled", 1, 100)),
                List.of(new Hit("b", 1, 999999), new Hit("also-not-recalled", 1, 1)), 60, 1);
        assertEquals(List.of("a", "b"), result.stream().map(RankedHit::factId).toList());
        assertEquals(result.getFirst().fusedScore(), result.get(1).fusedScore());
        assertEquals(0.0001, result.getFirst().lexical().orElseThrow().score());
        assertEquals(999999, result.get(1).vector().orElseThrow().score());
    }

    @Test
    void exactFactAndRevisionIdentifyACandidateAndDifferentRevisionsRemainDistinct() {
        var result = MemoryRankFusion.fuse(List.of(new Hit("same-fact", 1, 1)),
                List.of(new Hit("same-fact", 2, 1)), 60, 10);
        assertEquals(2, result.size());
        assertEquals(List.of(1L, 2L), result.stream().map(RankedHit::revision).toList());
        assertTrue(result.stream().allMatch(hit -> hit.lexical().isEmpty() || hit.vector().isEmpty()));
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.getFirst()));
    }

    @Test
    void repeatedIdentityInOneLegDoesNotContributeTwiceOrShiftOtherOriginalRanks() {
        var result = MemoryRankFusion.fuse(
                List.of(new Hit("a", 1, 10), new Hit("a", 1, 999), new Hit("b", 1, 5)), List.of(), 60, 3);
        assertEquals(2, result.size());
        assertEquals(1.0 / 61, result.getFirst().fusedScore(), 1e-15);
        assertEquals(Optional.of(new Leg(1, 10)), result.getFirst().lexical());
        assertEquals(Optional.of(new Leg(3, 5)), result.get(1).lexical());
    }

    @Test
    void emptyLegsReturnEmptyAndInvalidLimitsOrNonfiniteScoresAreRejected() {
        assertEquals(List.of(), MemoryRankFusion.fuse(List.of(), List.of(), 60, 10));
        assertThrows(IllegalArgumentException.class, () -> MemoryRankFusion.fuse(List.of(), List.of(), 0, 10));
        assertThrows(IllegalArgumentException.class, () -> MemoryRankFusion.fuse(List.of(), List.of(), 60, 0));
        assertThrows(IllegalArgumentException.class, () -> new Hit("a", 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Hit("a", 1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Hit("a", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Hit(" ", 1, 1));
    }
}
