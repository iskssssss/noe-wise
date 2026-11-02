package com.skillforge.core.knowledge;

/** 同一不可变来源上的检索候选；身份和内容在融合、重排中保持不变。 */
public record KnowledgeHit(String kbId, String documentId, String chunkId, String sourceVersion,
        String content, double score, Double vectorScore) {
    KnowledgeHit withScore(double nextScore) {
        return new KnowledgeHit(kbId, documentId, chunkId, sourceVersion, content, nextScore, vectorScore);
    }
    KnowledgeHit reranked(double relevanceScore, double originalScore) {
        return new KnowledgeHit(kbId, documentId, chunkId, sourceVersion, content, relevanceScore, originalScore);
    }
}
