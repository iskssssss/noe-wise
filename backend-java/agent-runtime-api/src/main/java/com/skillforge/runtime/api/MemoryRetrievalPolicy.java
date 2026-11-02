package com.skillforge.runtime.api;

import java.util.Optional;

/** 本次查询完整冻结的检索参数；用途 profile 与算法版本分别表达。 */
public record MemoryRetrievalPolicy(
        String id,
        String version,
        String projectionVersion,
        String analyzerVersion,
        int candidateLimit,
        int rrfRankConstant,
        double minimumCosineSimilarity,
        Optional<EmbeddingModel> embeddingModel,
        VectorFailurePolicy vectorFailurePolicy) {
    public MemoryRetrievalPolicy {
        id = ApiContracts.requireText(id, "id");
        version = ApiContracts.requireText(version, "version");
        projectionVersion = ApiContracts.requireText(projectionVersion, "projectionVersion");
        analyzerVersion = ApiContracts.requireText(analyzerVersion, "analyzerVersion");
        candidateLimit = ApiContracts.requirePositive(candidateLimit, "candidateLimit");
        rrfRankConstant = ApiContracts.requirePositive(rrfRankConstant, "rrfRankConstant");
        if (!Double.isFinite(minimumCosineSimilarity) || minimumCosineSimilarity < -1 || minimumCosineSimilarity > 1) {
            throw new IllegalArgumentException("minimumCosineSimilarity must be finite and in [-1,1]");
        }
        embeddingModel = ApiContracts.requireOptional(embeddingModel, "embeddingModel");
        vectorFailurePolicy = ApiContracts.requireNonNull(vectorFailurePolicy, "vectorFailurePolicy");
    }

    /** 显式选择成熟词法分析首版；不会把旧词命中阈值转换为融合分。 */
    public static MemoryRetrievalPolicy lexicalV1() {
        return new MemoryRetrievalPolicy("memory-lexical", "1", "memory-body-v1", "lucene-cjk-9.12.3-v1",
                64, 60, 0.7, Optional.empty(), VectorFailurePolicy.FAIL);
    }

    /** 精确 embedding 模型配置；凭据仍由宿主解析，不写入查询。 */
    public record EmbeddingModel(String modelConfigId, long revision, int dimensions) {
        public EmbeddingModel {
            modelConfigId = ApiContracts.requireText(modelConfigId, "modelConfigId");
            revision = ApiContracts.requirePositive(revision, "revision");
            dimensions = ApiContracts.requirePositive(dimensions, "dimensions");
        }
    }

    /** 向量失败不能静默成为空结果；只有声明的降级允许继续。 */
    public enum VectorFailurePolicy { FAIL, LEXICAL_FALLBACK }
}
