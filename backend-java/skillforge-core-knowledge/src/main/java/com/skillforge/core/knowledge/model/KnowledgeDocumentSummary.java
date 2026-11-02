package com.skillforge.core.knowledge.model;

import java.time.Instant;

/** 文档列表的窄投影，不读取正文及解析元数据。 */
public record KnowledgeDocumentSummary(Long documentId, String title, String sourceType,
        String status, String sourceVersion, Integer chunkCount, Instant createdAt) { }
