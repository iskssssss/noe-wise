package com.skillforge.core.knowledge.dto.response;

/** 精确来源的只读正文，不作为当前文档编辑快照。 */
public record DocumentVersionResponse(Long documentId, Long kbId, String sourceVersion,
        String title, String content) { }
