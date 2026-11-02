package com.skillforge.core.knowledge.model;

/** 数据库按知识库聚合的数量；COUNT 使用 long，避免大规模分块计数溢出。 */
public record KnowledgeBaseCount(long kbId, long total) {}
