package com.skillforge.core.knowledge.model;

/**
 * 租户知识存储容量；正文为 UTF-8 字节，向量为浮点载荷字节，物理字节只统计正文与向量列。
 *
 * @param contentBytes 原始文档及唯一分块正文的逻辑字节
 * @param embeddingBytes 唯一向量的浮点载荷字节
 * @param chunkBodies 唯一分块正文数量
 * @param embeddings 唯一向量数量
 * @param physicalBytes 当前租户正文及向量行的实际 PostgreSQL 列存储字节
 * @param maxContentBytes 已配置的逻辑正文与向量字节上限，NULL 表示未设置
 * @param maxChunkBodies 已配置的唯一分块上限，NULL 表示未设置
 * @param sourceVersions 仍保留的来源版本数，含元数据修订与历史
 * @param chunkReferences 仍保留的集合成员关系数
 * @param frozenReferences Runtime冻结分块引用数
 * @param rebuildReferences 重建暂存向量引用数
 * @param indexProjectionHolds 进行中或未确认的外部索引来源持有者数
 * @param indexCleanupIntents 待确认的精确来源投影清理意图数
 */
public record KnowledgeStorageCapacity(long contentBytes, long embeddingBytes, long chunkBodies,
        long embeddings, long physicalBytes, Long maxContentBytes, Long maxChunkBodies,
        long sourceVersions, long chunkReferences, long frozenReferences, long rebuildReferences,
        long indexProjectionHolds, long indexCleanupIntents) {
    /** 预算使用逻辑载荷字节，避免将数据库压缩率变动作为受理依据。 */
    public long payloadBytes() { return Math.addExact(contentBytes, embeddingBytes); }
}
