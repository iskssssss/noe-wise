package com.skillforge.runtime.adapter.jdbc.memory.search;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 标准 Reciprocal Rank Fusion；只融合候选引用，不把相似或矛盾事实合并为同一事实。 */
public final class MemoryRankFusion {
    private MemoryRankFusion() { }

    /** 已完成范围过滤的一条召回结果；原始分数保留自身量纲，不作跨检索器加法。 */
    public record Hit(String factId, long revision, double score) {
        public Hit {
            requireIdentity(factId, revision);
            requireFinite(score);
        }
    }

    /** 一条召回路径的原始一基排名和原始分数。 */
    public record Leg(int rank, double score) {
        public Leg {
            if (rank < 1) throw new IllegalArgumentException("memory_search_rank_must_be_positive");
            requireFinite(score);
        }
    }

    /** 融合名次分不是正确概率；lexical/vector 原始排名与分数用于冻结轨迹。 */
    public record RankedHit(String factId, long revision, double fusedScore,
            Optional<Leg> lexical, Optional<Leg> vector) {
        public RankedHit {
            requireIdentity(factId, revision);
            requireFinite(fusedScore);
            lexical = Objects.requireNonNull(lexical, "lexical");
            vector = Objects.requireNonNull(vector, "vector");
            if (fusedScore <= 0 || lexical.isEmpty() && vector.isEmpty())
                throw new IllegalArgumentException("memory_search_fused_hit_invalid");
        }
    }

    /**
     * 每条路径只取其前 topK 原始位置，按 1/(rankConstant+rank) 求和。
     * 同一 factId+revision 在同路径重复出现时仅首次位置贡献，不能刷高融合分。
     * 相同融合分按 factId、revision 排序；调用方随后读取权威事实并按真实正文做预算选择。
     */
    public static List<RankedHit> fuse(List<Hit> lexical, List<Hit> vector, int rankConstant, int topK) {
        if (rankConstant < 1 || topK < 1) throw new IllegalArgumentException("memory_search_fusion_limits_invalid");
        var merged = new HashMap<Identity, Candidate>();
        collect(List.copyOf(Objects.requireNonNull(lexical, "lexical")), topK, true, merged);
        collect(List.copyOf(Objects.requireNonNull(vector, "vector")), topK, false, merged);
        return merged.entrySet().stream().map(entry -> {
            var identity = entry.getKey(); var candidate = entry.getValue();
            double score = contribution(candidate.lexical, rankConstant) + contribution(candidate.vector, rankConstant);
            return new RankedHit(identity.factId, identity.revision, score,
                    Optional.ofNullable(candidate.lexical), Optional.ofNullable(candidate.vector));
        }).sorted(Comparator.comparingDouble(RankedHit::fusedScore).reversed()
                .thenComparing(RankedHit::factId).thenComparingLong(RankedHit::revision)).toList();
    }

    private static void collect(List<Hit> hits, int topK, boolean lexical, HashMap<Identity, Candidate> merged) {
        var seen = new HashSet<Identity>();
        for (int position = 0; position < Math.min(topK, hits.size()); position++) {
            Hit hit = hits.get(position);
            var key = new Identity(hit.factId(), hit.revision());
            if (!seen.add(key)) continue;
            var candidate = merged.computeIfAbsent(key, ignored -> new Candidate());
            var leg = new Leg(position + 1, hit.score());
            if (lexical) candidate.lexical = leg; else candidate.vector = leg;
        }
    }

    private static double contribution(Leg leg, int rankConstant) {
        return leg == null ? 0 : 1.0 / ((double) rankConstant + leg.rank());
    }
    private static void requireIdentity(String factId, long revision) {
        Objects.requireNonNull(factId, "factId");
        if (factId.isBlank() || revision < 1) throw new IllegalArgumentException("memory_search_fact_identity_invalid");
    }
    private static void requireFinite(double score) {
        if (!Double.isFinite(score)) throw new IllegalArgumentException("memory_search_score_not_finite");
    }
    private record Identity(String factId, long revision) { }
    private static final class Candidate {
        private Leg lexical;
        private Leg vector;
    }
}
