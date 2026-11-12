package com.skillforge.core.knowledge.dto.response;

import java.util.List;

/**
 * 知识库检索响应 DTO。
 *
 * @param items 检索结果列表
 * @param rerankStatus 本次检索实际重排状态
 */
public record KnowledgeSearchResponse(
        List<KnowledgeSearchItemResponse> items,
        com.skillforge.core.knowledge.KnowledgeRerankStatus rerankStatus,
        com.skillforge.core.knowledge.KnowledgeService.SearchScope scope) {
}
